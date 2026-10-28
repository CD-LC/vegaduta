package com.agenticai.core.agents.tools.scratchpad;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD for {@link ScratchpadNote} - the storage half of the agent scratchpad, a persistent
 * agent-authored notepad scoped per {@code (agentId, sessionId)} where a {@code null} sessionId is
 * an agent-long-term note.
 *
 * <p>All bounds are enforced here, not in the callback layer and not in DDL, so they stay
 * configuration ({@link ScratchpadProperties}) and every caller - tool callback, future REST
 * surface - gets identical answers:
 *
 * <ul>
 *   <li>key must match {@code ^[a-zA-Z0-9_.-]{1,64}$} - the shape every tool-facing identifier in
 *       this codebase already uses;
 *   <li>body may not exceed {@code app.tools.scratchpad.max-note-bytes} UTF-8 bytes;
 *   <li>a {@code (agentId, sessionId)} scope may hold at most {@code
 *       app.tools.scratchpad.max-notes} notes - the cap gates inserts only, so an agent at the cap
 *       can still rewrite the notes it already has.
 * </ul>
 *
 * <p>{@code write} is an upsert keyed on {@code (agent_id, session_id, key)}: the unique index is a
 * backstop, the {@link ScratchpadNoteRepository#findByAgentIdAndSessionIdAndKey} lookup is the
 * primary path (same posture {@code CustomCodeToolService} takes with its name uniqueness).
 */
@Service
public class AgentScratchpadService {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]{1,64}$");

    private final ScratchpadNoteRepository repository;
    private final ScratchpadProperties properties;

    public AgentScratchpadService(
            ScratchpadNoteRepository repository, ScratchpadProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * What one {@link #write} did beyond persisting the note: {@code evictedKey} is the
     * least-recently-updated note the full scope dropped to make room ({@code null} when nothing
     * was evicted), {@code expiredRemoved} how many notes the TTL sweep cleared first.
     */
    public record WriteResult(ScratchpadNote note, String evictedKey, int expiredRemoved) {}

    /**
     * Writes {@code body} under {@code key}, overwriting the existing note in the same scope if one
     * exists. {@code sessionId == null} writes an agent-long-term note.
     *
     * <p>Before the write the scope's TTL sweep runs (session notes age out after {@code
     * session-note-ttl}, agent-long-term notes after {@code agent-note-ttl}, {@code 0} = never), so
     * a dead session's notes cannot pin the cap forever. A NEW key into a scope still at {@code
     * max-notes} then evicts the least-recently-updated note when {@code evict-oldest-on-cap} is on
     * (the model is told via {@code evicted_key} on the tool result, so it can re-persist anything
     * it still needs under that key); with the flag off the write is refused as before.
     *
     * @return the note as persisted plus the eviction detail (a fresh id on insert, the
     *     pre-existing one on overwrite)
     * @throws IllegalStateException when {@code app.tools.scratchpad.enabled=false}
     * @throws IllegalArgumentException on a malformed key, null/oversized body, or a full scope
     *     with eviction off
     */
    @Transactional
    public WriteResult write(UUID agentId, UUID sessionId, String key, String body) {
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        validateKey(key);
        if (body == null) {
            throw new IllegalArgumentException("Scratchpad note body must not be null");
        }
        int byteSize = body.getBytes(StandardCharsets.UTF_8).length;
        if (byteSize > properties.getMaxNoteBytes()) {
            throw new IllegalArgumentException(
                    "Scratchpad note body is "
                            + byteSize
                            + " bytes, over the "
                            + properties.getMaxNoteBytes()
                            + "-byte limit (app.tools.scratchpad.max-note-bytes)");
        }
        Optional<ScratchpadNote> existing =
                repository.findByAgentIdAndSessionIdAndKey(agentId, sessionId, key);
        if (existing.isPresent()) {
            ScratchpadNote note = existing.get();
            note.update(body, byteSize);
            return new WriteResult(repository.save(note), null, 0);
        }
        int expiredRemoved = sweepExpired(agentId, sessionId);
        String evictedKey = null;
        if (repository.countByAgentIdAndSessionId(agentId, sessionId) >= properties.getMaxNotes()) {
            if (!properties.isEvictOldestOnCap()) {
                throw new IllegalArgumentException(
                        "Scratchpad already holds "
                                + properties.getMaxNotes()
                                + " notes for this agent/session scope (app.tools.scratchpad.max-notes)"
                                + " - delete one or rewrite an existing key");
            }
            ScratchpadNote oldest =
                    repository
                            .findFirstByAgentIdAndSessionIdOrderByUpdatedAtAsc(agentId, sessionId)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Scratchpad scope reports "
                                                            + properties.getMaxNotes()
                                                            + " notes but has none to evict"));
            evictedKey = oldest.getKey();
            repository.delete(oldest);
        }
        ScratchpadNote saved =
                repository.save(new ScratchpadNote(agentId, sessionId, key, body, byteSize));
        return new WriteResult(saved, evictedKey, expiredRemoved);
    }

    /**
     * The scope's lazy TTL sweep; returns how many expired notes were removed (0 = TTL off or none
     * stale).
     */
    private int sweepExpired(UUID agentId, UUID sessionId) {
        java.time.Duration ttl =
                sessionId == null ? properties.getAgentNoteTtl() : properties.getSessionNoteTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return 0;
        }
        long removed =
                repository.deleteByAgentIdAndSessionIdAndUpdatedAtBefore(
                        agentId, sessionId, Instant.now().minus(ttl));
        return (int) Math.min(removed, Integer.MAX_VALUE);
    }

    /**
     * The note under {@code key} in the {@code (agentId, sessionId)} scope; null sessionId reads
     * the agent-long-term note.
     */
    @Transactional(readOnly = true)
    public Optional<ScratchpadNote> read(UUID agentId, UUID sessionId, String key) {
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        validateKey(key);
        return repository.findByAgentIdAndSessionIdAndKey(agentId, sessionId, key);
    }

    /**
     * Every note the agent can see from {@code sessionId} - the session's own notes AND its
     * agent-long-term ones - newest-updated first. A null {@code sessionId} lists just the
     * long-term notes.
     */
    @Transactional(readOnly = true)
    public List<ScratchpadNote> list(UUID agentId, UUID sessionId) {
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        return repository.findVisible(agentId, sessionId);
    }

    /**
     * {@link #list} as operator-facing views (no entity, no bodies) - the read-only admin surface's
     * list shape.
     */
    @Transactional(readOnly = true)
    public List<ScratchpadNoteView> listViews(UUID agentId, UUID sessionId) {
        return list(agentId, sessionId).stream()
                .map(note -> ScratchpadNoteView.of(note, false))
                .toList();
    }

    /**
     * {@link #read} as an operator-facing view carrying the body - the read-only admin surface's
     * single-note shape.
     */
    @Transactional(readOnly = true)
    public Optional<ScratchpadNoteView> readView(UUID agentId, UUID sessionId, String key) {
        return read(agentId, sessionId, key).map(note -> ScratchpadNoteView.of(note, true));
    }

    /** Removes the note under {@code key} in the scope; true when one existed. */
    @Transactional
    public boolean delete(UUID agentId, UUID sessionId, String key) {
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        validateKey(key);
        return repository.deleteByAgentIdAndSessionIdAndKey(agentId, sessionId, key) > 0;
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "Agent scratchpad is not enabled on this deployment (app.tools.scratchpad.enabled=false)");
        }
    }

    private void validateKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Scratchpad key must match ^[a-zA-Z0-9_.-]{1,64}$ (letters, digits, '_', '.', '-'; 1-64 chars), got: "
                            + key);
        }
    }
}
