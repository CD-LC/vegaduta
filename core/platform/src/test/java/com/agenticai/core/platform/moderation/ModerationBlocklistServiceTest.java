package com.agenticai.core.platform.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The write path must normalise before lookup so an operator cannot pile up
 * near-duplicate blocks, and deactivation must leave the row (and its audit
 * trail) in place - unblocking is {@code active=false}, never a delete. The
 * entity's {@code apply} semantics ride along since the service is their only
 * caller.
 */
class ModerationBlocklistServiceTest {

    private ModerationBlocklistEntryRepository repository;
    private ModerationBlocklistService service;

    @BeforeEach
    void setUp() {
        repository = mock(ModerationBlocklistEntryRepository.class);
        service = new ModerationBlocklistService(repository);
    }

    // ---------------------------------------------------------------- list

    @Test
    void listWithoutATypeReturnsEverythingNewestFirst() {
        List<ModerationBlocklistEntry> entries = List.of(
            new ModerationBlocklistEntry(ModerationBlocklistType.DOMAIN, "bad.example", "spam", "ops"));
        when(repository.findByOrderByCreatedAtDesc()).thenReturn(entries);

        assertEquals(entries, service.list(null));
        verify(repository).findByOrderByCreatedAtDesc();
    }

    @Test
    void listWithATypeScopesTheQuery() {
        when(repository.findByEntryTypeOrderByCreatedAtDesc(ModerationBlocklistType.USER))
            .thenReturn(List.of());

        assertTrue(service.list(ModerationBlocklistType.USER).isEmpty());
        verify(repository).findByEntryTypeOrderByCreatedAtDesc(ModerationBlocklistType.USER);
    }

    // ---------------------------------------------------------------- upsert

    @Test
    void aBlankValueIsRefusedBeforeAnyRepositoryWork() {
        assertThrows(IllegalArgumentException.class,
            () -> service.upsert(ModerationBlocklistType.USER, "   ", true, "r", "ops"));
        assertThrows(IllegalArgumentException.class,
            () -> service.upsert(ModerationBlocklistType.DOMAIN, null, true, "r", "ops"));
    }

    @Test
    void aNewValueIsSavedNormalised() {
        when(repository.findByEntryTypeAndValue(ModerationBlocklistType.DOMAIN, "evil.example"))
            .thenReturn(Optional.empty());
        when(repository.save(any(ModerationBlocklistEntry.class))).thenAnswer(i -> i.getArgument(0));

        ModerationBlocklistEntry saved =
            service.upsert(ModerationBlocklistType.DOMAIN, " HTTPS://Evil.Example/x ", true, "phishing", "ops");

        ArgumentCaptor<ModerationBlocklistEntry> captor =
            ArgumentCaptor.forClass(ModerationBlocklistEntry.class);
        verify(repository).save(captor.capture());
        assertEquals("evil.example", captor.getValue().getValue(),
            "the stored row carries the normalised form so lookups are plain equality");
        assertEquals(ModerationBlocklistType.DOMAIN, captor.getValue().getEntryType());
        assertEquals("phishing", captor.getValue().getReason());
        assertEquals("ops", captor.getValue().getCreatedBy());
        assertTrue(saved.isActive());
    }

    @Test
    void reactivatingAnExistingRowUpdatesItInPlace() {
        ModerationBlocklistEntry existing =
            new ModerationBlocklistEntry(ModerationBlocklistType.USER, "troll@x.io", "old", "ops");
        existing.apply(false, "calmed down", "ops");
        when(repository.findByEntryTypeAndValue(ModerationBlocklistType.USER, "troll@x.io"))
            .thenReturn(Optional.of(existing));

        ModerationBlocklistEntry result =
            service.upsert(ModerationBlocklistType.USER, " Troll@X.io ", true, "back at it", "admin-2");

        assertSame(existing, result, "the same row is reused - a second row would split the audit trail");
        assertTrue(result.isActive());
        assertEquals("back at it", result.getReason());
        assertEquals("admin-2", result.getCreatedBy(), "the last actor to touch the row is recorded");
    }

    @Test
    void anEmptyReasonDoesNotEraseTheRecordedOne() {
        ModerationBlocklistEntry entry =
            new ModerationBlocklistEntry(ModerationBlocklistType.PHONE, "+1555", "spam calls", "ops");

        entry.apply(false, "  ", "admin");

        assertFalse(entry.isActive());
        assertEquals("spam calls", entry.getReason(), "a blank update keeps the existing reason");
        assertEquals("admin", entry.getCreatedBy());
    }

    @Test
    void upsertingInactiveDeactivatesEvenANewlyCreatedRow() {
        when(repository.findByEntryTypeAndValue(ModerationBlocklistType.REGION, "xx"))
            .thenReturn(Optional.empty());
        when(repository.save(any(ModerationBlocklistEntry.class))).thenAnswer(i -> i.getArgument(0));

        ModerationBlocklistEntry entry =
            service.upsert(ModerationBlocklistType.REGION, "XX", false, "test region", "ops");

        assertFalse(entry.isActive(), "active=false on create lands as an inactive row, not an active one");
    }

    // ---------------------------------------------------------------- activeValues / isBlocked

    @Test
    void activeValuesFiltersToTheRequestedType() {
        ModerationBlocklistEntry user = new ModerationBlocklistEntry(
            ModerationBlocklistType.USER, "troll@x.io", "r", "ops");
        ModerationBlocklistEntry domain = new ModerationBlocklistEntry(
            ModerationBlocklistType.DOMAIN, "evil.example", "r", "ops");
        when(repository.findByActiveTrue()).thenReturn(List.of(user, domain));

        assertEquals(Set.of("troll@x.io"), service.activeValues(ModerationBlocklistType.USER));
        assertEquals(Set.of("evil.example"), service.activeValues(ModerationBlocklistType.DOMAIN));
        assertTrue(service.activeValues(ModerationBlocklistType.PHONE).isEmpty());
    }

    @Test
    void isBlockedNormalisesTheLookupAndHonoursActive() {
        ModerationBlocklistEntry active =
            new ModerationBlocklistEntry(ModerationBlocklistType.DOMAIN, "evil.example", "r", "ops");
        when(repository.findByEntryTypeAndValue(
            eq(ModerationBlocklistType.DOMAIN), eq("evil.example")))
            .thenReturn(Optional.of(active));

        assertTrue(service.isBlocked(ModerationBlocklistType.DOMAIN, "https://www.evil.example/path"));
        assertTrue(service.isBlocked(ModerationBlocklistType.DOMAIN, "EVIL.EXAMPLE"));

        active.apply(false, null, "ops");
        assertFalse(service.isBlocked(ModerationBlocklistType.DOMAIN, "evil.example"),
            "an inactive row is not a block");
    }

    @Test
    void isBlockedTreatsBlankAndUnknownValuesAsUnblocked() {
        assertFalse(service.isBlocked(ModerationBlocklistType.USER, null));
        assertFalse(service.isBlocked(ModerationBlocklistType.USER, "   "));

        when(repository.findByEntryTypeAndValue(ModerationBlocklistType.USER, "innocent@x.io"))
            .thenReturn(Optional.empty());
        assertFalse(service.isBlocked(ModerationBlocklistType.USER, "innocent@x.io"));
    }
}
