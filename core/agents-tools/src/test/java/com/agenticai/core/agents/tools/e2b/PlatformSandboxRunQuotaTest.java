package com.agenticai.core.agents.tools.e2b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolCredentialStatusResponse;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.sdlc.E2bAccess;
import com.agenticai.core.sdlc.E2bAccessResolver;
import com.agenticai.core.sdlc.E2bApiException;
import com.agenticai.core.sdlc.E2bNotConfiguredException;
import com.agenticai.core.sdlc.E2bSandboxCreateException;
import com.agenticai.core.sdlc.SdlcSandboxService;
import com.agenticai.core.sdlc.dto.RunCodeRequest;
import com.agenticai.core.sdlc.dto.RunCodeResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * D1: the per-tenant daily meter for platform-paid run_code, and how the tool callback applies it.
 */
class PlatformSandboxRunQuotaTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String INPUT = "{\"code\":\"print(1)\",\"language\":\"python\"}";
    private static final E2bAccess PLATFORM =
            new E2bAccess("platform-key", "platform-tpl", E2bAccess.Source.PLATFORM);
    private static final E2bAccess TENANT =
            new E2bAccess("tenant-key", "tenant-tpl", E2bAccess.Source.TENANT);

    private final TenantLimits limits = mock(TenantLimits.class);
    private final AtomicReference<LocalDate> today =
            new AtomicReference<>(LocalDate.of(2026, 10, 3));
    private final SdlcSandboxService sandbox = mock(SdlcSandboxService.class);
    private final E2bAccessResolver resolver = mock(E2bAccessResolver.class);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static void as(String tenant) {
        TenantContext.set(TenantContext.of(tenant, "user", Set.of()));
    }

    @SuppressWarnings("unchecked")
    private PlatformSandboxRunQuota quota(int free, int paid) {
        ObjectProvider<TenantLimits> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(limits);
        return new PlatformSandboxRunQuota(provider, free, paid, 30, today::get);
    }

    @Test
    void freePlanIsCappedAndTheMessageSaysWhatToDo() {
        when(limits.storedPlan("t1")).thenReturn(TenantPlan.FREE);
        PlatformSandboxRunQuota quota = quota(2, 5);
        as("t1");

        assertNull(quota.tryConsume());
        assertNull(quota.tryConsume());
        String denied = quota.tryConsume();
        assertNotNull(denied);
        assertTrue(denied.contains("Daily analysis limit reached"));
        assertTrue(denied.contains("E2B key"));
        assertTrue(denied.contains("tomorrow"));
        assertEquals(2, quota.usedToday());
    }

    @Test
    void paidPlansGetThePaidCapAndUnlimitedIsNotUncapped() {
        when(limits.storedPlan("premium")).thenReturn(TenantPlan.PREMIUM);
        when(limits.storedPlan("unlimited")).thenReturn(TenantPlan.UNLIMITED);
        PlatformSandboxRunQuota quota = quota(1, 3);
        for (String tenant : new String[] {"premium", "unlimited"}) {
            as(tenant);
            assertNull(quota.tryConsume());
            assertNull(quota.tryConsume());
            assertNull(quota.tryConsume());
            assertNotNull(quota.tryConsume(), tenant + " must stop at the paid cap");
        }
    }

    @Test
    void theStoredPlanIsReadNotTheFreeModeEffectivePlan() {
        when(limits.storedPlan("t1")).thenReturn(TenantPlan.FREE);
        when(limits.plan("t1")).thenReturn(TenantPlan.UNLIMITED); // platform free mode
        PlatformSandboxRunQuota quota = quota(1, 100);
        as("t1");
        assertNull(quota.tryConsume());
        assertNotNull(quota.tryConsume());
        verify(limits, never()).plan(anyString());
    }

    @Test
    void countersArePerTenantAndResetOnTheNextUtcDay() {
        when(limits.storedPlan(anyString())).thenReturn(TenantPlan.FREE);
        PlatformSandboxRunQuota quota = quota(1, 1);
        as("t1");
        assertNull(quota.tryConsume());
        assertNotNull(quota.tryConsume());
        as("t2");
        assertNull(quota.tryConsume(), "another tenant has its own counter");
        today.set(today.get().plusDays(1));
        as("t1");
        assertNull(quota.tryConsume(), "a new day starts from zero");
    }

    @Test
    void refundGivesTheRunBackAndNeverGoesNegative() {
        when(limits.storedPlan("t1")).thenReturn(TenantPlan.FREE);
        PlatformSandboxRunQuota quota = quota(1, 1);
        as("t1");
        quota.refund();
        assertEquals(0, quota.usedToday());
        assertNull(quota.tryConsume());
        quota.refund();
        assertNull(quota.tryConsume());
        assertNotNull(quota.tryConsume());
    }

    @Test
    void noTenantContextIsRefusedAndAFailedPlanLookupMeansTheFreeCap() {
        TenantContext.clear();
        PlatformSandboxRunQuota quota = quota(1, 50);
        assertEquals(PlatformSandboxRunQuota.NO_WORKSPACE_MESSAGE, quota.tryConsume());

        when(limits.storedPlan("t1")).thenThrow(new IllegalStateException("db down"));
        as("t1");
        assertNull(quota.tryConsume());
        assertNotNull(quota.tryConsume());
    }

    // ---- E2bRunCodeToolCallback applies the meter only to platform-paid runs ----

    private E2bRunCodeToolCallback callback(PlatformSandboxRunQuota quota) {
        return new E2bRunCodeToolCallback(sandbox, null, null, null, null, "s1", resolver, quota);
    }

    @Test
    void platformRunIsCountedForcedToTheMaxTimeoutAndOverQuotaReturnsErrorJson() throws Exception {
        when(limits.storedPlan("t1")).thenReturn(TenantPlan.FREE);
        when(resolver.resolveForCodeExecution()).thenReturn(PLATFORM);
        when(sandbox.runCode(any(E2bAccess.class), any(RunCodeRequest.class)))
                .thenReturn(new RunCodeResponse("hi", "", 0, false, "sbx"));
        as("t1");
        E2bRunCodeToolCallback callback = callback(quota(1, 1));

        JsonNode first = MAPPER.readTree(callback.call(INPUT));
        assertEquals("hi", first.path("stdout").asString());
        ArgumentCaptor<RunCodeRequest> request = ArgumentCaptor.forClass(RunCodeRequest.class);
        ArgumentCaptor<E2bAccess> access = ArgumentCaptor.forClass(E2bAccess.class);
        verify(sandbox).runCode(access.capture(), request.capture());
        assertTrue(access.getValue().platformManaged());
        assertEquals(Integer.valueOf(30), request.getValue().timeoutSeconds());

        JsonNode second = MAPPER.readTree(callback.call(INPUT));
        assertTrue(second.path("error").asString().contains("Daily analysis limit reached"));
        assertFalse(second.has("stdout"));
        verify(sandbox, times(1)).runCode(any(E2bAccess.class), any(RunCodeRequest.class));
        // The tenant-key-only entry point is never used once a resolver is wired.
        verify(sandbox, never()).runCode(any(RunCodeRequest.class));
    }

    @Test
    void tenantKeyRunIsNeverMeteredAndKeepsTheDefaultTimeout() throws Exception {
        when(resolver.resolveForCodeExecution()).thenReturn(TENANT);
        when(sandbox.runCode(any(E2bAccess.class), any(RunCodeRequest.class)))
                .thenReturn(new RunCodeResponse("hi", "", 0, false, "sbx"));
        as("t1");
        PlatformSandboxRunQuota quota = quota(0, 0);
        E2bRunCodeToolCallback callback = callback(quota);

        assertEquals("hi", MAPPER.readTree(callback.call(INPUT)).path("stdout").asString());
        assertEquals("hi", MAPPER.readTree(callback.call(INPUT)).path("stdout").asString());
        ArgumentCaptor<RunCodeRequest> request = ArgumentCaptor.forClass(RunCodeRequest.class);
        verify(sandbox, times(2)).runCode(any(E2bAccess.class), request.capture());
        assertNull(request.getValue().timeoutSeconds());
        assertEquals(0, quota.usedToday());
    }

    @Test
    void onlyACreateFailureRefundsAPostCreateFailureStaysCounted() throws Exception {
        when(limits.storedPlan("t1")).thenReturn(TenantPlan.FREE);
        when(resolver.resolveForCodeExecution()).thenReturn(PLATFORM);
        as("t1");
        PlatformSandboxRunQuota quota = quota(2, 2);

        // One stubbing chain: re-stubbing a throwing mock with when() would itself throw.
        when(sandbox.runCode(any(E2bAccess.class), any(RunCodeRequest.class)))
                .thenThrow(new E2bSandboxCreateException(new E2bApiException(0, "E2B unreachable")))
                .thenThrow(new E2bApiException(0, "Could not reach the code-execution template"))
                .thenThrow(new IllegalStateException("boom"));

        // No sandbox was ever created: the run is given back.
        JsonNode out = MAPPER.readTree(callback(quota).call(INPUT));
        assertTrue(out.path("error").asString().contains("E2B unreachable"));
        assertEquals(0, quota.usedToday());

        // The sandbox existed and the runner failed (user code can force this): stays counted.
        assertTrue(MAPPER.readTree(callback(quota).call(INPUT)).has("error"));
        assertEquals(1, quota.usedToday());
        assertTrue(MAPPER.readTree(callback(quota).call(INPUT)).has("error"));
        assertEquals(2, quota.usedToday());
        assertTrue(
                MAPPER.readTree(callback(quota).call(INPUT))
                        .path("error")
                        .asString()
                        .contains("Daily analysis limit reached"));
    }

    @SuppressWarnings("unchecked")
    private PlatformSandboxRunQuota quota(
            int free, int paid, int timeout, int global, boolean allowDemo) {
        ObjectProvider<TenantLimits> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(limits);
        return new PlatformSandboxRunQuota(
                provider, free, paid, timeout, global, allowDemo, today::get);
    }

    @Test
    void globalDailyCeilingBoundsAllTenantsTogetherAndRefundReleasesIt() {
        when(limits.storedPlan(anyString())).thenReturn(TenantPlan.FREE);
        PlatformSandboxRunQuota quota = quota(5, 5, 30, 2, false);
        as("t1");
        assertNull(quota.tryConsume());
        as("t2");
        assertNull(quota.tryConsume());
        as("t3");
        assertEquals(PlatformSandboxRunQuota.GLOBAL_LIMIT_MESSAGE, quota.tryConsume());
        assertEquals(0, quota.usedToday());
        as("t2");
        quota.refund();
        as("t3");
        assertNull(quota.tryConsume());
        today.set(today.get().plusDays(1));
        assertNull(quota.tryConsume(), "the global counter resets with the day");
    }

    @Test
    void sharedDemoTenantIsRefusedUnlessExplicitlyAllowed() {
        when(limits.storedPlan("demo")).thenReturn(TenantPlan.FREE);
        when(limits.isSharedDemoTenant("demo")).thenReturn(true);
        as("demo");
        PlatformSandboxRunQuota refused = quota(5, 5, 30, 100, false);
        assertEquals(PlatformSandboxRunQuota.DEMO_TENANT_MESSAGE, refused.tryConsume());
        assertEquals(0, refused.usedToday());
        assertNull(quota(5, 5, 30, 100, true).tryConsume());
    }

    @Test
    void maxTimeoutIsClampedOnBothSides() {
        assertEquals(1, quota(1, 1, 0, 10, false).maxTimeoutSeconds());
        assertEquals(30, quota(1, 1, 30, 10, false).maxTimeoutSeconds());
        assertEquals(
                PlatformSandboxRunQuota.MAX_TIMEOUT_CEILING_SECONDS,
                quota(1, 1, 3000, 10, false).maxTimeoutSeconds());
    }

    @Test
    void platformRunWithoutAQuotaBeanIsRefusedAndNotConfiguredBecomesErrorJson() throws Exception {
        when(resolver.resolveForCodeExecution()).thenReturn(PLATFORM);
        as("t1");
        JsonNode refused = MAPPER.readTree(callback(null).call(INPUT));
        assertTrue(refused.path("error").asString().contains("not available yet"));

        when(resolver.resolveForCodeExecution()).thenThrow(new E2bNotConfiguredException());
        JsonNode notConfigured = MAPPER.readTree(callback(quota(1, 1)).call(INPUT));
        assertTrue(notConfigured.has("error"));
        verify(sandbox, never()).runCode(any(E2bAccess.class), any(RunCodeRequest.class));
    }

    // ---- SandboxFileRegistry: one readiness predicate, reason reworded for the platform fallback
    // ----

    @SuppressWarnings("unchecked")
    private SandboxFileRegistry registryWith(boolean ready, boolean platformEnabled) {
        when(resolver.codeExecutionReady()).thenReturn(ready);
        when(resolver.platformFallbackEnabled()).thenReturn(platformEnabled);
        ObjectProvider<E2bAccessResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        SandboxFileRegistry registry = new SandboxFileRegistry(true, 6, 1000, 10_000, 60);
        registry.setAccessResolver(provider);
        return registry;
    }

    @Test
    void registryUsesTheAccessResolverAndRewordsTheReasonWhenThePlatformFallbackIsOn() {
        assertTrue(registryWith(true, true).availableFor(true));
        assertEquals(
                SandboxFileRegistry.REASON_NOT_AVAILABLE_YET,
                registryWith(false, true).unavailableReason(true));
        assertTrue(
                SandboxFileRegistry.REASON_NOT_AVAILABLE_YET.contains(
                        "not available yet for this workspace"));
        assertEquals(
                SandboxFileRegistry.REASON_CONNECT_E2B,
                registryWith(false, false).unavailableReason(true));
        // Agent attachment is still checked first.
        assertTrue(registryWith(true, true).unavailableReason(false).contains("Code execution"));
    }

    // ---- PlatformE2bAccessProvider: key AND template id, read fresh ----

    @SuppressWarnings("unchecked")
    private PlatformE2bAccessProvider provider(PlatformToolCredentialService service) {
        ObjectProvider<PlatformToolCredentialService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(service);
        return new PlatformE2bAccessProvider(p);
    }

    @Test
    void platformProviderNeedsBothKeyAndTemplateId() {
        PlatformToolCredentialService service = mock(PlatformToolCredentialService.class);
        when(service.resolveActiveApiKey(PlatformToolKey.E2B))
                .thenReturn(Optional.of("platform-key"));
        when(service.resolveActiveExtraConfig(PlatformToolKey.E2B)).thenReturn(Optional.empty());
        when(service.status(PlatformToolKey.E2B))
                .thenReturn(
                        new PlatformToolCredentialStatusResponse(
                                PlatformToolKey.E2B, true, null, null, null));
        PlatformE2bAccessProvider provider = provider(service);
        assertTrue(provider.resolve().isEmpty());
        assertFalse(provider.isConfigured());

        when(service.resolveActiveExtraConfig(PlatformToolKey.E2B))
                .thenReturn(Optional.of(" tpl-1 "));
        when(service.status(PlatformToolKey.E2B))
                .thenReturn(
                        new PlatformToolCredentialStatusResponse(
                                PlatformToolKey.E2B, true, "tpl-1", null, null));
        assertTrue(provider.isConfigured());
        E2bAccess access = provider.resolve().orElseThrow();
        assertEquals("platform-key", access.apiKey());
        assertEquals("tpl-1", access.templateId());
        assertTrue(access.platformManaged());

        assertTrue(provider(null).resolve().isEmpty());
        assertFalse(provider(null).isConfigured());
    }
}
