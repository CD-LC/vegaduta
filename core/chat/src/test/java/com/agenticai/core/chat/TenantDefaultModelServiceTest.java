package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.ExternalProviderCredentialSource;
import com.agenticai.core.providers.LlmProviderCredential;
import com.agenticai.core.providers.LlmProviderCredentialRepository;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderStatus;
import com.agenticai.core.providers.ProviderUsageSignal;
import com.agenticai.core.providers.dto.ProviderResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Prod signed-in pass 2026-10-05, bug 1: a new agent defaulted to
 * {@code GOOGLE_VERTEX / gemini-2.0-flash-001} - the first provider row - and
 * its first chat answered "The AI provider rejected our credentials", in a
 * bring-your-own-key workspace whose OpenRouter key was answering every call.
 *
 * <p>Runs the real {@link ProviderCredentialResolver} ranking over the real
 * {@link CallLogProviderUsageSignal}, with only the two repositories mocked,
 * so the whole "which provider is actually usable" path is under test.
 */
class TenantDefaultModelServiceTest {

    private static final String TENANT = "RUF_DUTA";

    private LlmProviderCredentialRepository credentials;
    private LlmCallLogRepository callLog;
    private TenantLimits tenantLimits;
    private ProviderModelCatalogService catalog;
    private ExternalProviderCredentialSource external;
    private TenantDefaultModelService service;

    @BeforeEach
    void setUp() {
        credentials = mock(LlmProviderCredentialRepository.class);
        callLog = mock(LlmCallLogRepository.class);
        tenantLimits = mock(TenantLimits.class);
        catalog = mock(ProviderModelCatalogService.class);
        external = mock(ExternalProviderCredentialSource.class);
        when(external.providerName()).thenReturn(ProviderName.SARVAM);
        when(external.currentCredential()).thenReturn(Optional.empty());
        when(callLog.findTopByProviderNameAndSuccessAndStartedAtAfterOrderByStartedAtDesc(any(), eq(true), any()))
            .thenReturn(Optional.empty());
        when(callLog.findTopByProviderNameAndSuccessAndStartedAtAfterOrderByStartedAtDesc(any(), eq(false), any()))
            .thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderUsageSignal> signal = mock(ObjectProvider.class);
        when(signal.getIfAvailable()).thenReturn(new CallLogProviderUsageSignal(callLog));
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
            credentials, mock(EncryptionService.class), List.of(), signal);
        service = new TenantDefaultModelService(tenantLimits, resolver, catalog, List.of(external));
        // A fresh tenant id per test keeps the signal's short TTL cache from leaking between tests.
        TenantContext.set(TenantContext.of(TENANT + "-" + System.nanoTime(), "user-1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static LlmProviderCredential active(ProviderName name) {
        LlmProviderCredential credential = mock(LlmProviderCredential.class);
        when(credential.getProviderName()).thenReturn(name);
        return credential;
    }

    private void activeCredentials(LlmProviderCredential... rows) {
        when(credentials.findByStatusOrderByCreatedAtAsc(ProviderStatus.ACTIVE)).thenReturn(List.of(rows));
    }

    private static LlmCallLog call(ProviderName provider, String model, boolean success, String error, Instant at) {
        LlmCallLog row = mock(LlmCallLog.class);
        when(row.getModel()).thenReturn(model);
        when(row.getStartedAt()).thenReturn(at);
        when(row.getErrorMessage()).thenReturn(error);
        return row;
    }

    private void logged(ProviderName provider, boolean success, LlmCallLog row) {
        when(callLog.findTopByProviderNameAndSuccessAndStartedAtAfterOrderByStartedAtDesc(
            eq(provider), eq(success), any())).thenReturn(Optional.of(row));
    }

    @Test
    void byokWorkspaceDefaultsToTheProviderAndModelThatLastAnsweredNotTheOldestKey() {
        Instant now = Instant.now();
        activeCredentials(active(ProviderName.GOOGLE_VERTEX), active(ProviderName.OPENROUTER));
        LlmCallLog vertexRefused = call(ProviderName.GOOGLE_VERTEX, "gemini-2.0-flash-001", false,
            "Failed to generate content", now.minusSeconds(30));
        when(vertexRefused.getStackTrace()).thenReturn(
            "java.lang.RuntimeException: Failed to generate content\n\tat x.Y.z(Y.java:1)\n"
                + "Caused by: com.google.genai.errors.ClientException: 401 . Request had invalid authentication "
                + "credentials.\n\tat a.B.c(B.java:2)");
        LlmCallLog openRouterOk = call(ProviderName.OPENROUTER, "deepseek/deepseek-chat", true, null,
            now.minusSeconds(120));
        logged(ProviderName.GOOGLE_VERTEX, false, vertexRefused);
        logged(ProviderName.OPENROUTER, true, openRouterOk);

        TenantDefaultModelService.DefaultModel picked = service.resolve();

        assertEquals(ProviderName.OPENROUTER, picked.providerName());
        assertEquals("deepseek/deepseek-chat", picked.model());
        assertEquals(TenantDefaultModelService.Source.WORKING_PROVIDER, picked.source());
        assertFalse(picked.needsAttention());
        assertNull(picked.message());
    }

    @Test
    void anUntriedKeyGetsTheCatalogDefaultModelOfItsOwnProvider() {
        activeCredentials(active(ProviderName.OPENAI));
        when(catalog.defaultModelFor(ProviderName.OPENAI)).thenReturn(Optional.of("gpt-4o-mini"));

        TenantDefaultModelService.DefaultModel picked = service.resolve();

        assertEquals(ProviderName.OPENAI, picked.providerName());
        assertEquals("gpt-4o-mini", picked.model());
        assertEquals(TenantDefaultModelService.Source.ACTIVE_PROVIDER, picked.source());
        assertFalse(picked.needsAttention());
    }

    @Test
    void theOnlyKeyBeingRefusedIsStillOfferedButSaysToCheckIt() {
        activeCredentials(active(ProviderName.GOOGLE_VERTEX));
        when(catalog.defaultModelFor(ProviderName.GOOGLE_VERTEX)).thenReturn(Optional.of("gemini-2.0-flash-001"));
        logged(ProviderName.GOOGLE_VERTEX, false, call(ProviderName.GOOGLE_VERTEX, "gemini-2.0-flash-001", false,
            "403 Forbidden from POST https://example.invalid/v1", Instant.now()));

        TenantDefaultModelService.DefaultModel picked = service.resolve();

        assertEquals(ProviderName.GOOGLE_VERTEX, picked.providerName());
        assertTrue(picked.needsAttention());
        assertTrue(picked.message().contains("Providers settings"), picked.message());
    }

    @Test
    void noUsableProviderSaysConnectOneInsteadOfNamingAProvider() {
        activeCredentials();
        when(external.currentCredential()).thenReturn(Optional.empty());

        TenantDefaultModelService.DefaultModel picked = service.resolve();

        assertNull(picked.providerName());
        assertNull(picked.model());
        assertEquals(TenantDefaultModelService.Source.NONE, picked.source());
        assertTrue(picked.needsAttention());
        assertTrue(picked.message().contains("Connect a model provider"), picked.message());
    }

    @Test
    void activeVendorOwnedKeyIsUsedWhenNoRankedKeyExists() {
        activeCredentials();
        when(external.currentCredential()).thenReturn(Optional.of(new ProviderResponse(
            null, ProviderName.SARVAM, ProviderStatus.ACTIVE, Instant.now(), Instant.now(), null)));
        when(catalog.defaultModelFor(ProviderName.SARVAM)).thenReturn(Optional.of("sarvam-m"));

        TenantDefaultModelService.DefaultModel picked = service.resolve();

        assertEquals(ProviderName.SARVAM, picked.providerName());
        assertEquals("sarvam-m", picked.model());
        assertEquals(TenantDefaultModelService.Source.ACTIVE_PROVIDER, picked.source());
        assertFalse(picked.needsAttention());
    }

    @Test
    void platformPoolWorkspaceDefaultsToThePoolWhateverKeysExist() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElseThrow();
        when(tenantLimits.usesPlatformPool(tenantId)).thenReturn(true);
        activeCredentials(active(ProviderName.OPENAI));

        TenantDefaultModelService.DefaultModel picked = service.resolve();

        assertEquals(ProviderName.PLATFORM_HOSTED, picked.providerName());
        assertEquals("platform-pool", picked.model());
        assertEquals(TenantDefaultModelService.Source.PLATFORM_POOL, picked.source());
    }

    @Test
    void credentialRejectionIsRecognisedButARateLimitOrRetiredModelIsNot() {
        assertTrue(CallLogProviderUsageSignal.looksLikeCredentialRejection("401 Unauthorized from POST /v1/chat", null));
        assertTrue(CallLogProviderUsageSignal.looksLikeCredentialRejection("Incorrect API key provided: invalid_api_key", null));
        assertTrue(CallLogProviderUsageSignal.looksLikeCredentialRejection("Failed to generate content",
            "java.lang.RuntimeException Caused by: com.google.genai.errors.ClientException: 403 . PERMISSION_DENIED"));
        assertFalse(CallLogProviderUsageSignal.looksLikeCredentialRejection("429 Too Many Requests - rate limit", null));
        assertFalse(CallLogProviderUsageSignal.looksLikeCredentialRejection("404 model not found: grok-3", null));
        assertFalse(CallLogProviderUsageSignal.looksLikeCredentialRejection("Read timed out after 14010 ms", null));
        assertFalse(CallLogProviderUsageSignal.looksLikeCredentialRejection(null, null));
    }
}
