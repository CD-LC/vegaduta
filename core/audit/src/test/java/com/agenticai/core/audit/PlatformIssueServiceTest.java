package com.agenticai.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link PlatformIssueService} backs the Admin Issues portal (#425/#426) -
 * its {@link #record} method is called synchronously from three failure
 * paths across the codebase ({@code GlobalExceptionHandler}'s catch-all,
 * a failed workflow run, a tool call error) specifically so a real outage
 * still gets captured even if the broker or outbox relay is itself down
 * (see the class's own javadoc for why this deliberately bypasses the
 * event pipeline). Its fingerprint-based dedup/upsert logic was manually
 * investigated earlier in this engagement and confirmed correct, but had no
 * actual test file locking that finding in - this suite is that regression
 * test, plus first-time coverage for the concurrent-race branch
 * ({@link DataIntegrityViolationException} on a fingerprint unique-
 * constraint collision) and {@code createTicket}'s deliberate
 * non-deduplication (#974).
 *
 * <p>{@link PlatformIssue} is constructed for real throughout - its public
 * constructors set every field this suite needs, so unlike {@code
 * ChannelSession} elsewhere in this codebase's Tier 2 work, there's no
 * JPA-generated-only field forcing a mock here.
 */
class PlatformIssueServiceTest {

    private PlatformIssueRepository repository;
    private PlatformIssueService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformIssueRepository.class);
        service = new PlatformIssueService(repository);
    }

    // --- record(): brand-new fingerprint --------------------------------

    @Test
    void recordCreatesABrandNewIssueWithOccurrenceCountOneAndOpenStatusForANeverBeforeSeenFingerprint() {
        when(repository.findByFingerprint(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(PlatformIssue.class))).thenAnswer(inv -> inv.getArgument(0));

        PlatformIssue issue = service.record(IssueSource.EXCEPTION, "agent-123", IssueSeverity.ERROR, "NullPointerException", "boom");

        assertEquals(1, issue.getOccurrenceCount());
        assertEquals(IssueStatus.OPEN, issue.getStatus());
        assertEquals(IssueSource.EXCEPTION, issue.getSource());
        assertEquals("agent-123", issue.getSourceId());
        assertEquals(IssueSeverity.ERROR, issue.getSeverity());
        assertEquals("NullPointerException", issue.getTitle());
        assertEquals("boom", issue.getMessage());
    }

    // --- record(): recurrence on an already-seen fingerprint ----------------

    @Test
    void recordBumpsOccurrenceCountAndLastSeenAtRatherThanCreatingASecondRowForARepeatFingerprint() {
        PlatformIssue existing = new PlatformIssue(
            "fp-abc", IssueSource.EXCEPTION, "agent-123", IssueSeverity.ERROR, "NullPointerException", "first message"
        );
        when(repository.findByFingerprint(anyString())).thenReturn(Optional.of(existing));
        when(repository.save(any(PlatformIssue.class))).thenAnswer(inv -> inv.getArgument(0));

        PlatformIssue result = service.record(IssueSource.EXCEPTION, "agent-123", IssueSeverity.ERROR, "NullPointerException", "second message");

        assertEquals(existing, result);
        assertEquals(2, result.getOccurrenceCount());
        assertEquals("second message", result.getMessage());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.argThat(saved -> saved != existing));
    }

    @Test
    void recordReopensAPreviouslyResolvedIssueWhenTheSameFingerprintRecurs() {
        PlatformIssue existing = new PlatformIssue(
            "fp-abc", IssueSource.EXCEPTION, "agent-123", IssueSeverity.ERROR, "NullPointerException", "first message"
        );
        existing.resolve();
        assertEquals(IssueStatus.RESOLVED, existing.getStatus());
        when(repository.findByFingerprint(anyString())).thenReturn(Optional.of(existing));
        when(repository.save(any(PlatformIssue.class))).thenAnswer(inv -> inv.getArgument(0));

        PlatformIssue result = service.record(IssueSource.EXCEPTION, "agent-123", IssueSeverity.ERROR, "NullPointerException", "it's back");

        assertEquals(IssueStatus.OPEN, result.getStatus());
    }

    @Test
    void recordProducesTheSameFingerprintForIdenticalSourceSourceIdAndTitleRegardlessOfMessage() {
        // fingerprint() is deliberately keyed on source+sourceId+title, NOT
        // message - see the method's own javadoc on why a variable message
        // (a record id, a stack trace timestamp) must not fragment one
        // recurring problem into many distinct fingerprints.
        when(repository.findByFingerprint(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(PlatformIssue.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(IssueSource.EXCEPTION, "agent-123", IssueSeverity.ERROR, "NullPointerException", "message A: record 111");
        service.record(IssueSource.EXCEPTION, "agent-123", IssueSeverity.ERROR, "NullPointerException", "message B: record 999");

        org.mockito.ArgumentCaptor<String> fingerprintCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).findByFingerprint(fingerprintCaptor.capture());
        assertEquals(fingerprintCaptor.getAllValues().get(0), fingerprintCaptor.getAllValues().get(1));
    }

    // --- record(): the DataIntegrityViolationException race branch ------------

    @Test
    void recordTreatsAUniqueConstraintRaceAsARecurrenceInsteadOfPropagatingTheConstraintViolation() {
        // Two threads both see "no existing row" for a never-before-seen
        // fingerprint, both attempt to insert, and the database's unique
        // constraint on fingerprint lets exactly one through - this test is
        // the loser: its own findByFingerprint() missed, its save() then
        // hits the constraint the winner already satisfied.
        PlatformIssue winnersRow = new PlatformIssue(
            "fp-race", IssueSource.WORKFLOW_RUN, "run-1", IssueSeverity.ERROR, "Workflow run failed", "winner's message"
        );
        when(repository.findByFingerprint(anyString()))
            .thenReturn(Optional.empty())   // first check, before the insert attempt
            .thenReturn(Optional.of(winnersRow)); // second check, inside the catch block
        when(repository.save(any(PlatformIssue.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
            .thenAnswer(inv -> inv.getArgument(0));

        PlatformIssue result = service.record(IssueSource.WORKFLOW_RUN, "run-1", IssueSeverity.ERROR, "Workflow run failed", "loser's message");

        assertEquals(winnersRow, result);
        assertEquals(2, result.getOccurrenceCount());
        assertEquals("loser's message", result.getMessage());
    }

    @Test
    void recordRethrowsTheOriginalConstraintViolationIfTheRaceWinnersRowIsSomehowStillNotFound() {
        // A genuinely unexpected state (the constraint fired, but a second
        // lookup for the same fingerprint still comes back empty) - the
        // service must surface the ORIGINAL exception rather than a
        // confusing NoSuchElementException from a bare orElseThrow(NPE).
        when(repository.findByFingerprint(anyString())).thenReturn(Optional.empty(), Optional.empty());
        DataIntegrityViolationException originalException = new DataIntegrityViolationException("constraint violation");
        when(repository.save(any(PlatformIssue.class))).thenThrow(originalException);

        DataIntegrityViolationException thrown = assertThrows(
            DataIntegrityViolationException.class,
            () -> service.record(IssueSource.WORKFLOW_RUN, "run-1", IssueSeverity.ERROR, "Workflow run failed", "message")
        );
        assertEquals(originalException, thrown);
    }

    // --- createTicket(): deliberately NOT deduplicated (#974) -----------------

    @Test
    void createTicketNeverConsultsFindByFingerprintUnlikeRecord() {
        when(repository.save(any(PlatformIssue.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createTicket(IssueCategory.BILLING, "Can't send WhatsApp messages", "details");

        verify(repository, never()).findByFingerprint(anyString());
    }

    @Test
    void createTicketProducesADifferentFingerprintEveryTimeEvenForAnIdenticalTitle() {
        when(repository.save(any(PlatformIssue.class))).thenAnswer(inv -> inv.getArgument(0));

        PlatformIssue first = service.createTicket(IssueCategory.BILLING, "Can't send WhatsApp messages", "report 1");
        PlatformIssue second = service.createTicket(IssueCategory.BILLING, "Can't send WhatsApp messages", "report 2");

        // Two distinct tenant-filed tickets with the same title must remain
        // two distinct rows - see the method's own javadoc for why this must
        // never collapse into record()'s recurrence behavior.
        assertNotEquals(first.getFingerprint(), second.getFingerprint());
    }

    @Test
    void createTicketSetsTenantReportedSourceWarningSeverityAndTheGivenCategoryWithNoSourceId() {
        when(repository.save(any(PlatformIssue.class))).thenAnswer(inv -> inv.getArgument(0));

        PlatformIssue ticket = service.createTicket(IssueCategory.TECHNICAL, "Title", "message");

        assertEquals(IssueSource.TENANT_REPORTED, ticket.getSource());
        assertEquals(IssueSeverity.WARNING, ticket.getSeverity());
        assertEquals(IssueCategory.TECHNICAL, ticket.getCategory());
        assertEquals(null, ticket.getSourceId());
    }

    // --- list() --------------------------------------------------------------

    @Test
    void listWithNoStatusFilterReturnsEveryIssueNewestFirst() {
        List<PlatformIssue> all = List.of(mock(PlatformIssue.class));
        when(repository.findTop200ByOrderByLastSeenAtDesc()).thenReturn(all);

        assertEquals(all, service.list(null));
        verify(repository, never()).findTop200ByStatusOrderByLastSeenAtDesc(any());
    }

    @Test
    void listWithAStatusFilterDelegatesToTheStatusScopedQuery() {
        List<PlatformIssue> open = List.of(mock(PlatformIssue.class));
        when(repository.findTop200ByStatusOrderByLastSeenAtDesc(IssueStatus.OPEN)).thenReturn(open);

        assertEquals(open, service.list(IssueStatus.OPEN));
    }

    // --- acknowledge() / resolve() ---------------------------------------------

    @Test
    void acknowledgeMarksAnExistingIssueAcknowledgedWithTheActorId() {
        UUID id = UUID.randomUUID();
        PlatformIssue issue = new PlatformIssue("fp", IssueSource.EXCEPTION, null, IssueSeverity.ERROR, "t", "m");
        when(repository.findById(id)).thenReturn(Optional.of(issue));
        when(repository.save(issue)).thenReturn(issue);

        PlatformIssue result = service.acknowledge(id, "admin-1");

        assertEquals(IssueStatus.ACKNOWLEDGED, result.getStatus());
        assertEquals("admin-1", result.getAcknowledgedBy());
    }

    @Test
    void acknowledgeThrowsIssueNotFoundExceptionForAnUnknownId() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(IssueNotFoundException.class, () -> service.acknowledge(id, "admin-1"));
    }

    @Test
    void resolveMarksAnExistingIssueResolved() {
        UUID id = UUID.randomUUID();
        PlatformIssue issue = new PlatformIssue("fp", IssueSource.EXCEPTION, null, IssueSeverity.ERROR, "t", "m");
        when(repository.findById(id)).thenReturn(Optional.of(issue));
        when(repository.save(issue)).thenReturn(issue);

        PlatformIssue result = service.resolve(id);

        assertEquals(IssueStatus.RESOLVED, result.getStatus());
    }

    @Test
    void resolveThrowsIssueNotFoundExceptionForAnUnknownId() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(IssueNotFoundException.class, () -> service.resolve(id));
    }
}
