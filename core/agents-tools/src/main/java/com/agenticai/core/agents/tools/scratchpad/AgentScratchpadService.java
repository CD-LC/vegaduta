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
 * <p><b>Whose notes.</b> A session-pad note belongs to the session (whose id is already derived per
 * caller). An agent-pad note belongs to the caller's {@code subjectKey} (V680): the model's read,
 * list, write and delete only reach the caller's own agent-pad notes, the cap and eviction are per
 * caller, and a turn with no identified caller cannot use the agent pad at all. Rows that predate the
 * column have no owner; they are never returned to the model (operators still see them).
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
        return write(agentId, sessionId, null, key, body, NoteTrust.TRUSTED);
    }

    /**
     * {@link #write(UUID, UUID, String, String)} with the provenance the turn's taint decided and no
     * caller identity: only a session-pad write can succeed (an agent-pad write needs a subject, see
     * {@link #write(UUID, UUID, String, String, String, NoteTrust)}).
     */
    @Transactional
    public WriteResult write(
            UUID agentId, UUID sessionId, String key, String body, NoteTrust provenance) {
        return write(agentId, sessionId, null, key, body, provenance);
    }

    /**
     * Writes on behalf of {@code subjectKey}, the caller of the turn. A QUARANTINED note is stored but
     * hidden from {@link #read} and {@link #list} until a person approves it. Two things a quarantined
     * write may not do, because both would let a tainted turn damage what was trusted: overwrite a
     * TRUSTED note (the old text would be destroyed or silently replaced by attacker text), or evict a
     * note to make room.
     *
     * @throws IllegalArgumentException as {@link #write(UUID, UUID, String, String)}, plus the two
     *     refusals above, an agent-pad write with no {@code subjectKey}, and a write onto a session
     *     note another subject owns
     */
    @Transactional
    public WriteResult write(
            UUID agentId,
            UUID sessionId,
            String subjectKey,
            String key,
            String body,
            NoteTrust provenance) {
        NoteTrust prov = provenance == null ? NoteTrust.TRUSTED : provenance;
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        String subject = normalize(subjectKey);
        validateKey(key);
        if (sessionId == null && subject == null) {
            throw new IllegalArgumentException(
                    "The agent pad is kept per user and this turn has no identified user - use the session pad");
        }
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
        Optional<ScratchpadNote> existing = lookup(agentId, sessionId, subject, key);
        if (existing.isPresent()) {
            ScratchpadNote note = existing.get();
            if (!ownedBy(note, subject)) {
                throw new IllegalArgumentException(
                        "Scratchpad note '" + key + "' belongs to another user and cannot be written from here");
            }
            if (prov.quarantined() && !note.isQuarantined()) {
                throw new IllegalArgumentException(
                        "Scratchpad note '"
                                + key
                                + "' is trusted and this turn handled untrusted content, so it cannot be"
                                + " overwritten from here - save the new text under a different key (it is"
                                + " held for review) or ask the user");
            }
            note.update(body, byteSize, prov);
            note.claim(subject);
            return new WriteResult(repository.save(note), null, 0);
        }
        int expiredRemoved = sweepExpired(agentId, sessionId);
        String evictedKey = null;
        if (count(agentId, sessionId, subject) >= properties.getMaxNotes()) {
            if (prov.quarantined() || !properties.isEvictOldestOnCap()) {
                throw new IllegalArgumentException(
                        "Scratchpad already holds "
                                + properties.getMaxNotes()
                                + " notes for this agent/session scope (app.tools.scratchpad.max-notes)"
                                + " - delete one or rewrite an existing key");
            }
            ScratchpadNote oldest =
                    oldest(agentId, sessionId, subject)
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
                repository.save(
                        new ScratchpadNote(agentId, sessionId, subject, key, body, byteSize, prov));
        return new WriteResult(saved, evictedKey, expiredRemoved);
    }

    private static String normalize(String subjectKey) {
        return subjectKey == null || subjectKey.isBlank() ? null : subjectKey;
    }

    /** The note in the caller's scope: the session's, or the caller's own agent-pad note. */
    private Optional<ScratchpadNote> lookup(UUID agentId, UUID sessionId, String subject, String key) {
        if (sessionId != null) {
            return repository.findByAgentIdAndSessionIdAndKey(agentId, sessionId, key);
        }
        return subject == null
                ? Optional.empty()
                : repository.findByAgentIdAndSessionIdIsNullAndSubjectKeyAndKey(agentId, subject, key);
    }

    private long count(UUID agentId, UUID sessionId, String subject) {
        return sessionId != null
                ? repository.countByAgentIdAndSessionId(agentId, sessionId)
                : repository.countByAgentIdAndSessionIdIsNullAndSubjectKey(agentId, subject);
    }

    private Optional<ScratchpadNote> oldest(UUID agentId, UUID sessionId, String subject) {
        return sessionId != null
                ? repository.findFirstByAgentIdAndSessionIdOrderByUpdatedAtAsc(agentId, sessionId)
                : repository.findFirstByAgentIdAndSessionIdIsNullAndSubjectKeyOrderByUpdatedAtAsc(
                        agentId, subject);
    }

    /** Session notes: the caller's or nobody's (legacy). Agent-pad notes: only the caller's. */
    private static boolean ownedBy(ScratchpadNote note, String subject) {
        String owner = note.getSubjectKey();
        if (note.getSessionId() == null) {
            return subject != null && subject.equals(owner);
        }
        return owner == null || owner.equals(subject);
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
     * {@link #read(UUID, UUID, String, String)} with no caller identity: reaches session-pad notes only.
     */
    @Transactional(readOnly = true)
    public Optional<ScratchpadNote> read(UUID agentId, UUID sessionId, String key) {
        return read(agentId, sessionId, null, key);
    }

    /**
     * The note under {@code key} in the {@code (agentId, sessionId)} scope as {@code subjectKey} sees
     * it; a null sessionId reads the caller's own agent-pad note.
     */
    @Transactional(readOnly = true)
    public Optional<ScratchpadNote> read(
            UUID agentId, UUID sessionId, String subjectKey, String key) {
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        validateKey(key);
        String subject = normalize(subjectKey);
        // A note held for review is invisible here: this is the model's read path.
        return lookup(agentId, sessionId, subject, key)
                .filter(note -> !note.isQuarantined())
                .filter(note -> ownedBy(note, subject));
    }

    /** {@link #list(UUID, UUID, String)} with no caller identity: session-pad notes only. */
    @Transactional(readOnly = true)
    public List<ScratchpadNote> list(UUID agentId, UUID sessionId) {
        return list(agentId, sessionId, null);
    }

    /**
     * Every note the caller can see from {@code sessionId} - the session's own notes AND the caller's
     * agent-pad ones - newest-updated first. A null {@code sessionId} lists just the agent pad.
     */
    @Transactional(readOnly = true)
    public List<ScratchpadNote> list(UUID agentId, UUID sessionId, String subjectKey) {
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        String subject = normalize(subjectKey);
        return repository.findVisibleTo(agentId, sessionId, subject == null ? "" : subject);
    }

    /**
     * Operator-facing views of the pad, every owner (no entity, no bodies) - the read-only admin
     * surface's list shape.
     */
    @Transactional(readOnly = true)
    public List<ScratchpadNoteView> listViews(UUID agentId, UUID sessionId) {
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        return repository.findVisible(agentId, sessionId).stream()
                .map(note -> ScratchpadNoteView.of(note, false))
                .toList();
    }

    /**
     * One note as an operator sees it, with its body. For the agent pad, {@code subjectKey} picks the
     * owner; without it the key must be unique across owners.
     *
     * @throws IllegalArgumentException on a malformed key or an ambiguous agent-pad key
     */
    @Transactional(readOnly = true)
    public Optional<ScratchpadNoteView> readView(
            UUID agentId, UUID sessionId, String key, String subjectKey) {
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        validateKey(key);
        return candidates(agentId, sessionId, key, normalize(subjectKey), false).stream()
                .findFirst()
                .filter(note -> !note.isQuarantined())
                .map(note -> ScratchpadNoteView.of(note, true));
    }

    /** Removes the caller's note under {@code key} in the scope; true when one existed. */
    @Transactional
    public boolean delete(UUID agentId, UUID sessionId, String subjectKey, String key) {
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        validateKey(key);
        String subject = normalize(subjectKey);
        Optional<ScratchpadNote> found =
                lookup(agentId, sessionId, subject, key).filter(note -> ownedBy(note, subject));
        found.ifPresent(repository::delete);
        return found.isPresent();
    }

    /** {@link #delete(UUID, UUID, String, String)} with no caller identity: session-pad notes only. */
    @Transactional
    public boolean delete(UUID agentId, UUID sessionId, String key) {
        return delete(agentId, sessionId, null, key);
    }

    /**
     * Data-subject erasure: deletes every note, on either pad of any agent, that {@code subjectKey} owns.
     * Works with the scratchpad switched off (data written while it was on is still personal data).
     */
    @Transactional
    public int eraseSubject(String subjectKey) {
        String subject = normalize(subjectKey);
        if (subject == null) {
            return 0;
        }
        return (int) Math.min(repository.deleteBySubjectKey(subject), Integer.MAX_VALUE);
    }

    /**
     * The notes an operator action addresses: the session's note, or the agent-pad notes under the key
     * (narrowed to {@code subject} when given). More than one match is ambiguous.
     */
    private List<ScratchpadNote> candidates(
            UUID agentId, UUID sessionId, String key, String subject, boolean onlyQuarantined) {
        List<ScratchpadNote> found =
                sessionId != null
                        ? repository
                                .findByAgentIdAndSessionIdAndKey(agentId, sessionId, key)
                                .map(List::of)
                                .orElse(List.of())
                        : repository.findByAgentIdAndSessionIdIsNullAndKey(agentId, key);
        List<ScratchpadNote> out =
                found.stream()
                        .filter(n -> subject == null || subject.equals(n.getSubjectKey()))
                        .filter(n -> !onlyQuarantined || n.isQuarantined())
                        .toList();
        if (out.size() > 1) {
            throw new IllegalArgumentException(
                    "More than one user has a note '" + key + "' on the agent pad - pass subject to pick one");
        }
        return out;
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

    /** Notes of this agent held for review (any pad), newest first. */
    @Transactional(readOnly = true)
    public List<QuarantinedNoteView> listQuarantined(UUID agentId) {
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        return repository.findQuarantined(agentId).stream().map(QuarantinedNoteView::of).toList();
    }

    /**
     * Makes a held note readable. False when there is no held note under that key in the scope. For the
     * agent pad {@code subjectKey} picks the owner (optional while only one owner has a held note under
     * the key).
     */
    @Transactional
    public boolean approve(UUID agentId, UUID sessionId, String key, String subjectKey) {
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        validateKey(key);
        Optional<ScratchpadNote> held =
                candidates(agentId, sessionId, key, normalize(subjectKey), true).stream().findFirst();
        held.ifPresent(
                note -> {
                    note.approve();
                    repository.save(note);
                });
        return held.isPresent();
    }

    /** {@link #approve(UUID, UUID, String, String)} without an owner. */
    @Transactional
    public boolean approve(UUID agentId, UUID sessionId, String key) {
        return approve(agentId, sessionId, key, null);
    }

    /** Deletes a held note. Never touches a trusted one. */
    @Transactional
    public boolean reject(UUID agentId, UUID sessionId, String key, String subjectKey) {
        requireEnabled();
        Objects.requireNonNull(agentId, "agentId must not be null");
        validateKey(key);
        Optional<ScratchpadNote> held =
                candidates(agentId, sessionId, key, normalize(subjectKey), true).stream().findFirst();
        held.ifPresent(repository::delete);
        return held.isPresent();
    }

    /** {@link #reject(UUID, UUID, String, String)} without an owner. */
    @Transactional
    public boolean reject(UUID agentId, UUID sessionId, String key) {
        return reject(agentId, sessionId, key, null);
    }
}
