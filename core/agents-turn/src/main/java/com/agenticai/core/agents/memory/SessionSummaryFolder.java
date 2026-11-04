package com.agenticai.core.agents.memory;

import com.agenticai.core.guardrails.taint.ConversationTaintStore;
import com.agenticai.core.guardrails.taint.Spotlight;
import com.agenticai.core.knowledge.MemoryWriteScreen;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.logging.LogSafe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The rolling-summary half of {@link SessionMemoryService}: replaying the summary ahead of the
 * window, claiming batches of turns that left the replay window and handing them to {@link
 * SessionSummaryService}, the marker-aware storage trim, and the summary key's reads and writes.
 * See the service's class javadoc for the design. Not a Spring bean - the service builds it - and
 * every method here is fail-open exactly as it was on the service.
 */
final class SessionSummaryFolder {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryService.class);

    /**
     * Sibling flag of the summary key: present (value {@code 1}) when the rolling summary was folded from a
     * tainted conversation or untrusted text. Own prefix, like the summary's, so a caller-supplied id cannot
     * address another session's flag.
     */
    static final String UNTRUSTED_KEY_PREFIX = "agentic:session-summary-untrusted:";

    static String untrustedKey(String sessionId) {
        return UNTRUSTED_KEY_PREFIX + sessionId;
    }

    private final StringRedisTemplate redisTemplate;
    private final Duration sessionTtl;

    /** Optional summariser; {@code getIfAvailable() == null} is the outermost fail-open switch. */
    private final ObjectProvider<SessionSummaryService> summaryService;

    private final HistoryWindowSettings windowSettings;
    private final Function<String, ChatTurn> deserializer;

    /** The storage bound and fold batch in force ({@code app.memory.limits.*}); read per call, so a rebind is seen. */
    private final java.util.function.Supplier<MemoryLimitsProperties> limits;

    /**
     * Where the conversation-level taint flag is read ({@code null} = none wired: taint mode off or a hand-built
     * service). Set by {@link SessionMemoryService#setConversationTaintStore}.
     */
    private volatile ConversationTaintStore taintStore;

    /** True = a summary written from untrusted input is skipped on replay; false (default) = replayed Spotlight-wrapped. */
    private volatile boolean skipUntrustedSummary;

    /** Drops the summary and its untrusted flag (rewind, branch switch). */
    void dropSummary(String sessionId) {
        redisTemplate.delete(SessionMemoryService.summaryKey(sessionId));
        redisTemplate.delete(untrustedKey(sessionId));
    }

    void setTaintStore(ConversationTaintStore store) {
        this.taintStore = store;
    }

    void setSkipUntrustedSummary(boolean skip) {
        this.skipUntrustedSummary = skip;
    }

    /** The line that opens the replayed summary (with its line break); the registry's, see the service. */
    private final java.util.function.Supplier<String> preamble;

    SessionSummaryFolder(
            StringRedisTemplate redisTemplate,
            Duration sessionTtl,
            ObjectProvider<SessionSummaryService> summaryService,
            HistoryWindowSettings windowSettings,
            Function<String, ChatTurn> deserializer,
            java.util.function.Supplier<MemoryLimitsProperties> limits,
            java.util.function.Supplier<String> preamble) {
        this.preamble = preamble;
        this.redisTemplate = redisTemplate;
        this.sessionTtl = sessionTtl;
        this.summaryService = summaryService;
        this.windowSettings = windowSettings;
        this.deserializer = deserializer;
        this.limits = limits;
    }

    /**
     * Prepends the rolling summary to the replay window by folding it into the CONTENT of the
     * window's oldest turn, rather than by adding a turn of its own.
     *
     * <p>That looks like the fussier option and is in fact the only safe one. A {@link ChatTurn}
     * can only be {@code USER} or {@code ASSISTANT}, and {@code AgentChatService#toSpringAiMessage}
     * maps it straight onto a {@code UserMessage}/{@code AssistantMessage}. An extra {@code USER}
     * turn at the head would put two consecutive user messages in front of a window that the
     * boundary rule above has just guaranteed starts on a user turn; an extra {@code ASSISTANT}
     * turn would make the conversation open with the assistant. Providers are picky about both in
     * ways that would surface as a hard 400 on the chat path — the exact opposite of what an
     * optional enhancement is allowed to cost. Merging into the existing first turn keeps the
     * message sequence byte-identical in shape to a session that never had a turn leave its window.
     *
     * <p>The other rejected option, for the record: putting the summary in the per-turn system
     * block instead. That is genuinely the tidier home for it, but it lives in {@code
     * AgentChatService}, and this window is the seam this class actually owns.
     *
     * <p>Read via {@code GETEX}, not {@code GET}: the summary must expire with the list it
     * summarises, never before it. The list refreshes its TTL on every append, but the summary is
     * only WRITTEN once per fold (~40 turns), so a plain read would let a busy session outlive its
     * own summary. {@code GETEX} refreshes and reads in a single round trip, so the read path pays
     * one Redis command for the whole feature.
     */
    List<ChatTurn> withSummary(String sessionId, List<ChatTurn> window) {
        String summary = readSummary(sessionId);
        if (summary == null || summary.isBlank() || window.isEmpty()) {
            return new ArrayList<>(window);
        }
        boolean untrusted = readSummaryUntrusted(sessionId);
        if (untrusted && skipUntrustedSummary) {
            return new ArrayList<>(window);
        }
        // A summary folded from a tainted conversation (or whose taint could not be read) is
        // attacker-influenced text replayed into every later turn: delimit it as data, exactly as
        // an untrusted tool result is.
        if (untrusted) {
            summary = Spotlight.wrap("session_summary", summary);
        }
        ChatTurn oldest = window.get(0);
        String content = oldest.content() == null ? "" : oldest.content();
        List<ChatTurn> merged = new ArrayList<>(window);
        merged.set(
                0,
                new ChatTurn(
                        oldest.role(),
                        preamble.get() + summary + "\n\n" + content,
                        oldest.timestamp(),
                        oldest.tools()));
        return merged;
    }

    /**
     * Fail-open read of the rolling summary. Any Redis-side problem here (unreachable, a value of
     * the wrong type, an older server without {@code GETEX}) must degrade to "this session has no
     * summary" — the window itself is still perfectly good context, and refusing to serve it
     * because an optional enhancement failed would turn a nice-to-have into an outage.
     */
    String readSummary(String sessionId) {
        try {
            return redisTemplate
                    .opsForValue()
                    .getAndExpire(SessionMemoryService.summaryKey(sessionId), sessionTtl);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not read the rolling summary for session {} - replaying the window"
                            + " without it",
                    LogSafe.clean(sessionId),
                    e);
            return null;
        }
    }

    /**
     * True when the stored summary was folded from untrusted input. Reads the sibling flag key (refreshing its TTL
     * with the summary's). Fails CLOSED: a flag that cannot be read counts as untrusted. A summary with no flag key
     * (written before the flag existed, or from clean input) is trusted.
     */
    boolean readSummaryUntrusted(String sessionId) {
        try {
            return redisTemplate
                            .opsForValue()
                            .getAndExpire(untrustedKey(sessionId), sessionTtl)
                    != null;
        } catch (RuntimeException e) {
            log.warn(
                    "Could not read the rolling-summary taint flag for session {} - treating the summary as untrusted",
                    LogSafe.clean(sessionId),
                    e);
            return true;
        }
    }

    /**
     * Whether a fold now must be treated as built from untrusted input: the conversation has had a tainted turn,
     * its flag cannot be read (fail closed), the summary being extended was already untrusted (the flag only
     * grows), or any turn being folded trips {@link MemoryWriteScreen}. No taint store wired = the conversation
     * taint feature is off, so only the previous flag and the screen count.
     */
    boolean foldInputUntrusted(
            String sessionId, Optional<TenantContext> tenant, List<ChatTurn> turns) {
        if (readSummaryUntrusted(sessionId)) {
            return true;
        }
        ConversationTaintStore store = taintStore;
        if (store != null) {
            try {
                String tenantId = tenant.map(TenantContext::getTenantId).orElse(null);
                if (tenantId == null || store.isTainted(tenantId, sessionId)) {
                    return true;
                }
            } catch (RuntimeException e) {
                log.warn(
                        "Conversation taint flag unreadable for session {} - the folded summary is marked untrusted",
                        LogSafe.clean(sessionId),
                        e);
                return true;
            }
        }
        return turns.stream()
                .anyMatch(t -> t.content() != null && MemoryWriteScreen.refusal(t.content()).isPresent());
    }

    /** The summariser bean when present AND enabled, else null - the outermost fail-open switch. */
    SessionSummaryService activeSummariser(String sessionId) {
        // memory.write.enabled off for this conversation's agent: no fold is claimed, so no summary is written (the
        // turns past the window are trimmed as when summarising is off).
        return SessionWriteSwitch.writesOff(sessionId) ? null : activeSummariser();
    }

    SessionSummaryService activeSummariser() {
        try {
            SessionSummaryService summariser =
                    summaryService == null ? null : summaryService.getIfAvailable();
            return summariser != null && summariser.isEnabled() ? summariser : null;
        } catch (RuntimeException e) {
            log.warn("Session summariser lookup failed - appending without summarisation", e);
            return null;
        }
    }

    /**
     * The fold window: how many newest stored turns count as "still replayed" for folding purposes
     * - the effective {@code history-max-turns}, capped at {@code
     * SessionMemoryService.MAX_STORED_ENTRIES - SessionMemoryService.SUMMARY_FOLD_BATCH}.
     *
     * <p>The cap is what guarantees the storage trim never discards an unfolded turn. A batch is
     * claimed as soon as {@value SessionMemoryService#SUMMARY_FOLD_BATCH} unfolded turns sit
     * outside the fold window, so at most {@code window + SessionMemoryService.SUMMARY_FOLD_BATCH -
     * 1} newest entries are ever unfolded; with the window capped as above that is always fewer
     * than the {@value SessionMemoryService#MAX_STORED_ENTRIES} the trim keeps. Without the cap, an
     * admin window of 400 would make the fold fire only after the trim had already thrown the turns
     * away. The price, for an admin window above the cap, is that the oldest few replayed turns may
     * also appear in the summary - a harmless overlap, not a hole.
     */
    int foldWindowTurns() {
        return Math.min(
                windowSettings.current().maxTurns(),
                SessionMemoryService.MAX_STORED_ENTRIES - SessionMemoryService.SUMMARY_FOLD_BATCH);
    }

    /**
     * Claims (atomically, via {@link SessionAppendScript#FOLD_CLAIM_SCRIPT}) the batch of turns
     * that has left the replay window since the last fold, and hands it to {@link
     * SessionSummaryService} together with the current summary.
     */
    @SuppressWarnings("unchecked")
    void foldTurnsThatLeftTheWindow(
            String sessionId, String redisKey, SessionSummaryService summariser) {
        // Captured HERE, on the caller's thread, and passed as data. appendTurn
        // is reached from the stream-completion thread on the assistant-turn
        // path, where the ambient TenantContext is already gone - the very
        // reason AgentChatService threads a snapshot into its own outbound
        // guardrail audit instead of reading the ThreadLocal there. An empty
        // snapshot means we skip WITHOUT claiming: a summariser resolving
        // credentials or a plan with no tenant would either throw or, far
        // worse, silently read the `public` schema (the 2026-08-10
        // document-generation outage). Not claiming leaves the marker where it
        // was, so the very next user-turn append claims the batch instead.
        Optional<TenantContext> tenant = TenantContext.current();
        if (tenant.isEmpty()) {
            return;
        }
        try {
            List<Object> raw =
                    redisTemplate.execute(
                            SessionAppendScript.FOLD_CLAIM_SCRIPT,
                            List.of(redisKey, SessionMemoryService.foldMarkerKey(sessionId)),
                            Integer.toString(foldWindowTurns()),
                            Integer.toString(limits.get().effectiveFoldBatch()),
                            Long.toString(sessionTtl.toSeconds()));
            handClaimedBatchToSummariser(sessionId, summariser, tenant, raw);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not claim the session-history summarisation fold for session {} - "
                            + "appending without summarising, exactly as before this feature",
                    LogSafe.clean(sessionId),
                    e);
        }
    }

    /**
     * Deserializes a claimed fold batch and starts the background fold. Never throws (fail-open,
     * like the claim).
     */
    void handClaimedBatchToSummariser(
            String sessionId,
            SessionSummaryService summariser,
            Optional<TenantContext> tenant,
            List<Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            List<ChatTurn> leaving =
                    raw.stream()
                            .map(o -> o == null ? null : deserializer.apply(o.toString()))
                            .filter(java.util.Objects::nonNull)
                            // An error the person was shown is not conversation content; keep it
                            // out of the summary too.
                            .filter(turn -> !ChatTurn.isFailedReply(turn))
                            .toList();
            if (leaving.isEmpty()) {
                return; // claimed but unreadable - nothing to summarise, marker still advanced past
                // it
            }
            // Never throws; the LLM call itself happens on a background thread
            // and the persist callback is invoked from there, which is why the
            // summary write lives in a lambda rather than after this line.
            boolean inputUntrusted = foldInputUntrusted(sessionId, tenant, leaving);
            summariser.foldAsync(
                    sessionId,
                    tenant,
                    readSummary(sessionId),
                    leaving,
                    folded -> writeSummary(sessionId, folded, inputUntrusted));
        } catch (RuntimeException e) {
            log.warn(
                    "Could not hand the claimed fold batch for session {} to the summariser",
                    LogSafe.clean(sessionId),
                    e);
        }
    }

    /**
     * The storage trim for sessions with a live summariser: {@link SessionAppendScript#TRIM_SCRIPT}
     * trims to {@value SessionMemoryService#MAX_STORED_ENTRIES} and shifts the fold marker in one
     * atomic step. If scripting fails, falls back to the plain LTRIM so the storage ceiling holds
     * regardless - the marker may then point a few turns too far, which can only cause a few turns
     * to be skipped by a later fold, never folded twice.
     */
    void trimKeepingFoldMarkerAligned(String sessionId, String redisKey) {
        try {
            redisTemplate.execute(
                    SessionAppendScript.TRIM_SCRIPT,
                    List.of(redisKey, SessionMemoryService.foldMarkerKey(sessionId)),
                    Integer.toString(limits.get().getSessionMaxStoredEntries()),
                    Long.toString(sessionTtl.toSeconds()));
        } catch (RuntimeException e) {
            log.warn(
                    "Marker-aware storage trim failed for session {} - falling back to the plain"
                            + " trim",
                    LogSafe.clean(sessionId),
                    e);
            redisTemplate.opsForList().trim(redisKey, -limits.get().getSessionMaxStoredEntries(), -1);
        }
    }

    /**
     * Writes the folded summary with the session TTL in one {@code SET ... EX}. Invoked from {@link
     * SessionSummaryService}'s background thread, so it is guarded on its own: by the time it runs,
     * the chat turn that triggered the fold is long finished, and a Redis failure at that point
     * must not surface as an unhandled exception on a thread nobody is watching.
     */
    void writeSummary(String sessionId, String summary) {
        writeSummary(sessionId, summary, false);
    }

    /**
     * As {@link #writeSummary(String, String)}, recording whether the summary was built from untrusted input. The
     * model's output is screened as well ({@link MemoryWriteScreen}): a summary that carries a directive or a
     * credential shape is stored but flagged. The flag key is written BEFORE the summary so a reader can never
     * see an untrusted summary without its flag; a clean fold removes the flag (its input already included the
     * previous flag, so a clean fold means the whole chain is clean).
     */
    void writeSummary(String sessionId, String summary, boolean inputUntrusted) {
        try {
            boolean untrusted =
                    inputUntrusted || MemoryWriteScreen.refusal(summary).isPresent();
            String flagKey = untrustedKey(sessionId);
            if (untrusted) {
                redisTemplate.opsForValue().set(flagKey, "1", sessionTtl);
            } else {
                redisTemplate.delete(flagKey);
            }
            redisTemplate
                    .opsForValue()
                    .set(SessionMemoryService.summaryKey(sessionId), summary, sessionTtl);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not store the rolling summary for session {} - the previous one (if any)"
                            + " stands",
                    LogSafe.clean(sessionId),
                    e);
        }
    }
}
