package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * A2-PR1 acceptance criteria this class exercises directly: per-plan caps
 * resolve correctly (FREE/PREMIUM/UNLIMITED), -1 semantics, cache
 * invalidation on edit, and the Redis-down fallback reading the DB rather
 * than failing open/closed. {@link TenantLimitsServiceImplTest} covers the
 * -1 -> {@code Integer.MAX_VALUE}/{@code Optional.empty()} translation at
 * the public {@code TenantLimits} interface boundary; this class stays at
 * {@link PlanLimitsService}'s own level (raw -1 values, cache mechanics).
 */
class PlanLimitsServiceTest {

    private PlanLimitsRepository planLimitsRepository;
    private TenantRepository tenantRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private FreeModeService freeModeService;
    private PlanLimitsService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        planLimitsRepository = mock(PlanLimitsRepository.class);
        tenantRepository = mock(TenantRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        freeModeService = mock(FreeModeService.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Mockito's default boolean stub is false - free mode OFF, so every
        // pre-existing test exercises normal plan enforcement unchanged.
        service = new PlanLimitsService(planLimitsRepository, tenantRepository, redisTemplate, freeModeService);
    }

    private static PlanLimits row(TenantPlan plan, int users, int agents, int workflows, int kb, boolean byok, boolean whatsapp, boolean whiteLabel) {
        // live_vision_allowed (V180) and the six V470 billing entitlements
        // mirror their migration seeds: FREE gets none of them; PREMIUM gets
        // live-vision + external-channels + developer-api + sso + automation
        // (scim and workflow-export are UNLIMITED-only per V470); UNLIMITED
        // gets all. Existing per-plan assertions stay representative without
        // every call site growing six more args.
        boolean paid = plan != TenantPlan.FREE;
        boolean unlimited = plan == TenantPlan.UNLIMITED;
        return new PlanLimits(plan, users, agents, workflows, kb, byok, whatsapp, whiteLabel,
            paid /*liveVision*/, paid /*externalChannels*/, paid /*developerApi*/, paid /*sso*/,
            unlimited /*scim*/, unlimited /*workflowExport*/, paid /*automation*/);
    }

    @Test
    void resolvesFreeLimitsFromDbOnCacheMiss() {
        when(valueOperations.get("plan-limits:FREE")).thenReturn(null);
        when(planLimitsRepository.findById(TenantPlan.FREE))
            .thenReturn(Optional.of(row(TenantPlan.FREE, 2, 5, 3, 1, false, false, false)));

        PlanLimitsService.ResolvedPlanLimits limits = service.getLimitsForPlan(TenantPlan.FREE);

        assertEquals(2, limits.maxUsers());
        assertEquals(5, limits.maxAgents());
        assertEquals(3, limits.maxWorkflows());
        assertEquals(1, limits.maxKbCollections());
        // Result is written back into the cache after a DB read - 15 fields
        // since V470 widened the record with the six billing entitlements.
        verify(valueOperations).set("plan-limits:FREE",
            "FREE|2|5|3|1|false|false|false|false|false|false|false|false|false|false");
    }

    @Test
    void resolvesPremiumLimitsFromDbOnCacheMiss() {
        when(valueOperations.get("plan-limits:PREMIUM")).thenReturn(null);
        when(planLimitsRepository.findById(TenantPlan.PREMIUM))
            .thenReturn(Optional.of(row(TenantPlan.PREMIUM, 10, 25, 15, 5, true, true, false)));

        PlanLimitsService.ResolvedPlanLimits limits = service.getLimitsForPlan(TenantPlan.PREMIUM);

        assertEquals(10, limits.maxUsers());
        assertEquals(25, limits.maxAgents());
        assertEquals(15, limits.maxWorkflows());
        assertEquals(true, limits.byokAllowed());
        assertEquals(true, limits.whatsappAllowed());
        assertEquals(false, limits.whiteLabel());
    }

    @Test
    void unlimitedPlanStoresRawNegativeOneSentinel() {
        // PlanLimitsService itself hands back the raw -1 sentinel; the
        // Integer.MAX_VALUE / Optional.empty() translation happens one
        // layer up, in TenantLimitsServiceImpl (see TenantLimitsServiceImplTest).
        when(valueOperations.get("plan-limits:UNLIMITED")).thenReturn(null);
        when(planLimitsRepository.findById(TenantPlan.UNLIMITED))
            .thenReturn(Optional.of(row(TenantPlan.UNLIMITED, -1, -1, -1, -1, true, true, true)));

        PlanLimitsService.ResolvedPlanLimits limits = service.getLimitsForPlan(TenantPlan.UNLIMITED);

        assertEquals(-1, limits.maxUsers());
        assertEquals(-1, limits.maxAgents());
        assertEquals(-1, limits.maxWorkflows());
        assertEquals(-1, limits.maxKbCollections());
    }

    @Test
    void servesFromCacheWithoutHittingDbOnCacheHit() {
        when(valueOperations.get("plan-limits:FREE")).thenReturn(
            "FREE|2|5|3|1|false|false|false|false|false|false|false|false|false|false");

        PlanLimitsService.ResolvedPlanLimits limits = service.getLimitsForPlan(TenantPlan.FREE);

        assertEquals(2, limits.maxUsers());
        assertEquals(TenantPlan.FREE, limits.plan());
        verify(planLimitsRepository, never()).findById(any());
    }

    @Test
    void preV180CachedValueIsTreatedAsAMissAndReReadFromDb() {
        // The plan-limits:{PLAN} keys have no TTL, so a value cached by a
        // build BEFORE live_vision_allowed existed (8 fields) survives a
        // deploy - deserialize must treat it as a miss and re-read, not
        // throw or silently mis-map fields. (The 9-field V180 shape would
        // behave identically now - 15 is the current contract.)
        when(valueOperations.get("plan-limits:PREMIUM")).thenReturn("PREMIUM|10|25|15|5|true|true|false");
        when(planLimitsRepository.findById(TenantPlan.PREMIUM))
            .thenReturn(Optional.of(row(TenantPlan.PREMIUM, 10, 25, 15, 5, true, true, false)));

        PlanLimitsService.ResolvedPlanLimits limits = service.getLimitsForPlan(TenantPlan.PREMIUM);

        assertEquals(true, limits.liveVisionAllowed());
        verify(planLimitsRepository, times(1)).findById(TenantPlan.PREMIUM);
    }

    @Test
    void missingPlanLimitsRowThrowsRatherThanFailingOpenOrClosed() {
        when(valueOperations.get("plan-limits:FREE")).thenReturn(null);
        when(planLimitsRepository.findById(TenantPlan.FREE)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.getLimitsForPlan(TenantPlan.FREE));
    }

    @Test
    void redisDownOnReadFallsBackToDbInsteadOfFailingOpenOrClosed() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis connection refused"));
        when(planLimitsRepository.findById(TenantPlan.FREE))
            .thenReturn(Optional.of(row(TenantPlan.FREE, 2, 5, 3, 1, false, false, false)));

        PlanLimitsService.ResolvedPlanLimits limits = service.getLimitsForPlan(TenantPlan.FREE);

        assertEquals(2, limits.maxUsers());
        assertEquals(5, limits.maxAgents());
    }

    @Test
    void redisDownOnWriteDoesNotPropagateAfterASuccessfulDbRead() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(planLimitsRepository.findById(TenantPlan.FREE))
            .thenReturn(Optional.of(row(TenantPlan.FREE, 2, 5, 3, 1, false, false, false)));
        org.mockito.Mockito.doThrow(new RuntimeException("Redis connection refused"))
            .when(valueOperations).set(anyString(), anyString());

        PlanLimitsService.ResolvedPlanLimits limits = service.getLimitsForPlan(TenantPlan.FREE);

        assertEquals(2, limits.maxUsers());
    }

    @Test
    void evictPlanLimitsCacheDeletesOnlyThatPlansKey() {
        service.evictPlanLimitsCache(TenantPlan.PREMIUM);

        verify(redisTemplate).delete("plan-limits:PREMIUM");
    }

    @Test
    void evictPlanLimitsCacheSwallowsARedisOutageRatherThanThrowing() {
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("Redis connection refused"));

        service.evictPlanLimitsCache(TenantPlan.FREE);
        // No exception propagated - caller (a future admin-edit endpoint)
        // should not fail the edit itself just because cache-busting failed;
        // the stale value falls out on its own once something re-caches it.
    }

    @Test
    void resolvePlanCachesTenantPlanWithFiveMinuteTtl() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getPlan()).thenReturn(TenantPlan.PREMIUM);
        when(valueOperations.get("tenant:plan:acme")).thenReturn(null);
        when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

        TenantPlan plan = service.resolvePlan("acme");

        assertEquals(TenantPlan.PREMIUM, plan);
        verify(valueOperations).set("tenant:plan:acme", "PREMIUM", Duration.ofMinutes(5));
    }

    @Test
    void resolvePlanServesFromCacheWithoutHittingDbOnCacheHit() {
        when(valueOperations.get("tenant:plan:acme")).thenReturn("FREE");

        TenantPlan plan = service.resolvePlan("acme");

        assertEquals(TenantPlan.FREE, plan);
        verify(tenantRepository, never()).findBySchemaName(anyString());
    }

    @Test
    void unknownTenantSchemaThrowsIllegalState() {
        when(valueOperations.get("tenant:plan:ghost")).thenReturn(null);
        when(tenantRepository.findBySchemaName("ghost")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.resolvePlan("ghost"));
    }

    @Test
    void getAllPlanLimitsReturnsAllThreePlansInDeclarationOrder() {
        when(valueOperations.get("plan-limits:FREE")).thenReturn(null);
        when(planLimitsRepository.findById(TenantPlan.FREE))
            .thenReturn(Optional.of(row(TenantPlan.FREE, 2, 5, 3, 1, false, false, false)));
        when(valueOperations.get("plan-limits:PREMIUM")).thenReturn(null);
        when(planLimitsRepository.findById(TenantPlan.PREMIUM))
            .thenReturn(Optional.of(row(TenantPlan.PREMIUM, 10, 25, 15, 5, true, true, false)));
        when(valueOperations.get("plan-limits:UNLIMITED")).thenReturn(null);
        when(planLimitsRepository.findById(TenantPlan.UNLIMITED))
            .thenReturn(Optional.of(row(TenantPlan.UNLIMITED, -1, -1, -1, -1, true, true, true)));

        List<PlanLimitsService.ResolvedPlanLimits> all = service.getAllPlanLimits();

        assertEquals(3, all.size());
        assertEquals(TenantPlan.FREE, all.get(0).plan());
        assertEquals(TenantPlan.PREMIUM, all.get(1).plan());
        assertEquals(TenantPlan.UNLIMITED, all.get(2).plan());
    }

    @Test
    void freeModeResolvesUnlimitedWithoutTouchingCacheOrDb() {
        // platform.free-mode.enabled = true: every tenant resolves UNLIMITED
        // before the Redis read, so a cached "FREE" from before the flip can
        // never hold a tenant at its old gate - and an unknown tenant can't
        // throw either (no Tenant row is loaded).
        when(freeModeService.isEnabled()).thenReturn(true);
        when(valueOperations.get("tenant:plan:acme")).thenReturn("FREE");

        assertEquals(TenantPlan.UNLIMITED, service.resolvePlan("acme"));
        assertEquals(TenantPlan.UNLIMITED, service.resolvePlan("ghost"));

        verify(tenantRepository, never()).findBySchemaName(anyString());
    }
}
