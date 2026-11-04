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
    private static final String ALICE = "alice";
    private static final String BOB = "bob";

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
        ScratchpadNote note = service.write(AGENT, null, ALICE, "k", "body", NoteTrust.TRUSTED).note();
        assertThat(note.getSessionId()).isNull();
        assertThat(note.getSubjectKey()).isEqualTo(ALICE);
        verify(repository)
                .findByAgentIdAndSessionIdIsNullAndSubjectKeyAndKey(eq(AGENT), eq(ALICE), eq("k"));
        verify(repository).countByAgentIdAndSessionIdIsNullAndSubjectKey(eq(AGENT), eq(ALICE));
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
        ScratchpadNote note = new ScratchpadNote(AGENT, null, ALICE, "k", "b", 1, NoteTrust.TRUSTED);
        when(repository.findByAgentIdAndSessionIdIsNullAndSubjectKeyAndKey(AGENT, ALICE, "k"))
                .thenReturn(Optional.of(note));
        assertThat(service.read(AGENT, null, ALICE, "k")).containsSame(note);
        assertThat(service.read(AGENT, null, ALICE, "missing")).isEmpty();
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
        service.write(AGENT, null, ALICE, "k", "body", NoteTrust.TRUSTED);

        verify(repository, never())
                .deleteByAgentIdAndSessionIdAndUpdatedAtBefore(any(), any(), any());
    }

    @Test
    void agentScopeSweepsWhenTtlConfigured() {
        properties.setAgentNoteTtl(java.time.Duration.ofDays(7));
        when(repository.deleteByAgentIdAndSessionIdAndUpdatedAtBefore(eq(AGENT), isNull(), any()))
                .thenReturn(1L);

        AgentScratchpadService.WriteResult result = service.write(AGENT, null, ALICE, "k", "body", NoteTrust.TRUSTED);

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
        ScratchpadNote note = service.write(AGENT, null, ALICE, "k", "body", NoteTrust.TRUSTED).note();
        assertThat(note.getSessionId()).isNull();
    }

    // -------------------------------------------------------------- list / delete

    @Test
    void listReturnsSessionAndLongTermNotes() {
        ScratchpadNote sessionNote = new ScratchpadNote(AGENT, SESSION, "s", "b", 1);
        ScratchpadNote longTerm = new ScratchpadNote(AGENT, null, ALICE, "l", "b", 1, NoteTrust.TRUSTED);
        when(repository.findVisibleTo(AGENT, SESSION, ALICE)).thenReturn(List.of(sessionNote, longTerm));
        assertThat(service.list(AGENT, SESSION, ALICE)).containsExactly(sessionNote, longTerm);
    }

    @Test
    void deleteReportsWhetherANoteExisted() {
        ScratchpadNote note = new ScratchpadNote(AGENT, SESSION, "k", "b", 1);
        when(repository.findByAgentIdAndSessionIdAndKey(AGENT, SESSION, "k"))
                .thenReturn(Optional.of(note));
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

    // ------------------------------------------------------------------ quarantine

    private static final NoteTrust TAINTED = new NoteTrust(true, List.of("fetch_web_page"));

    @Test
    void aTaintedWriteIsStoredQuarantinedWithItsSources() {
        ScratchpadNote note = service.write(AGENT, SESSION, "k", "body", TAINTED).note();

        assertThat(note.isQuarantined()).isTrue();
        assertThat(note.getSourceTaint()).containsExactly("fetch_web_page");
    }

    @Test
    void aTaintedWriteCannotOverwriteATrustedNote() {
        ScratchpadNote trusted = new ScratchpadNote(AGENT, SESSION, "k", "the real plan", 13);
        when(repository.findByAgentIdAndSessionIdAndKey(AGENT, SESSION, "k"))
                .thenReturn(Optional.of(trusted));

        assertThatThrownBy(() -> service.write(AGENT, SESSION, "k", "attacker text", TAINTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted");

        assertThat(trusted.getBody()).isEqualTo("the real plan");
        verify(repository, never()).save(any());
    }

    @Test
    void aTaintedWriteNeverEvictsToMakeRoom() {
        when(repository.countByAgentIdAndSessionId(AGENT, SESSION))
                .thenReturn((long) properties.getMaxNotes());

        assertThatThrownBy(() -> service.write(AGENT, SESSION, "k", "body", TAINTED))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).delete(any(ScratchpadNote.class));
        verify(repository, never()).save(any());
    }

    @Test
    void aQuarantinedNoteIsNotReadable() {
        ScratchpadNote held = new ScratchpadNote(AGENT, null, ALICE, "k", "b", 1, TAINTED);
        when(repository.findByAgentIdAndSessionIdIsNullAndSubjectKeyAndKey(AGENT, ALICE, "k"))
                .thenReturn(Optional.of(held));

        assertThat(service.read(AGENT, null, ALICE, "k")).isEmpty();
    }

    @Test
    void approveMakesAHeldNoteReadableAndRejectDeletesOnlyHeldNotes() {
        ScratchpadNote held = new ScratchpadNote(AGENT, null, ALICE, "held", "b", 1, TAINTED);
        ScratchpadNote trusted = new ScratchpadNote(AGENT, null, ALICE, "ok", "b", 1, NoteTrust.TRUSTED);
        when(repository.findByAgentIdAndSessionIdIsNullAndKey(AGENT, "held"))
                .thenReturn(List.of(held));
        when(repository.findByAgentIdAndSessionIdIsNullAndKey(AGENT, "ok"))
                .thenReturn(List.of(trusted));

        assertThat(service.reject(AGENT, null, "ok")).isFalse();
        verify(repository, never()).delete(trusted);
        assertThat(service.approve(AGENT, null, "held")).isTrue();
        assertThat(held.isQuarantined()).isFalse();
        assertThat(service.approve(AGENT, null, "ok")).isFalse();
    }

    // ------------------------------------------------------- G5: per-user agent pad

    @Test
    void anAgentPadWriteWithoutAnIdentifiedCallerIsRefused() {
        for (String subject : new String[] {null, "", "  "}) {
            assertThatThrownBy(
                            () -> service.write(AGENT, null, subject, "k", "body", NoteTrust.TRUSTED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no identified user");
        }
        verify(repository, never()).save(any());
        // the session pad still works for an anonymous turn
        assertThat(service.write(AGENT, SESSION, null, "k", "body", NoteTrust.TRUSTED).note().getKey())
                .isEqualTo("k");
    }

    @Test
    void anAgentPadReadAndDeleteOnlyReachTheCallersOwnNotes() {
        ScratchpadNote alices = new ScratchpadNote(AGENT, null, ALICE, "k", "alice secret", 12, NoteTrust.TRUSTED);
        when(repository.findByAgentIdAndSessionIdIsNullAndSubjectKeyAndKey(AGENT, ALICE, "k"))
                .thenReturn(Optional.of(alices));

        assertThat(service.read(AGENT, null, ALICE, "k")).containsSame(alices);
        // Bob's lookup is keyed on Bob, so Alice's row is never the one the repository returns.
        assertThat(service.read(AGENT, null, BOB, "k")).isEmpty();
        assertThat(service.read(AGENT, null, null, "k")).isEmpty();
        assertThat(service.delete(AGENT, null, BOB, "k")).isFalse();
        verify(repository, never()).delete(any(ScratchpadNote.class));
        assertThat(service.delete(AGENT, null, ALICE, "k")).isTrue();
        verify(repository).delete(alices);
    }

    @Test
    void aLegacyNoOwnerAgentPadNoteIsNeverReturnedToTheModel() {
        ScratchpadNote legacy = new ScratchpadNote(AGENT, null, "k", "who wrote me", 12);
        // even if a repository handed it back, ownership fails closed
        when(repository.findByAgentIdAndSessionIdIsNullAndSubjectKeyAndKey(AGENT, ALICE, "k"))
                .thenReturn(Optional.of(legacy));
        assertThat(service.read(AGENT, null, ALICE, "k")).isEmpty();
        assertThatThrownBy(() -> service.write(AGENT, null, ALICE, "k", "x", NoteTrust.TRUSTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another user");
    }

    @Test
    void listIsScopedToTheCallerAndNeverUsesTheOperatorQuery() {
        service.list(AGENT, SESSION, ALICE);
        verify(repository).findVisibleTo(AGENT, SESSION, ALICE);
        service.list(AGENT, SESSION, null);
        verify(repository).findVisibleTo(AGENT, SESSION, "");
        verify(repository, never()).findVisible(any(), any());
    }

    @Test
    void theNoteCapAndEvictionArePerCaller() {
        properties.setMaxNotes(2);
        when(repository.countByAgentIdAndSessionIdIsNullAndSubjectKey(AGENT, ALICE)).thenReturn(2L);
        ScratchpadNote oldest = new ScratchpadNote(AGENT, null, ALICE, "old", "b", 1, NoteTrust.TRUSTED);
        when(repository.findFirstByAgentIdAndSessionIdIsNullAndSubjectKeyOrderByUpdatedAtAsc(AGENT, ALICE))
                .thenReturn(Optional.of(oldest));

        assertThat(service.write(AGENT, null, ALICE, "new", "b", NoteTrust.TRUSTED).evictedKey())
                .isEqualTo("old");
        // Bob has room even though Alice is full
        assertThat(service.write(AGENT, null, BOB, "new", "b", NoteTrust.TRUSTED).evictedKey()).isNull();
    }

    @Test
    void aSessionNoteOwnedByAnotherSubjectCannotBeOverwrittenAndALegacyOneIsClaimed() {
        ScratchpadNote bobs = new ScratchpadNote(AGENT, SESSION, BOB, "k", "b", 1, NoteTrust.TRUSTED);
        when(repository.findByAgentIdAndSessionIdAndKey(AGENT, SESSION, "k")).thenReturn(Optional.of(bobs));
        assertThatThrownBy(() -> service.write(AGENT, SESSION, ALICE, "k", "x", NoteTrust.TRUSTED))
                .isInstanceOf(IllegalArgumentException.class);

        ScratchpadNote legacy = new ScratchpadNote(AGENT, SESSION, "l", "b", 1);
        when(repository.findByAgentIdAndSessionIdAndKey(AGENT, SESSION, "l")).thenReturn(Optional.of(legacy));
        service.write(AGENT, SESSION, ALICE, "l", "x", NoteTrust.TRUSTED);
        assertThat(legacy.getSubjectKey()).isEqualTo(ALICE);
    }

    @Test
    void erasureDeletesBySubjectEvenWhenTheScratchpadIsDisabled() {
        properties.setEnabled(false);
        when(repository.deleteBySubjectKey(ALICE)).thenReturn(3L);

        assertThat(service.eraseSubject(ALICE)).isEqualTo(3);
        assertThat(service.eraseSubject(" ")).isZero();
        assertThat(service.eraseSubject(null)).isZero();
        verify(repository, never()).deleteBySubjectKey("");
        com.agenticai.core.agents.tools.ScratchpadSubjectEraser eraser =
                new com.agenticai.core.agents.tools.ScratchpadSubjectEraser(service);
        assertThat(eraser.store()).isEqualTo("scratchpad");
        assertThat(eraser.eraseSubject(ALICE)).isEqualTo(3);
    }

    @Test
    void approvingAnAmbiguousAgentPadKeyNeedsTheOwner() {
        ScratchpadNote a = new ScratchpadNote(AGENT, null, ALICE, "k", "b", 1, TAINTED);
        ScratchpadNote b = new ScratchpadNote(AGENT, null, BOB, "k", "b", 1, TAINTED);
        when(repository.findByAgentIdAndSessionIdIsNullAndKey(AGENT, "k")).thenReturn(List.of(a, b));

        assertThatThrownBy(() -> service.approve(AGENT, null, "k"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pass subject");
        assertThat(service.approve(AGENT, null, "k", BOB)).isTrue();
        assertThat(b.isQuarantined()).isFalse();
        assertThat(a.isQuarantined()).isTrue();
    }
}
