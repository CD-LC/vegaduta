package com.agenticai.core.agents.tools.scratchpad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit 5 + Mockito, no Spring context - same posture as {@code CustomCodeToolServiceTest}.
 * The repository is a mock; what is under test is this service's own rules (key shape, the UTF-8
 * byte cap, the per-scope note cap, upsert semantics, and the null-session long-term scope).
 */
class AgentScratchpadServiceTest {

    private ScratchpadNoteRepository repository;
    private ScratchpadProperties properties;
    private AgentScratchpadService service;

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(ScratchpadNoteRepository.class);
        properties = new ScratchpadProperties();
        service = new AgentScratchpadService(repository, properties);

        // Repository.save is identity by default - these tests assert on the
        // entity the service built, not on JPA.
        when(repository.save(any(ScratchpadNote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByAgentIdAndSessionIdAndKey(any(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.countByAgentIdAndSessionId(any(), any())).thenReturn(0L);
    }

    // ------------------------------------------------------------------ key

    @Test
    void acceptsWellFormedKeys() {
        for (String key : List.of("a", "todo", "my-note.v2", "A1_Z-", "x".repeat(64))) {
            ScratchpadNote note = service.write(AGENT, SESSION, key, "body").note();
            assertThat(note.getKey()).isEqualTo(key);
        }
    }

    @Test
    void rejectsMalformedKeys() {
        // Empty, over 64 chars, space, slash, quote, non-ASCII, and null -
        // every shape the pattern is meant to exclude.
        for (String key :
                new String[] {"", "x".repeat(65), "my key", "a/b", "a'b", "caf\u00e9", null}) {
            assertThatThrownBy(() -> service.write(AGENT, SESSION, key, "body"))
                    .as("key %s", key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Scratchpad key must match");
        }
        verify(repository, never()).save(any());
    }

    @Test
    void readAndDeleteValidateKeyToo() {
        assertThatThrownBy(() -> service.read(AGENT, SESSION, "bad key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete(AGENT, SESSION, "bad key"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------- body

    @Test
    void rejectsNullBody() {
        assertThatThrownBy(() -> service.write(AGENT, SESSION, "k", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void rejectsOversizedBody() {
        properties.setMaxNoteBytes(8);
        assertThatThrownBy(() -> service.write(AGENT, SESSION, "k", "123456789"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9 bytes")
                .hasMessageContaining("8-byte limit");
    }

    @Test
    void byteCapCountsUtf8BytesNotChars() {
        // "é" is one char but two UTF-8 bytes: a 4-char string over a 5-byte cap.
        properties.setMaxNoteBytes(5);
        assertThatThrownBy(() -> service.write(AGENT, SESSION, "k", "\u00e9\u00e9\u00e9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("6 bytes");
    }

    @Test
    void storesComputedByteSize() {
        ScratchpadNote note = service.write(AGENT, SESSION, "k", "\u00e9\u00e9\u00e9").note();
        assertThat(note.getByteSize())
                .isEqualTo("\u00e9\u00e9\u00e9".getBytes(StandardCharsets.UTF_8).length);
    }

    // --------------------------------------------------------------- upsert

    @Test
    void writeCreatesNewNoteInScope() {
        ScratchpadNote note = service.write(AGENT, SESSION, "k", "body").note();
        assertThat(note.getAgentId()).isEqualTo(AGENT);
        assertThat(note.getSessionId()).isEqualTo(SESSION);
        assertThat(note.getBody()).isEqualTo("body");
        assertThat(note.getCreatedAt()).isNotNull();
        assertThat(note.getUpdatedAt()).isNotNull();
        verify(repository).save(note);
    }

    @Test
    void writeWithNullSessionIsAnAgentLongTermNote() {
        ScratchpadNote note = service.write(AGENT, null, "k", "body").note();
        assertThat(note.getSessionId()).isNull();
        verify(repository).findByAgentIdAndSessionIdAndKey(eq(AGENT), isNull(), eq("k"));
        verify(repository).countByAgentIdAndSessionId(eq(AGENT), isNull());
    }

    @Test
    void writeOverwritesExistingNoteKeepingIdentity() {
        ScratchpadNote existing = new ScratchpadNote(AGENT, SESSION, "k", "old", 3);
        when(repository.findByAgentIdAndSessionIdAndKey(AGENT, SESSION, "k"))
                .thenReturn(Optional.of(existing));

        ScratchpadNote result = service.write(AGENT, SESSION, "k", "new body").note();

        assertThat(result).isSameAs(existing);
        assertThat(result.getBody()).isEqualTo("new body");
        assertThat(result.getByteSize()).isEqualTo(8);
        verify(repository).save(existing);
    }

    @Test
    void readPassesThroughScopeAndKey() {
        ScratchpadNote note = new ScratchpadNote(AGENT, null, "k", "b", 1);
        when(repository.findByAgentIdAndSessionIdAndKey(AGENT, null, "k"))
                .thenReturn(Optional.of(note));
        assertThat(service.read(AGENT, null, "k")).containsSame(note);
        assertThat(service.read(AGENT, null, "missing")).isEmpty();
    }

    // ------------------------------------------------------------------ cap

    @Test
    void rejectsInsertOverMaxNotesWhenEvictionOff() {
        properties.setMaxNotes(3);
        properties.setEvictOldestOnCap(false);
        when(repository.countByAgentIdAndSessionId(AGENT, SESSION)).thenReturn(3L);
        assertThatThrownBy(() -> service.write(AGENT, SESSION, "k", "body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 notes")
                .hasMessageContaining("max-notes");
        verify(repository, never()).save(any());
    }

    @Test
    void insertOverMaxNotesEvictsLeastRecentlyUpdated() {
        // Default evict-oldest-on-cap: a full pad drops its stalest note so the
        // write lands, and the result names the evicted key for the caller.
        properties.setMaxNotes(2);
        ScratchpadNote stale = new ScratchpadNote(AGENT, SESSION, "old", "b", 1);
        when(repository.countByAgentIdAndSessionId(AGENT, SESSION)).thenReturn(2L);
        when(repository.findFirstByAgentIdAndSessionIdOrderByUpdatedAtAsc(AGENT, SESSION))
                .thenReturn(Optional.of(stale));

        AgentScratchpadService.WriteResult result = service.write(AGENT, SESSION, "new", "body");

        assertThat(result.note().getKey()).isEqualTo("new");
        assertThat(result.evictedKey()).isEqualTo("old");
        verify(repository).delete(stale);
        verify(repository).save(result.note());
    }

    @Test
    void rewriteAtCapDoesNotEvict() {
        // The upsert path returns before the cap check - an agent at the cap can
        // still rewrite the notes it already has, nothing is evicted.
        properties.setMaxNotes(1);
        ScratchpadNote existing = new ScratchpadNote(AGENT, SESSION, "k", "old", 3);
        when(repository.findByAgentIdAndSessionIdAndKey(AGENT, SESSION, "k"))
                .thenReturn(Optional.of(existing));
        when(repository.countByAgentIdAndSessionId(AGENT, SESSION)).thenReturn(1L);

        AgentScratchpadService.WriteResult result = service.write(AGENT, SESSION, "k", "new");

        assertThat(result.evictedKey()).isNull();
        verify(repository, never()).findFirstByAgentIdAndSessionIdOrderByUpdatedAtAsc(any(), any());
        verify(repository, never()).delete(any(ScratchpadNote.class));
    }

    @Test
    void sessionScopeSweepsExpiredNotesOnWrite() {
        // Default session-note-ttl is 30d: the sweep asks the repository to drop
        // anything older than the cutoff before the insert.
        when(repository.deleteByAgentIdAndSessionIdAndUpdatedAtBefore(
                        eq(AGENT), eq(SESSION), any()))
                .thenReturn(2L);

        AgentScratchpadService.WriteResult result = service.write(AGENT, SESSION, "k", "body");

        assertThat(result.expiredRemoved()).isEqualTo(2);
        verify(repository)
                .deleteByAgentIdAndSessionIdAndUpdatedAtBefore(eq(AGENT), eq(SESSION), any());
    }

    @Test
    void agentScopeDoesNotSweepWithDefaultTtl() {
        // agent-note-ttl defaults to 0 (never): no sweep runs on the long-term pad.
        service.write(AGENT, null, "k", "body");

        verify(repository, never())
                .deleteByAgentIdAndSessionIdAndUpdatedAtBefore(any(), any(), any());
    }

    @Test
    void agentScopeSweepsWhenTtlConfigured() {
        properties.setAgentNoteTtl(java.time.Duration.ofDays(7));
        when(repository.deleteByAgentIdAndSessionIdAndUpdatedAtBefore(eq(AGENT), isNull(), any()))
                .thenReturn(1L);

        AgentScratchpadService.WriteResult result = service.write(AGENT, null, "k", "body");

        assertThat(result.expiredRemoved()).isEqualTo(1);
    }

    @Test
    void rewriteOfExistingKeyIsAllowedAtCap() {
        // The cap gates inserts only - an agent at the cap must still be able
        // to rewrite the notes it already has.
        properties.setMaxNotes(1);
        ScratchpadNote existing = new ScratchpadNote(AGENT, SESSION, "k", "old", 3);
        when(repository.findByAgentIdAndSessionIdAndKey(AGENT, SESSION, "k"))
                .thenReturn(Optional.of(existing));
        when(repository.countByAgentIdAndSessionId(AGENT, SESSION)).thenReturn(1L);

        assertThat(service.write(AGENT, SESSION, "k", "new").note().getBody()).isEqualTo("new");
        verify(repository).save(existing);
    }

    @Test
    void capIsPerScopeNotPerAgent() {
        // The session scope is full, but the agent-long-term scope is not -
        // a null-session write must still pass.
        when(repository.countByAgentIdAndSessionId(AGENT, SESSION)).thenReturn(200L);
        ScratchpadNote note = service.write(AGENT, null, "k", "body").note();
        assertThat(note.getSessionId()).isNull();
    }

    // -------------------------------------------------------------- list / delete

    @Test
    void listReturnsSessionAndLongTermNotes() {
        ScratchpadNote sessionNote = new ScratchpadNote(AGENT, SESSION, "s", "b", 1);
        ScratchpadNote longTerm = new ScratchpadNote(AGENT, null, "l", "b", 1);
        when(repository.findVisible(AGENT, SESSION)).thenReturn(List.of(sessionNote, longTerm));
        assertThat(service.list(AGENT, SESSION)).containsExactly(sessionNote, longTerm);
    }

    @Test
    void deleteReportsWhetherANoteExisted() {
        when(repository.deleteByAgentIdAndSessionIdAndKey(AGENT, SESSION, "k")).thenReturn(1L);
        when(repository.deleteByAgentIdAndSessionIdAndKey(AGENT, SESSION, "gone")).thenReturn(0L);
        assertThat(service.delete(AGENT, SESSION, "k")).isTrue();
        assertThat(service.delete(AGENT, SESSION, "gone")).isFalse();
    }

    // -------------------------------------------------------------- disabled

    @Test
    void everyOperationRefusesWhenDisabled() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.write(AGENT, SESSION, "k", "b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not enabled");
        assertThatThrownBy(() -> service.read(AGENT, SESSION, "k"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.list(AGENT, SESSION))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.delete(AGENT, SESSION, "k"))
                .isInstanceOf(IllegalStateException.class);
    }
}
