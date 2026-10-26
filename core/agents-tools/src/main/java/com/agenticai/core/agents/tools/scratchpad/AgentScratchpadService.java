package com.agenticai.core.agents.tools.scratchpad;

import java.nio.charset.StandardCharsets;
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

    public AgentScratchpadService(ScratchpadNoteRepository repository, ScratchpadProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Writes {@code body} under {@code key}, overwriting the existing note in the same scope if one
     * exists. {@code sessionId == null} writes an agent-long-term note.
     *
     * @return the note as persisted (a fresh id on insert, the pre-existing one on overwrite)
     * @throws IllegalStateException when {@code app.tools.scratchpad.enabled=false}
     * @throws IllegalArgumentException on a malformed key, null/oversized body, or a full scope
     */
    @Transactional
    public ScratchpadNote write(UUID agentId, UUID sessionId, String key, String body) {
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
            return repository.save(note);
        }
        if (repository.countByAgentIdAndSessionId(agentId, sessionId) >= properties.getMaxNotes()) {
            throw new IllegalArgumentException(
                    "Scratchpad already holds "
                            + properties.getMaxNotes()
                            + " notes for this agent/session scope (app.tools.scratchpad.max-notes)"
                            + " - delete one or rewrite an existing key");
        }
        return repository.save(new ScratchpadNote(agentId, sessionId, key, body, byteSize));
    }

    /** The note under {@code key} in the {@code (agentId, sessionId)} scope; null sessionId reads the agent-long-term note. */
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
