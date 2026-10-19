package com.agenticai.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.dto.AuditLogCountsResponse;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * {@code GET /api/audit-log/counts} ({@link AuditLogService#counts}): window validation,
 * per-outcome grouping, the predicates each COUNT is built from, tenant scope and the role gate.
 *
 * <p>Same approach as {@link AuditLogServiceTest}: the real {@link Specification}s are captured and
 * run against mocked Criteria API objects. Tenant scope and the role gate are not runtime-testable
 * here (there is no MockMvc / security harness and no database in this module - see {@code
 * WorkflowTriggerEndpointAdminGateTest}), so they are asserted at the declaration level: nothing in
 * the request can name a tenant, every read goes through the tenant-scoped repository, and {@code
 * SecurityConfig} still gates {@code /api/audit-log/**} on {@code PERM_VIEW_AUDIT_LOG}.
 */
class AuditLogCountsTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private AuditLogRepository repository;
    private AuditLogService service;
    private Root<AuditLogEntry> root;
    private CriteriaQuery<?> query;
    private CriteriaBuilder cb;
    private Path<Object> actionTypePath;
    private Path<Object> outcomePath;
    private Path<Object> createdAtPath;
    private Path<Object> targetIdPath;
    private Path<Object> actorIdPath;
    private Path<Object> detailPath;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(AuditLogRepository.class);
        service = new AuditLogService(repository);
        root = mock(Root.class);
        query = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        actionTypePath = mock(Path.class);
        outcomePath = mock(Path.class);
        createdAtPath = mock(Path.class);
        targetIdPath = mock(Path.class);
        actorIdPath = mock(Path.class);
        detailPath = mock(Path.class);
        when(root.<Object>get("actionType")).thenReturn(actionTypePath);
        when(root.<Object>get("outcome")).thenReturn(outcomePath);
        when(root.<Object>get("createdAt")).thenReturn(createdAtPath);
        when(root.<Object>get("targetId")).thenReturn(targetIdPath);
        when(root.<Object>get("actorId")).thenReturn(actorIdPath);
        when(root.<Object>get("detail")).thenReturn(detailPath);
        when(cb.and(org.mockito.ArgumentMatchers.<Predicate[]>any()))
                .thenReturn(mock(Predicate.class));
    }

    @SuppressWarnings("unchecked")
    private List<Specification<AuditLogEntry>> capturedSpecs() {
        ArgumentCaptor<Specification<AuditLogEntry>> captor =
                ArgumentCaptor.forClass(Specification.class);
        verify(repository, times(AuditOutcome.values().length)).count(captor.capture());
        return captor.getAllValues();
    }

    // --- grouping -----------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void countsOncePerOutcomeAndReportsEveryOutcomeIncludingZeros() {
        // One COUNT per outcome, in enum order: SUCCESS, FAILURE, PENDING_APPROVAL, AUTO_APPROVED,
        // APPROVED, REJECTED, then the Agent SDK outcomes DENIED, EXPIRED, REPLAYED,
        // BUDGET_EXCEEDED, WARNING (all zero here).
        when(repository.count(any(Specification.class)))
                .thenReturn(4L, 1L, 2L, 0L, 0L, 3L, 0L, 0L, 0L, 0L, 0L);

        AuditLogCountsResponse out =
                service.counts("agent.hook.decision", "agent-1", null, null, null, null, NOW);

        assertEquals(10, out.total());
        assertEquals(AuditOutcome.values().length, out.byOutcome().size());
        assertEquals(4, out.byOutcome().get(AuditOutcome.SUCCESS));
        assertEquals(1, out.byOutcome().get(AuditOutcome.FAILURE));
        assertEquals(2, out.byOutcome().get(AuditOutcome.PENDING_APPROVAL));
        assertEquals(0, out.byOutcome().get(AuditOutcome.AUTO_APPROVED));
        assertEquals(0, out.byOutcome().get(AuditOutcome.APPROVED));
        assertEquals(3, out.byOutcome().get(AuditOutcome.REJECTED));

        // Each COUNT is restricted to exactly one outcome, and between them they cover all six.
        List<AuditOutcome> seen = new ArrayList<>();
        for (Specification<AuditLogEntry> spec : capturedSpecs()) {
            CriteriaBuilder each = mock(CriteriaBuilder.class);
            spec.toPredicate(root, query, each);
            ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
            verify(each).equal(eq(outcomePath), value.capture());
            seen.add((AuditOutcome) value.getValue());
        }
        assertEquals(Arrays.asList(AuditOutcome.values()), seen);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEmptyWindowIsAllZerosNotAnError() {
        when(repository.count(any(Specification.class))).thenReturn(0L);

        AuditLogCountsResponse out =
                service.counts("tool.arg_policy.denied", null, "agent:1", null, null, null, NOW);

        assertEquals(0, out.total());
        assertTrue(out.byOutcome().values().stream().allMatch(n -> n == 0L));
    }

    // --- predicates ---------------------------------------------------------

    @Test
    void buildsOnlyThePredicatesForTheFiltersThatWereGiven() {
        service.counts("agent.hook.decision", null, null, null, null, null, NOW);

        capturedSpecs().get(0).toPredicate(root, query, cb);

        verify(cb).equal(actionTypePath, "agent.hook.decision");
        verify(cb).equal(outcomePath, AuditOutcome.SUCCESS);
        verify(cb, never()).equal(eq(targetIdPath), any());
        verify(cb, never()).equal(eq(actorIdPath), any());
        verify(cb, never()).like(any(), anyString(), anyChar());
    }

    @Test
    void filtersByTargetActorAndALiteralDetailSubstring() {
        service.counts(
                " agent.hook.decision ",
                "agent-1",
                "agent:9",
                "decision=redacted 50%_x!",
                null,
                null,
                NOW);

        capturedSpecs().get(0).toPredicate(root, query, cb);

        verify(cb).equal(actionTypePath, "agent.hook.decision");
        verify(cb).equal(targetIdPath, "agent-1");
        verify(cb).equal(actorIdPath, "agent:9");
        // % _ and the escape character are literal, so the caller cannot widen the match with
        // wildcards.
        verify(cb).like(any(), eq("%decision=redacted 50!%!_x!!%"), eq('!'));
    }

    @Test
    void blankOptionalFiltersAreIgnoredAndEchoedAsNull() {
        AuditLogCountsResponse out = service.counts("x.y", "  ", "", " ", null, null, NOW);

        assertNull(out.targetId());
        assertNull(out.actorId());
        assertNull(out.detailContains());
        capturedSpecs().get(0).toPredicate(root, query, cb);
        verify(cb, never()).equal(eq(targetIdPath), any());
        verify(cb, never()).equal(eq(actorIdPath), any());
        verify(cb, never()).like(any(), anyString(), anyChar());
    }

    // --- window -------------------------------------------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void defaultsToTheLastSevenDaysFromInclusiveToExclusiveAndEchoesTheWindow() {
        AuditLogCountsResponse out = service.counts("x.y", null, null, null, null, null, NOW);

        assertEquals(NOW, out.to());
        assertEquals(NOW.minus(Duration.ofDays(7)), out.from());
        capturedSpecs().get(0).toPredicate(root, query, cb);
        verify(cb)
                .greaterThanOrEqualTo(
                        (Path) createdAtPath, (Comparable) NOW.minus(Duration.ofDays(7)));
        verify(cb).lessThan((Path) createdAtPath, (Comparable) NOW);
    }

    @Test
    void aGivenToWithoutFromCountsTheSevenDaysBeforeIt() {
        Instant to = Instant.parse("2026-09-01T00:00:00Z");

        AuditLogCountsResponse out = service.counts("x.y", null, null, null, null, to, NOW);

        assertEquals(to, out.to());
        assertEquals(to.minus(Duration.ofDays(7)), out.from());
    }

    @Test
    void acceptsExactlyNinetyDaysAndRefusesAnythingLongerInsteadOfClampingIt() {
        Instant from = NOW.minus(Duration.ofDays(90));

        assertEquals(from, service.counts("x.y", null, null, null, from, NOW, NOW).from());

        IllegalArgumentException tooLong =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.counts(
                                        "x.y", null, null, null, from.minusSeconds(1), NOW, NOW));
        assertTrue(tooLong.getMessage().contains("90 days"));
    }

    @Test
    void refusesAnEmptyOrInvertedWindowABlankActionTypeAndAnOverlongDetailFilter() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.counts("x.y", null, null, null, NOW, NOW, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.counts("x.y", null, null, null, NOW, NOW.minusSeconds(60), NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.counts(" ", null, null, null, null, null, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.counts(null, null, null, null, null, null, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.counts(
                                "x.y",
                                null,
                                null,
                                "d".repeat(AuditLogService.MAX_DETAIL_FILTER_CHARS + 1),
                                null,
                                null,
                                NOW));
    }

    @Test
    void aRefusedRequestNeverReachesTheDatabase() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.counts(
                                "x.y",
                                null,
                                null,
                                null,
                                NOW.minus(Duration.ofDays(400)),
                                NOW,
                                NOW));
        verifyNoInteractions(repository);
    }

    // --- tenant scope and role gate (declaration level) ----------------------

    @Test
    void
            theEndpointTakesNoParameterThatCouldNameATenantAndOnlyReadsThroughTheTenantScopedRepository()
                    throws NoSuchMethodException {
        Method endpoint =
                AuditLogController.class.getMethod(
                        "counts",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        Instant.class,
                        Instant.class);
        assertEquals("/api/audit-log/counts", endpoint.getAnnotation(GetMapping.class).value()[0]);
        for (Parameter p : endpoint.getParameters()) {
            assertTrue(p.isAnnotationPresent(RequestParam.class));
        }
        List<String> names =
                Arrays.stream(endpoint.getParameters())
                        .map(Parameter::getName)
                        .collect(Collectors.toList());
        assertTrue(
                names.stream()
                        .noneMatch(
                                n ->
                                        n.toLowerCase().contains("tenant")
                                                || n.toLowerCase().contains("schema")),
                "no request parameter may select a tenant: " + names);

        // The only data access is repository.count(Specification) - Hibernate's per-tenant schema
        // resolver scopes
        // it to the caller's tenant exactly like the list endpoint. No native SQL, no cross-schema
        // read.
        service.counts("x.y", null, null, null, null, null, NOW);
        capturedSpecs();
        org.mockito.Mockito.verifyNoMoreInteractions(repository);
    }

    @Test
    void securityConfigGatesTheCountsPathOnTheSamePermissionAsTheAuditList() throws IOException {
        java.nio.file.Path source = locateSecurityConfig();
        Assumptions.assumeTrue(
                source != null,
                "SecurityConfig.java not found relative to " + Paths.get("").toAbsolutePath());
        String code =
                securityRuleSourceLines(source).stream()
                        .map(String::trim)
                        .filter(
                                line ->
                                        !line.startsWith("//")
                                                && !line.startsWith("*")
                                                && !line.startsWith("/*"))
                        .collect(Collectors.joining(" "))
                        .replaceAll(
                                "\\s+", ""); // all whitespace removed: survives any line wrapping
        // /api/audit-log/** covers /api/audit-log/counts; the list and the counts share one matcher
        // and one authority.
        assertTrue(
                code.contains(
                        ".requestMatchers(\"/api/audit-log\",\"/api/audit-log/**\").hasAuthority(\"PERM_VIEW_AUDIT_LOG\")"));
    }

    /**
     * SecurityConfig.java plus the {@code *Rules.java} classes its main chain calls: since the
     * chain was split, most rules live in those classes, so a source check must read all of them.
     */
    private static List<String> securityRuleSourceLines(java.nio.file.Path securityConfig)
            throws IOException {
        List<String> lines = new ArrayList<>();
        try (Stream<java.nio.file.Path> files = Files.list(securityConfig.getParent())) {
            for (java.nio.file.Path file :
                    files.filter(
                                    p ->
                                            p.equals(securityConfig)
                                                    || p.getFileName()
                                                            .toString()
                                                            .endsWith("Rules.java"))
                            .sorted()
                            .toList()) {
                lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }
        }
        return lines;
    }

    private static java.nio.file.Path locateSecurityConfig() {
        java.nio.file.Path relative =
                Paths.get(
                        "core",
                        "security",
                        "src",
                        "main",
                        "java",
                        "com",
                        "agenticai",
                        "core",
                        "security",
                        "SecurityConfig.java");
        for (java.nio.file.Path dir = Paths.get("").toAbsolutePath();
                dir != null;
                dir = dir.getParent()) {
            java.nio.file.Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
