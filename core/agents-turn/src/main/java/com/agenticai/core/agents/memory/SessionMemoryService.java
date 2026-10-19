package com.agenticai.core.agents.memory;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed, per-session conversation memory.
 *
 * <p>Storage shape: a Redis LIST at key {@code agentic:session:<id>}, where {@code <id>} is ALWAYS
 * a {@link #scopedSessionId} (SEC-004 - never a raw client-chosen id), one JSON-encoded {@link
 * ChatTurn} per list element, oldest first. TTL is refreshed on every append so active
 * conversations don't expire mid-use, but idle ones clean themselves up automatically.
 *
 * <h2>Bounded replay (2026-08-11 token audit)</h2>
 *
 * {@link #getHistory} used to return the ENTIRE list — every turn ever stored, no cap.
 * AgentChatService replays that result into the prompt on every turn, which made unbounded history
 * the single largest per-turn token driver: an active session refreshes its TTL on each append, so
 * a busy day of chatting grew the prompt linearly with no ceiling. {@code getHistory} is therefore
 * now a WINDOW — the newest turns, bounded by a turn cap, a cumulative content-character budget AND
 * a cumulative estimated-token budget (whichever trips first - see {@link #estimateTokens} for why
 * characters alone were not enough), walking from the tail so it is always the oldest context that
 * falls off. Consumers that genuinely need the full transcript (none in main code today — the demo
 * transcript mailer reads the raw list itself, capped at its own 40 newest entries) use {@link
 * #getFullHistory}, which keeps the old contract.
 *
 * <p>All three window limits are admin-selectable platform settings (product-owner requirement — no
 * hardcoded spend knobs): {@code platform.chat.history-max-turns} / {@code
 * platform.chat.history-max-chars} / {@code platform.chat.history-max-tokens} rows via {@link
 * PlatformSettingsService}, falling back to the {@code @Value} defaults when no row exists yet.
 * Reads go through the same 10s time-boxed cache shape as {@code
 * FreeTierUsageLimiterService#effectiveQuotaConfig} — this sits on the chat hot path, so an
 * uncached read would add a Postgres round trip to every message. (A parallel change adds the admin
 * GET/PUT endpoint for the turn and char keys; the token key has no admin endpoint yet and is read
 * straight from its {@code platform_setting} row when one exists. This class only consumes them.)
 *
 * <h2>Summarisation when turns leave the replay window</h2>
 *
 * The replay window above is where conversation is effectively LOST to the model: once a turn falls
 * out of the newest {@code history-max-turns}, the model never sees it again, even though the list
 * still stores it. This class used to summarise only at the STORAGE trim ({@value
 * #MAX_STORED_ENTRIES} entries), which left a hole the size of the storage ceiling minus the
 * window: in a 100-turn session with the default 40-turn window, turns 1-60 were neither replayed
 * (outside the window) nor summarised (the list never got near 400) - the model had simply
 * forgotten how the conversation started while every byte of it sat in Redis (2026-09-28 Spring AI
 * gap audit).
 *
 * <p>{@link #appendTurn} therefore now folds turns into a rolling natural-language summary (via
 * {@link SessionSummaryService}) as soon as they leave the REPLAY window, in batches of {@value
 * #SUMMARY_FOLD_BATCH}: once at least that many stored turns sit outside the window and have not
 * been folded yet, the whole batch is handed to the summariser in one call. The summary lives at a
 * sibling key ({@link #SUMMARY_KEY_PREFIX}) on the same TTL as the list, and {@link #getHistory}
 * prepends it to the replay window so the model keeps the gist of everything that fell out of view.
 *
 * <p><b>The "summarised-up-to" marker.</b> Because the list keeps the folded turns (they are still
 * transcript, still read by {@link #getFullHistory} and the demo transcript mailer), something has
 * to remember which of them the summary already covers. That is a small STRING counter at {@link
 * #FOLD_MARKER_KEY_PREFIX}: the number of entries at the HEAD of the current list that are already
 * folded. Claiming a batch - read the marker, work out the out-of-window range, {@code LRANGE} it,
 * advance the marker - happens in ONE Lua script ({@link SessionAppendScript#FOLD_CLAIM_SCRIPT}),
 * so two appends racing on the same session cannot both claim the same batch: nothing folds twice.
 * The storage trim, which shifts every index, runs in a second script ({@link
 * SessionAppendScript#TRIM_SCRIPT}) that decrements the marker by exactly the number of entries it
 * removed, in the same atomic step, so the marker never points at the wrong turn.
 *
 * <p><b>Why the append path and not the read path.</b> Summarising in {@link #getHistory} -
 * condensing whatever the window happens to exclude on this turn - would pay the cost once per
 * TURN, on the chat hot path, for a conversation that mostly has not changed since the last time it
 * was summarised. The append is already a write, it knows exactly which turn just pushed another
 * one out of view, and batching means one small LLM call per {@value #SUMMARY_FOLD_BATCH} turns of
 * a long session, run on a background thread. The read side still does one extra Redis {@code
 * GETEX} and no thinking at all.
 *
 * <p>Independently of all this, {@link #appendTurn} still LTRIMs the stored list to the newest
 * {@value #MAX_STORED_ENTRIES} entries so the Redis value itself can never grow without limit - the
 * window bounds the prompt, the trim bounds the storage, and the fold now happens well before the
 * trim ever touches anything (see {@link SessionSummaryFolder#foldWindowTurns} for why the fold window is capped so
 * this holds for every admin window setting).
 *
 * <p>Residual gaps, stated rather than hidden: (1) the fold is keyed on the TURN cap only; a turn
 * that is inside the turn cap but dropped from this particular read by the char/token budget is not
 * summarised (it is still replayed on later, lighter turns, so it is not permanently lost the way
 * the 1-60 hole was). (2) The marker advances when a batch is CLAIMED, before the background LLM
 * call; if that call fails, the batch is simply not in the summary - the same degradation every
 * other fail-open path in this feature has. (3) Two folds in flight at once (only possible if a
 * single session crosses two batch boundaries within one LLM round trip) could each write a summary
 * built on the same predecessor, and the later write wins. (4) The remaining
 * read-then-append-then-expire sequence across a chat exchange is still not transactional, as
 * before - acceptable for the per-session write rates chat produces.
 */
@Component
public class SessionMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryService.class);

    /**
     * One WARN per minute while Redis is down, not one per chat turn. Redis is the ONLY store of
     * session history, so there is no in-memory copy to fall back to (an earlier audit note said
     * there was; there is not): history reads degrade to "no history" and appends are skipped, so a
     * Redis outage costs the conversation's memory, never the user's turn.
     */
    private static final RateLimitedLog REDIS_DOWN_LOG =
            new RateLimitedLog(log, Duration.ofMinutes(1));

    private static final String KEY_PREFIX = "agentic:session:";

    // PlatformSetting keys - kept public so the admin controller/UI and tests
    // reference the exact same strings, same convention as
    // FreeTierUsageLimiterService's KEY_* constants.
    public static final String KEY_HISTORY_MAX_TURNS = "platform.chat.history-max-turns";
    public static final String KEY_HISTORY_MAX_CHARS = "platform.chat.history-max-chars";
    public static final String KEY_HISTORY_MAX_TOKENS = "platform.chat.history-max-tokens";

    /**
     * Hard storage bound applied via LTRIM on every append — the newest {@value} entries are kept,
     * older ones are discarded by Redis itself. Deliberately a wide constant, not an admin knob: it
     * is 10x the default replay window (so no admin-tunable window value in a sane range ever loses
     * data it would have replayed) and exists purely so a pathological session (a bot loop, a load
     * test) cannot grow one Redis value without limit for its whole 24h TTL. {@code
     * DemoTranscriptEmailService} reads this same list directly and only ever wants its newest 40
     * entries, so the trim is invisible to it.
     */
    // Public (2026-08-12): PlatformController's chat-generation-settings PUT
    // caps the admin-tunable replay window at this same value - a window
    // larger than what the store keeps would silently behave as this cap, so
    // the write-side bound must reference the storage bound, not restate it.
    public static final int MAX_STORED_ENTRIES = 400;

    /**
     * How many out-of-window turns are folded into the rolling summary at a time.
     *
     * <p>Without batching, folding would be a per-append event: every new turn pushes exactly one
     * older turn out of the replay window, which would mean one LLM summarisation call for every
     * single turn of a long session. So a fold only fires once at least {@value} turns have left
     * the window without being folded, and then takes all of them in one call - a session pays one
     * small background completion per {@value} turns, not per turn. The cost of the batching is
     * that up to {@value}-1 turns can sit briefly in neither the window nor the summary; they are
     * still in the list and are folded at the next batch boundary.
     *
     * <p>Deliberately equal to the default replay window (40 turns). The fold window is
     * additionally capped at {@code MAX_STORED_ENTRIES - SUMMARY_FOLD_BATCH} (see {@link
     * #foldWindowTurns}) so a batch is always claimed before the storage trim can reach an unfolded
     * turn. With the summariser off none of this runs and the trim is the plain {@value
     * #MAX_STORED_ENTRIES} it always was.
     */
    static final int SUMMARY_FOLD_BATCH = 40;

    /**
     * Key namespace for the "summarised-up-to" marker: {@code agentic:session-folded:<id>}, a
     * STRING holding how many entries at the HEAD of the current list the rolling summary already
     * covers. Distinct PREFIX, not a suffix on the history key, for exactly the aliasing reason
     * {@link #SUMMARY_KEY_PREFIX}'s javadoc spells out: {@code sessionId} is caller-supplied, and a
     * suffix scheme would let one session's id collide with another session's marker key.
     */
    static final String FOLD_MARKER_KEY_PREFIX = "agentic:session-folded:";

    /**
     * Key namespace for the session owner record: {@code agentic:session-owner:<id>}, a STRING
     * holding {@code tenantId, newline, subject} of whoever made the first append. Read only by
     * {@link #isOwnedBy} (chat export). A distinct prefix for the same aliasing reason as {@link
     * #SUMMARY_KEY_PREFIX}.
     */
    static final String OWNER_KEY_PREFIX = "agentic:session-owner:";

    /**
     * Key namespace holding the rolling summary: {@code agentic:session-summary:<id>}.
     *
     * <p>A separate STRING key rather than a synthetic entry in the list, and rather than a third
     * {@link MessageRole}: the list is read directly by {@code DemoTranscriptEmailService} (which
     * duplicates the prefix — see its javadoc) and by {@link #getFullHistory}'s transcript
     * consumers, and a summary blob masquerading as a conversation turn would silently corrupt
     * both. Adding a role constant would additionally change what already-stored JSON in live Redis
     * means.
     *
     * <p><b>Why a distinct PREFIX and not a {@code :summary} suffix on the history key</b>
     * (adversarial-review fix, 2026-08-17): {@code sessionId} is caller-supplied at the web
     * endpoint ({@code AgentController#chat} accepts any string, not just UUIDs), so with a suffix
     * scheme a caller who starts a chat with {@code sessionId = "<victim>:summary"} would get their
     * history list RPUSHed at the exact key the victim's summary STRING lives at — the victim's
     * summary reads then hit WRONGTYPE (silently lost, fail-open), and the victim's next summary
     * SET would destroy the attacker-named session's entire stored history. With a distinct prefix
     * segment ({@code agentic:session-summary:} vs {@code agentic:session:}) no caller-supplied id
     * can make one namespace's key equal the other's — the two literals differ at a fixed position
     * before any caller input begins. No migration concern: this feature has not shipped, so no
     * summary is stored under the old suffix scheme in any live Redis.
     */
    static final String SUMMARY_KEY_PREFIX = "agentic:session-summary:";

    /**
     * Prefix the rolling summary is replayed under. Labelled explicitly so the model treats it as
     * background rather than as something the user just said — the summary is machine-generated
     * text about the conversation, not a message in it.
     */
    static final String SUMMARY_PREAMBLE =
            "[Earlier in this conversation, condensed automatically because it no longer fits in"
                    + " memory. Background context only - not something the user just said:]\n";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration sessionTtl;

    /**
     * {@link ObjectProvider} rather than a hard dependency, for the same reason {@code
     * AgentToolCallApprovalExecutor} holds THIS service through one: summarisation is strictly
     * optional, and a context that has not got the bean (a slim test context, or a deployment with
     * the feature switched off) must still be able to store and replay chat history. {@code
     * getIfAvailable() == null} is the outermost of this feature's fail-open switches — with no
     * summariser, the append is byte-for-byte the plain push/LTRIM/EXPIRE it was before. Held by
     * {@link SessionSummaryFolder}, which owns the rolling-summary machinery.
     */
    private final SessionSummaryFolder folder;

    /** The session owner record (chat export); see {@link SessionOwnerRecords}. */
    private final SessionOwnerRecords owners;

    /**
     * The admin-tunable replay window (turn, char and token caps); see {@link
     * HistoryWindowSettings}.
     */
    private final HistoryWindowSettings windowSettings;

    public SessionMemoryService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            PlatformSettingsService platformSettingsService,
            ObjectProvider<SessionSummaryService> summaryService,
            @Value("${app.chat.session-ttl-hours:24}") long sessionTtlHours,
            @Value("${app.chat.history-max-turns:40}") int historyMaxTurns,
            @Value("${app.chat.history-max-chars:32000}") int historyMaxChars,
            @Value("${app.chat.history-max-tokens:8000}") int historyMaxTokens,
            @Value("${app.chat.history-config-cache-ms:10000}") long configCacheMs) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.windowSettings =
                new HistoryWindowSettings(
                        platformSettingsService,
                        historyMaxTurns,
                        historyMaxChars,
                        historyMaxTokens,
                        configCacheMs);
        this.sessionTtl = Duration.ofHours(sessionTtlHours);
        this.folder =
                new SessionSummaryFolder(
                        redisTemplate, sessionTtl, summaryService, windowSettings, this::deserialize);
        this.owners = new SessionOwnerRecords(redisTemplate, sessionTtl);
    }

    /**
     * {@code app.chat.session-append-single-roundtrip}: one Lua round trip per {@link #appendTurn}
     * instead of 3 to 6. Spring sets it (default true); a hand-built instance keeps the legacy
     * multi-call sequence, which is also the fallback when the script itself is refused.
     */
    private volatile boolean singleRoundTripAppend;

    private final RedisScriptRefusal appendScriptRefusal = new RedisScriptRefusal();

    @org.springframework.beans.factory.annotation.Autowired
    public void setSingleRoundTripAppend(
            @Value("${app.chat.session-append-single-roundtrip:true}") boolean enabled) {
        this.singleRoundTripAppend = enabled;
    }

    /**
     * The bounded replay window — what AgentChatService feeds back into the prompt each turn.
     * Returns the NEWEST turns in chronological order, walking from the tail and stopping once the
     * turn cap, the cumulative content-character budget or the cumulative estimated-token budget
     * ({@link #estimateTokens}) would be exceeded - whichever trips first; the newest turn is
     * always included even if it alone busts either budget (an empty window would silently drop the
     * context of the reply the user just got).
     *
     * <p>When the cut lands mid-exchange — the oldest turn in the window is an ASSISTANT reply
     * whose USER prompt fell outside — that orphaned assistant turn is dropped too, so the model
     * never sees a conversation that opens with its own reply to a question it can't see.
     *
     * <p>When turns have left the replay window at some point and a rolling summary exists, it is
     * injected AHEAD of the window — see {@link SessionSummaryFolder#withSummary}. Injection happens after all the
     * windowing above, on purpose: the summary must not be charged against the char/token budgets
     * (it is already bounded on its own by {@code SessionSummaryService}'s {@code
     * history-summary-max-chars}, and it is the cheapest context in the prompt per unit of
     * conversation it stands for) and must not be reachable by the orphaned-assistant boundary
     * rule.
     *
     * <p>Full-transcript consumers must use {@link #getFullHistory} instead.
     */
    public List<ChatTurn> getHistory(String sessionId) {
        HistoryWindowSettings.HistoryConfig config = windowSettings.current();
        // Fetch only the newest maxTurns elements from Redis (negative range
        // indexes count from the tail) - no point deserializing entries the
        // window could never include.
        List<String> raw;
        try {
            raw = redisTemplate.opsForList().range(key(sessionId), -config.maxTurns(), -1);
        } catch (RuntimeException e) {
            return historyUnavailable("read", e);
        }
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        // A failed-reply entry (ChatTurn#failedAssistant) is the transcript's record of an error
        // the
        // person saw; it is not something the model said, so it never enters a prompt.
        List<ChatTurn> turns =
                raw.stream()
                        .map(this::deserialize)
                        .filter(java.util.Objects::nonNull)
                        .filter(turn -> !ChatTurn.isFailedReply(turn))
                        .toList();
        if (turns.isEmpty()) {
            return List.of();
        }

        // Walk from the tail (newest) accumulating content length and
        // estimated tokens; stop BEFORE including a turn that would push past
        // either budget. The newest turn is exempt from both checks (see
        // method javadoc).
        int start = turns.size(); // exclusive-from-the-left cursor; final window is [start, size)
        long chars = 0;
        long tokens = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            String content = turns.get(i).content();
            long len = content == null ? 0 : content.length();
            long tok = estimateTokens(content);
            boolean isNewest = i == turns.size() - 1;
            if (!isNewest
                    && (chars + len > config.maxChars() || tokens + tok > config.maxTokens())) {
                break;
            }
            chars += len;
            tokens += tok;
            start = i;
        }

        // Prefer a USER-turn boundary: if the window's oldest turn is an
        // orphaned assistant reply, drop it - unless it is the only turn
        // (never return an empty window for a non-empty session).
        if (turns.size() - start > 1 && turns.get(start).role() == MessageRole.ASSISTANT) {
            start++;
        }
        return folder.withSummary(sessionId, start == 0 ? turns : turns.subList(start, turns.size()));
    }

    /**
     * Cheap token ESTIMATE for one turn's content, used only to bound the replay window (never for
     * billing); see {@link HistoryTokenEstimator} for the rationale.
     */
    public static long estimateTokens(String content) {
        return HistoryTokenEstimator.estimateTokens(content);
    }

    /**
     * The old unbounded read — the complete stored transcript (itself capped at {@value
     * #MAX_STORED_ENTRIES} entries by the append-time LTRIM). Exists for genuine full-transcript
     * consumers (transcript UIs, exports); must NOT be used to build a model prompt — that's what
     * the bounded {@link #getHistory} window is for.
     */
    public List<ChatTurn> getFullHistory(String sessionId) {
        List<String> raw;
        try {
            raw = redisTemplate.opsForList().range(key(sessionId), 0, -1);
        } catch (RuntimeException e) {
            return historyUnavailable("full-read", e);
        }
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(this::deserialize).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * Time-travel rewind: truncates the stored transcript so the session continues as if only the
     * first {@code keepTurns} entries ever happened. The next user message then branches the
     * conversation from that point — the client resends (or edits) instead of the platform
     * re-running a turn, so nothing re-executes tool calls the original turn already fired.
     *
     * <p>Unlike turns that leave the replay window in {@link #appendTurn}, rewound turns are NOT
     * folded into the rolling summary: the summary feeds every future prompt, so folding content
     * the user just rewound away would resurrect exactly what they asked to forget. The summary is
     * dropped outright together with its "summarised-up-to" marker, and simply rebuilds from the
     * kept turns as they leave the window again.
     *
     * @return the number of entries actually kept (clamped to the current size); the caller reports
     *     {@code size - returned} as dropped.
     */
    public int rewind(String sessionId, int keepTurns) {
        String redisKey = key(sessionId);
        Long size = redisTemplate.opsForList().size(redisKey);
        long current = size == null ? 0 : size;
        int keep = (int) Math.max(0, Math.min(keepTurns, current));
        if (current <= keep) {
            return (int) current;
        }
        if (keep == 0) {
            // LTRIM cannot express "empty" - the negative-index range 0..-1 is
            // the full list. Delete outright instead.
            redisTemplate.delete(redisKey);
            redisTemplate.delete(ownerKey(sessionId));
        } else {
            redisTemplate.opsForList().trim(redisKey, 0, keep - 1);
        }
        redisTemplate.delete(summaryKey(sessionId));
        redisTemplate.delete(foldMarkerKey(sessionId));
        return keep;
    }

    /**
     * Replaces the whole stored transcript with {@code turns} (oldest first) and drops the rolling
     * summary and its fold marker, so the session replays exactly that list from now on. Used by
     * chat branching to point the live transcript at another path of a conversation tree: edit,
     * regenerate and branch-switch all end up here, which is why every history consumer (prompt
     * replay, summary, caches) sees only the active branch without knowing branches exist.
     *
     * <p>The summary is dropped for the same reason {@link #rewind} drops it: it was folded from
     * turns that may belong to the branch being left, and would otherwise leak them into the new
     * path. Only the newest {@value #MAX_STORED_ENTRIES} turns are kept. Not called on any normal
     * chat path.
     */
    public void replaceHistory(String sessionId, List<ChatTurn> turns) {
        String redisKey = key(sessionId);
        redisTemplate.delete(redisKey);
        redisTemplate.delete(summaryKey(sessionId));
        redisTemplate.delete(foldMarkerKey(sessionId));
        if (turns == null || turns.isEmpty()) {
            return;
        }
        int from = Math.max(0, turns.size() - MAX_STORED_ENTRIES);
        List<String> serialized = new ArrayList<>(turns.size() - from);
        for (ChatTurn turn : turns.subList(from, turns.size())) {
            serialized.add(serialize(turn));
        }
        redisTemplate.opsForList().rightPushAll(redisKey, serialized);
        redisTemplate.expire(redisKey, sessionTtl);
    }

    /**
     * Appends one turn, folds any batch of turns that has left the replay window into the rolling
     * summary, then applies the storage bound.
     *
     * <p>This is the only place summarisation is triggered from (see the class javadoc for why the
     * append path and not the read path). Ordering matters: the fold claim runs BEFORE the storage
     * trim, so a turn is always claimed for folding while it still exists.
     *
     * <p>Every branch is fail-open, in layers, because this method sits on the chat write path and
     * {@code AgentChatService} calls it with no try/catch - an exception escaping here costs the
     * user their turn (there is a regression test pinning what happens when Redis itself throws).
     * So: no summariser bean or feature switched off -> byte-for-byte the pre-feature
     * push/LTRIM/EXPIRE; no tenant context -> no fold (the marker is left where it was, so the
     * batch is claimed on the next user-turn append instead); Redis refusing the fold script -> no
     * fold; Redis refusing the marker-aware trim script -> the plain LTRIM; the LLM call failing
     * later on its background thread -> the summary is left as it was. Outermost layer: any Redis
     * failure at all (an outage, a timeout) skips the append and logs once a minute instead of
     * throwing - history is lost for the outage window, the turn is not.
     */
    public void appendTurn(String sessionId, ChatTurn turn) {
        String json = serialize(turn);
        String redisKey = key(sessionId);
        try {
            if (!singleRoundTripAppend
                    || appendScriptRefusal.skipScript()
                    || !appendInOneRoundTrip(sessionId, redisKey, json)) {
                appendLegacy(sessionId, redisKey, json);
            }
        } catch (RuntimeException e) {
            // Redis unreachable (or refusing). The turn was already answered; losing its history
            // entry costs the conversation a little memory, not the turn. Skip the write and
            // report once a minute rather than throwing out of the chat path.
            REDIS_DOWN_LOG.warn(
                    "session-memory-append",
                    "Session history unavailable (Redis error: "
                            + e.getClass().getSimpleName()
                            + ") - skipping the history append; chat continues without it");
        }
    }

    /**
     * The multi-call append (RPUSH, trim, EXPIRE, owner): the pre-script behaviour, kept as the
     * escape hatch.
     */
    private void appendLegacy(String sessionId, String redisKey, String json) {
        redisTemplate.opsForList().rightPush(redisKey, json);
        SessionSummaryService summariser = folder.activeSummariser();
        if (summariser == null) {
            // Hard storage bound: keep only the newest MAX_STORED_ENTRIES. Cheap (O(entries
            // removed), usually
            // zero) and idempotent - see the constant's javadoc for why this is a fixed ceiling.
            redisTemplate.opsForList().trim(redisKey, -MAX_STORED_ENTRIES, -1);
        } else {
            folder.foldTurnsThatLeftTheWindow(sessionId, redisKey, summariser);
            folder.trimKeepingFoldMarkerAligned(sessionId, redisKey);
        }
        redisTemplate.expire(redisKey, sessionTtl);
        owners.bindOwner(sessionId);
    }

    /**
     * The same steps in the same order as {@link #appendLegacy}, as ONE script call (see {@link
     * SessionAppendScript}). Returns false when Redis REFUSED the script (scripting disabled, ACL)
     * so the caller falls back to the legacy sequence. Any other failure (timeout, connection, Lua
     * error) is rethrown, never replayed. On Redis Cluster its keys are cross-slot: always refused.
     */
    private boolean appendInOneRoundTrip(String sessionId, String redisKey, String json) {
        SessionSummaryService summariser = folder.activeSummariser();
        Optional<TenantContext> tenant = TenantContext.current();
        SessionAppendScript.Mode mode =
                summariser == null
                        ? SessionAppendScript.Mode.PLAIN_TRIM
                        : tenant.isEmpty()
                                ? SessionAppendScript.Mode.MARKER_AWARE_TRIM
                                : SessionAppendScript.Mode.CLAIM_AND_TRIM;
        boolean claiming = mode == SessionAppendScript.Mode.CLAIM_AND_TRIM;
        String owner = tenant.map(t -> SessionOwnerRecords.ownerValue(t.getTenantId(), t.getSubject())).orElse(null);
        List<Object> claimed;
        try {
            claimed =
                    SessionAppendScript.append(
                            redisTemplate,
                            redisKey,
                            foldMarkerKey(sessionId),
                            ownerKey(sessionId),
                            json,
                            MAX_STORED_ENTRIES,
                            sessionTtl.toSeconds(),
                            mode,
                            claiming ? folder.foldWindowTurns() : 0,
                            SUMMARY_FOLD_BATCH,
                            owner);
        } catch (RuntimeException e) {
            appendScriptRefusal.rethrowUnlessRefused(e);
            REDIS_DOWN_LOG.warn(
                    "session-append-script-refused",
                    "Redis refused the session append script - using the multi-call append for"
                            + " now");
            return false;
        }
        if (claiming) {
            folder.handClaimedBatchToSummariser(sessionId, summariser, tenant, claimed);
        }
        return true;
    }

    /**
     * The shared "Redis is down on the history read path" answer: no history, one WARN a minute.
     */
    private static List<ChatTurn> historyUnavailable(String op, RuntimeException e) {
        REDIS_DOWN_LOG.warn(
                "session-memory-" + op,
                "Session history unavailable (Redis error: "
                        + e.getClass().getSimpleName()
                        + ") - replaying an empty history until it recovers");
        return List.of();
    }

    static String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    static String summaryKey(String sessionId) {
        return SUMMARY_KEY_PREFIX + sessionId;
    }

    static String foldMarkerKey(String sessionId) {
        return FOLD_MARKER_KEY_PREFIX + sessionId;
    }

    static String ownerKey(String sessionId) {
        return OWNER_KEY_PREFIX + sessionId;
    }

    /**
     * True only when this session has a recorded owner and it is exactly the given tenant and
     * subject. A session with no record (started before owner records existed, expired record,
     * Redis trouble) answers false.
     */
    public boolean isOwnedBy(String sessionId, String tenantId, String subject) {
        return owners.isOwnedBy(sessionId, tenantId, subject);
    }

    private String serialize(ChatTurn turn) {
        try {
            return objectMapper.writeValueAsString(turn);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat turn", e);
        }
    }

    private ChatTurn deserialize(String json) {
        try {
            return objectMapper.readValue(json, ChatTurn.class);
        } catch (JsonProcessingException e) {
            // A corrupt/unreadable entry shouldn't take down the whole conversation history.
            log.warn("Skipping unreadable chat turn in session history", e);
            return null;
        }
    }

    // ---- SEC-004: server-derived, owner-scoped memory ids ------------------

    /**
     * Marker every scoped memory id starts with. Versioned so a later change to the derivation can
     * never alias an id minted under this one.
     */
    public static final String SCOPED_ID_PREFIX = "c1:";

    /**
     * SEC-004: the id every method of this class must be called with, a server-derived digest of
     * who owns the conversation (tenant, agent, principal, client session id), never the raw
     * client-chosen id. See {@link SessionScopedIds} for the full derivation notes.
     */
    public static String scopedSessionId(
            String tenantId, java.util.UUID agentId, String principal, String clientSessionId) {
        return SessionScopedIds.scopedSessionId(tenantId, agentId, principal, clientSessionId);
    }

    // ------------------------------------------------------------------
    // SEC-004 turn binding. ChatTurnExecutor#streamFast and
    // BufferedTurnRunner#planThenRespond keep their established signatures
    // (they are edges of a frozen ArchUnit package cycle), so the caller binds
    // the scoped memory id on the calling thread around the call and those
    // methods read it ONCE, synchronously, on entry.
    // ------------------------------------------------------------------

    /**
     * Binds {@code scopedId} on this thread; returns the previous binding for {@link
     * #restoreMemorySessionId}.
     */
    public static String bindMemorySessionId(String scopedId) {
        return SessionMemoryBinding.bindMemorySessionId(scopedId);
    }

    /** Restores the binding returned by {@link #bindMemorySessionId}. */
    public static void restoreMemorySessionId(String previous) {
        SessionMemoryBinding.restoreMemorySessionId(previous);
    }

    /**
     * The scoped memory id bound on this thread. Fails closed: there is no fallback to a raw,
     * client-chosen session id.
     */
    public static String boundMemorySessionId() {
        return SessionMemoryBinding.boundMemorySessionId();
    }
}
