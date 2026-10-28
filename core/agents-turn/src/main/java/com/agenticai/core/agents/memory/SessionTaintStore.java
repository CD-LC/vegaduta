package com.agenticai.core.agents.memory;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.guardrails.taint.TaintMode;
import com.agenticai.core.guardrails.taint.TurnTaint;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.logging.LogSafe;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
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
 * Conversation-level taint ({@code app.agents.taint.session-persistence}). A turn's {@link
 * TurnTaint} dies with the turn, but the chat history is replayed on every later turn, so a reply
 * built from poisoned content would otherwise start the next turn clean. This store keeps a small
 * "session tainted" marker - the (bounded) sources, when the session first became tainted, when it
 * last did - next to the session's history and summary in Redis, and {@link #seedAndTrack} replays
 * it onto each later turn's {@code TurnContext} taint, which the tool gates already consult.
 *
 * <h2>Storage</h2>
 *
 * One key per session, {@code agentic:session-taint:<scoped session id>} (the SEC-004 scoped id,
 * so a marker is only reachable by the conversation's own owner), with the same TTL as the history
 * list and the rolling summary ({@code app.chat.session-ttl-hours}), refreshed whenever the marker
 * is read or written. The marker is keyed by SESSION, not by history window, so it also covers the
 * rolling summary: a summary is folded from turns of a session, and the marker outlives the folding
 * ({@link SessionMemoryService#rewind} deliberately leaves it in place - rewinding drops turns but
 * the platform cannot prove which turn carried the poison).
 *
 * <h2>Behaviour</h2>
 *
 * <ul>
 *   <li>Active only when the taint layer is on ({@code app.agents.taint.mode != off}) and {@code
 *       app.agents.taint.session-persistence} is true (the default).
 *   <li>Fail open: a Redis problem is logged and the turn proceeds with only its own taint; it can
 *       neither fail a turn nor fabricate a marker.
 *   <li>Explicit clear ({@link #clear}) is the only way to remove a marker; it is audited ({@code
 *       agent-taint}/{@code session_taint_cleared}). Clearing does NOT clean the history - it
 *       records a human decision that the conversation is trusted again.
 * </ul>
 */
@Component
public class SessionTaintStore {

    private static final Logger log = LoggerFactory.getLogger(SessionTaintStore.class);
    private static final ObjectMapper JSON = JsonMappers.plain();

    static final String KEY_PREFIX = "agentic:session-taint:";

    /** Bound on remembered source labels, same as {@code TurnTaint}. */
    static final int MAX_SOURCES = 8;

    /** The label a seeded turn carries, so the audit trail shows the taint came from the session. */
    public static final String SESSION_SOURCE = "session_tainted";

    /** What is stored for a tainted session. */
    public record Marker(List<String> sources, Instant firstTaintedAt, Instant lastTaintedAt) {}

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Duration ttl;
    private final ObjectProvider<AuditLogService> audit;

    public SessionTaintStore(
            StringRedisTemplate redis,
            @Value("${app.agents.taint.mode:observe}") String mode,
            @Value("${app.agents.taint.session-persistence:true}") boolean persistence,
            @Value("${app.chat.session-ttl-hours:24}") long sessionTtlHours,
            ObjectProvider<AuditLogService> audit) {
        this.redis = redis;
        this.enabled = persistence && TaintMode.parse(mode) != TaintMode.OFF;
        this.ttl = Duration.ofHours(Math.max(1, sessionTtlHours));
        this.audit = audit;
    }

    public boolean enabled() {
        return enabled;
    }

    static String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    /** The marker for this session, or empty (also when disabled or Redis is unreachable). */
    public Optional<Marker> load(String sessionId) {
        if (!enabled || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(key(sessionId));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            redis.expire(key(sessionId), ttl);
            return Optional.of(parse(raw));
        } catch (RuntimeException | java.io.IOException e) {
            log.debug("Could not read the taint marker of session {}", LogSafe.clean(sessionId), e);
            return Optional.empty();
        }
    }

    /**
     * Seeds {@code turn} from the session's marker (if any) and keeps the marker current for the
     * rest of the turn: every new taint source the turn records is merged into it. Call once per
     * turn, before the first mark the turn itself will make that you want persisted (marks made
     * earlier - an inbound channel label - are flushed here).
     */
    public void seedAndTrack(TurnTaint turn, String sessionId) {
        if (!enabled || turn == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        Optional<Marker> stored = load(sessionId);
        if (stored.isPresent()) {
            turn.mark(SESSION_SOURCE);
            for (String source : stored.get().sources()) {
                turn.mark(source);
            }
        }
        List<String> known = stored.map(Marker::sources).orElse(List.of());
        if (turn.isTainted() && !known.containsAll(persistable(turn.sources()))) {
            record(sessionId, turn.sources());
        }
        turn.onChange(sources -> record(sessionId, sources));
    }

    /** Merges {@code sources} into the session's marker; creates it on the first call. Never throws. */
    public void record(String sessionId, List<String> sources) {
        if (!enabled || sessionId == null || sessionId.isBlank()) {
            return;
        }
        List<String> fresh = persistable(sources);
        try {
            Instant now = Instant.now();
            Marker current = null;
            String raw = redis.opsForValue().get(key(sessionId));
            if (raw != null && !raw.isBlank()) {
                try {
                    current = parse(raw);
                } catch (java.io.IOException | RuntimeException e) {
                    log.debug("Replacing an unreadable taint marker", e);
                }
            }
            List<String> merged = new ArrayList<>(current == null ? List.of() : current.sources());
            for (String s : fresh) {
                if (merged.size() < MAX_SOURCES && !merged.contains(s)) {
                    merged.add(s);
                }
            }
            if (current != null && merged.equals(current.sources())) {
                redis.expire(key(sessionId), ttl);
                return;
            }
            if (merged.isEmpty()) {
                merged.add("unknown");
            }
            Marker next =
                    new Marker(merged, current == null ? now : current.firstTaintedAt(), now);
            com.fasterxml.jackson.databind.node.ObjectNode node = JSON.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode arr = node.putArray("sources");
            next.sources().forEach(arr::add);
            node.put("firstTaintedAt", next.firstTaintedAt().toString());
            node.put("lastTaintedAt", next.lastTaintedAt().toString());
            redis.opsForValue().set(key(sessionId), JSON.writeValueAsString(node), ttl);
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Could not persist the taint marker of session {}", LogSafe.clean(sessionId), e);
        }
    }

    /**
     * Removes the marker (the user, or an admin acting for them, decided the conversation is
     * trusted again) and records an audit entry whether or not one existed.
     *
     * @return true when a marker was removed
     */
    public boolean clear(String sessionId, String actor, String auditTargetId) {
        boolean removed = false;
        try {
            removed = Boolean.TRUE.equals(redis.delete(key(sessionId)));
        } catch (RuntimeException e) {
            log.warn("Could not clear the taint marker of session {}", LogSafe.clean(sessionId), e);
        }
        AuditLogService service = audit.getIfAvailable();
        if (service != null) {
            try {
                service.record(
                        actor == null || actor.isBlank() ? "unknown" : actor,
                        "session_taint_cleared",
                        "chat_session",
                        auditTargetId,
                        AuditOutcome.SUCCESS,
                        removed ? "marker removed" : "no marker present");
            } catch (RuntimeException e) {
                log.warn("Could not audit a session taint clear", e);
            }
        }
        return removed;
    }

    /** Sources worth persisting: the seed label is derived, not a cause. */
    private static List<String> persistable(List<String> sources) {
        List<String> out = new ArrayList<>(sources.size());
        for (String s : sources) {
            if (!SESSION_SOURCE.equals(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static Marker parse(String raw) throws java.io.IOException {
        com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(raw);
        List<String> sources = new ArrayList<>();
        node.path("sources").forEach(n -> sources.add(n.asText()));
        return new Marker(
                List.copyOf(sources),
                Instant.parse(node.path("firstTaintedAt").asText()),
                Instant.parse(node.path("lastTaintedAt").asText()));
    }
}
