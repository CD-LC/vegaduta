package com.agenticai.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * {@link AuditLogService} is, by its own javadoc, deliberately the ONLY
 * place anything in this codebase writes an {@link AuditLogEntry} - the
 * class every other Tier 2 suite's {@code eraseDataSubject}/compliance-path
 * tests already mock as a collaborator, but which itself had zero direct
 * coverage. Its {@link #list(String, String, java.time.Instant,
 * java.time.Instant)} method is the one piece of real logic worth locking
 * down: it builds a {@link Specification} in Java specifically to work
 * around a documented Postgres/Hibernate parameter-type-inference bug (see
 * {@link AuditLogRepository}'s javadoc) rather than a static {@code
 * (:param IS NULL OR ...)} JPQL query - the whole point being that an unset
 * filter must never even become a bind parameter. That's only actually true
 * if the predicate-building logic itself is correct, which is exactly what
 * this suite verifies.
 *
 * <p>Since {@link Specification} is a plain functional interface ({@code
 * toPredicate(Root, CriteriaQuery, CriteriaBuilder)}), this suite captures
 * the real {@code Specification} instance {@link AuditLogService} builds
 * and invokes it directly against mocked JPA Criteria API objects ({@link
 * Root}/{@link CriteriaQuery}/{@link CriteriaBuilder} are all plain
 * interfaces) - the same "exercise the real logic, mock only the
 * boundary" approach every other suite in this codebase takes, applied to
 * a boundary this codebase hadn't needed to mock before.
 */
class AuditLogServiceTest {

    private AuditLogRepository repository;
    private AuditLogService service;
    private Root<AuditLogEntry> root;
    private CriteriaQuery<?> query;
    private CriteriaBuilder cb;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(AuditLogRepository.class);
        service = new AuditLogService(repository);
        root = mock(Root.class);
        query = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        // Distinct Path mocks per field - NOT a single shared stub for any
        // string argument. root.get("actionType") and root.get("targetType")
        // must resolve to different mock identities, or verify(cb, never())
        // .equal(root.get("targetType"), any()) would false-negative (it'd
        // silently also match the real code's cb.equal(root.get("actionType"), ...)
        // call, since both would appear to share the same Path argument).
        Path<Object> actionTypePath = mock(Path.class);
        Path<Object> targetTypePath = mock(Path.class);
        Path<Object> createdAtPath = mock(Path.class);
        when(root.<Object>get("actionType")).thenReturn(actionTypePath);
        when(root.<Object>get("targetType")).thenReturn(targetTypePath);
        when(root.<Object>get("createdAt")).thenReturn(createdAtPath);
        when(cb.equal(any(), any())).thenReturn(mock(Predicate.class));
        when(cb.greaterThanOrEqualTo(any(), any(Instant.class))).thenReturn(mock(Predicate.class));
        when(cb.lessThanOrEqualTo(any(), any(Instant.class))).thenReturn(mock(Predicate.class));
        when(cb.and(org.mockito.ArgumentMatchers.<Predicate[]>any())).thenReturn(mock(Predicate.class));
        when(cb.desc(any())).thenReturn(mock(jakarta.persistence.criteria.Order.class));
        // Every list() call - regardless of which test - must find a
        // stubbed Page waiting, or the real getContent() call inside
        // AuditLogService#list throws a NullPointerException before this
        // suite ever gets to inspect the Specification it built.
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
    }

    @SuppressWarnings("unchecked")
    private Specification<AuditLogEntry> captureSpecification() {
        org.mockito.ArgumentCaptor<Specification<AuditLogEntry>> captor = org.mockito.ArgumentCaptor.forClass(Specification.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).findAll(captor.capture(), any(Pageable.class));
        return captor.getValue();
    }

    // --- record() ----------------------------------------------------------

    @Test
    void recordSavesAndReturnsANewAuditLogEntryWithTheGivenFields() {
        when(repository.save(any(AuditLogEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditLogEntry entry = service.record("actor-1", "agent.created", "agent", "agent-123", AuditOutcome.SUCCESS, "details");

        assertEquals("actor-1", entry.getActorId());
        assertEquals("agent.created", entry.getActionType());
        assertEquals("agent", entry.getTargetType());
        assertEquals("agent-123", entry.getTargetId());
        assertEquals(AuditOutcome.SUCCESS, entry.getOutcome());
        assertEquals("details", entry.getDetail());
    }

    // --- distinctActionTypes() -----------------------------------------------

    @Test
    void distinctActionTypesDelegatesDirectlyToTheRepositoryQuery() {
        when(repository.findDistinctActionTypes()).thenReturn(List.of("agent.created", "workflow.run.failed"));

        assertEquals(List.of("agent.created", "workflow.run.failed"), service.distinctActionTypes());
    }

    // --- list(): predicate-building logic ------------------------------------

    @Test
    void listTwoArgOverloadPassesNullFromAndToSoNoDateRangePredicatesAreEverAdded() {
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.list("agent.created", "agent");

        Specification<AuditLogEntry> spec = captureSpecification();
        spec.toPredicate(root, query, cb);

        verify(cb, never()).greaterThanOrEqualTo(any(), any(Instant.class));
        verify(cb, never()).lessThanOrEqualTo(any(), any(Instant.class));
    }

    @Test
    void listBuildsAnActionTypeEqualityPredicateWhenActionTypeIsProvided() {
        service.list("agent.created", null);

        Specification<AuditLogEntry> spec = captureSpecification();
        spec.toPredicate(root, query, cb);

        verify(cb).equal(root.get("actionType"), "agent.created");
        verify(cb, never()).equal(eq(root.get("targetType")), any());
    }

    @Test
    void listNormalizesABlankActionTypeToNoPredicateAtAllRatherThanMatchingAnEmptyString() {
        service.list("   ", "agent");

        Specification<AuditLogEntry> spec = captureSpecification();
        spec.toPredicate(root, query, cb);

        verify(cb, never()).equal(eq(root.get("actionType")), any());
        verify(cb).equal(root.get("targetType"), "agent");
    }

    @Test
    void listNormalizesABlankTargetTypeToNoPredicateAtAllRatherThanMatchingAnEmptyString() {
        service.list("agent.created", "");

        Specification<AuditLogEntry> spec = captureSpecification();
        spec.toPredicate(root, query, cb);

        verify(cb, never()).equal(eq(root.get("targetType")), any());
    }

    @Test
    void listBuildsBothDateRangeBoundsWhenFromAndToAreBothProvided() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T23:59:59Z");

        service.list(null, null, from, to);

        Specification<AuditLogEntry> spec = captureSpecification();
        spec.toPredicate(root, query, cb);

        verify(cb).greaterThanOrEqualTo(root.get("createdAt"), from);
        verify(cb).lessThanOrEqualTo(root.get("createdAt"), to);
    }

    @Test
    void listBuildsOnlyTheFromBoundWhenToIsOmitted() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");

        service.list(null, null, from, null);

        Specification<AuditLogEntry> spec = captureSpecification();
        spec.toPredicate(root, query, cb);

        verify(cb).greaterThanOrEqualTo(root.get("createdAt"), from);
        verify(cb, never()).lessThanOrEqualTo(any(), any(Instant.class));
    }

    @Test
    void listAlwaysOrdersByCreatedAtDescendingRegardlessOfWhichFiltersAreSet() {
        service.list(null, null);

        Specification<AuditLogEntry> spec = captureSpecification();
        spec.toPredicate(root, query, cb);

        verify(cb).desc(root.get("createdAt"));
        verify(query).orderBy(any(jakarta.persistence.criteria.Order.class));
    }

    @Test
    void listRequestsAtMostTheFirst200RowsAndReturnsThePageContent() {
        AuditLogEntry entry = mock(AuditLogEntry.class);
        Page<AuditLogEntry> page = new PageImpl<>(List.of(entry));
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        List<AuditLogEntry> results = service.list(null, null);

        assertEquals(List.of(entry), results);
        org.mockito.ArgumentCaptor<Pageable> pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageableCaptor.capture());
        assertEquals(0, pageableCaptor.getValue().getPageNumber());
        assertEquals(200, pageableCaptor.getValue().getPageSize());
    }

    @Test
    void searchUsesExactOffsetLimitAndNewestFirstSortWithIdTiebreaker() {
        AuditLogEntry entry = mock(AuditLogEntry.class);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(entry), org.springframework.data.domain.PageRequest.of(0, 10), 41));

        Page<AuditLogEntry> page = service.search(null, null, null, null, 30, 10);

        assertEquals(41, page.getTotalElements());
        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), captor.capture());
        assertEquals(30, captor.getValue().getOffset());
        assertEquals(10, captor.getValue().getPageSize());
        assertEquals(org.springframework.data.domain.Sort.Direction.DESC,
            captor.getValue().getSort().getOrderFor("createdAt").getDirection());
        assertEquals(org.springframework.data.domain.Sort.Direction.DESC,
            captor.getValue().getSort().getOrderFor("id").getDirection());
    }

    // --- search(): actor + free-text q filters ---------------------------------

    @Test
    void searchActorIsAnExactActorIdMatchAndBlankActorAddsNoPredicate() {
        service.search(null, null, "user-7", null, null, null, 0, 10);
        captureSpecification().toPredicate(root, query, cb);
        verify(cb).equal(root.get("actorId"), "user-7");

        org.mockito.Mockito.clearInvocations(cb);
        service.search(null, null, "  ", "", null, null, 0, 10);
        captureSpecification().toPredicate(root, query, cb);
        verify(cb, never()).equal(eq(root.get("actorId")), any());
        verify(cb, never()).like(org.mockito.ArgumentMatchers.<jakarta.persistence.criteria.Expression<String>>any(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyChar());
    }

    @Test
    void searchQIsLowercasedLiteralSubstringAcrossFourColumnsWithWildcardsEscaped() {
        service.search(null, null, null, "50%_Off!", null, null, 0, 10);
        captureSpecification().toPredicate(root, query, cb);

        verify(cb, org.mockito.Mockito.times(4)).like(
            org.mockito.ArgumentMatchers.<jakarta.persistence.criteria.Expression<String>>any(),
            eq("%50!%!_off!!%"), eq('!'));
        org.mockito.Mockito.verify(cb).or(org.mockito.ArgumentMatchers.<Predicate>any(), org.mockito.ArgumentMatchers.<Predicate>any(),
            org.mockito.ArgumentMatchers.<Predicate>any(), org.mockito.ArgumentMatchers.<Predicate>any());
    }

    @Test
    void searchRejectsAnOverlongQ() {
        String tooLong = "x".repeat(AuditLogService.MAX_QUERY_CHARS + 1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.search(null, null, null, tooLong, null, null, 0, 10));
    }

    @Test
    void sixArgSearchStillDelegatesWithNoActorOrQ() {
        service.search("agent.created", null, null, null, 0, 10);
        captureSpecification().toPredicate(root, query, cb);
        verify(cb).equal(root.get("actionType"), "agent.created");
        verify(cb, never()).equal(eq(root.get("actorId")), any());
    }
}
