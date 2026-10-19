package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.dto.ProviderResponse;
import com.agenticai.core.providers.validation.ProviderValidator;
import com.agenticai.core.security.TenantLimits;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** {@code GET /api/providers}: every ACTIVE credential carries {@code health} so the web can warn (bug 4). */
class ProviderServiceHealthTest {

    private LlmProviderCredentialRepository repository;
    private ProviderUsageSignal signal;
    private ProviderService service;

    @BeforeEach
    void setUp() {
        repository = mock(LlmProviderCredentialRepository.class);
        signal = mock(ProviderUsageSignal.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderUsageSignal> signalProvider = mock(ObjectProvider.class);
        when(signalProvider.getIfAvailable()).thenReturn(signal);
        ProviderValidator validator = mock(ProviderValidator.class);
        when(validator.supportedProvider()).thenReturn(ProviderName.ANTHROPIC);
        service = new ProviderService(repository, mock(EncryptionService.class), List.of(validator), List.of(),
            mock(TenantLimits.class), mock(PlatformHostedLlmProviderService.class), signalProvider);
    }

    private static LlmProviderCredential credential(ProviderName name, ProviderStatus status) {
        LlmProviderCredential c = mock(LlmProviderCredential.class);
        when(c.getProviderName()).thenReturn(name);
        when(c.getStatus()).thenReturn(status);
        return c;
    }

    @Test
    void listAllReportsHealthOnActiveCredentialsOnlySoTheWebCanWarnAboutARefusedKey() {
        LlmProviderCredential vertex = credential(ProviderName.GOOGLE_VERTEX, ProviderStatus.ACTIVE);
        LlmProviderCredential grok = credential(ProviderName.GROK, ProviderStatus.INACTIVE);
        when(repository.findAll()).thenReturn(List.of(vertex, grok));
        Instant failed = Instant.parse("2026-10-05T10:00:00Z");
        ProviderHealth rejected = new ProviderHealth(ProviderHealth.REJECTED, 14, 362, 0, null, failed,
            "Failed to generate content", failed.plusSeconds(6 * 3600));
        when(signal.health(List.of(ProviderName.GOOGLE_VERTEX), Duration.ofHours(6)))
            .thenReturn(Map.of(ProviderName.GOOGLE_VERTEX, rejected));

        List<ProviderResponse> all = service.listAll();

        assertEquals(ProviderHealth.REJECTED, all.get(0).health().status());
        assertEquals(362, all.get(0).health().failures());
        assertEquals("Failed to generate content", all.get(0).health().lastError());
        assertNull(all.get(1).health(), "an inactive credential has no health");
    }

    @Test
    void aFailingCallHistorySourceNeverBreaksTheListing() {
        LlmProviderCredential vertexCredential = credential(ProviderName.GOOGLE_VERTEX, ProviderStatus.ACTIVE);
        when(repository.findAll()).thenReturn(List.of(vertexCredential));
        when(signal.health(any(), any())).thenThrow(new IllegalStateException("call log down"));

        List<ProviderResponse> all = service.listAll();

        assertEquals(1, all.size());
        assertNull(all.get(0).health());
    }

    @Test
    void withoutACallHistorySourceTheListingIsUnchanged() {
        LlmProviderCredential vertexCredential = credential(ProviderName.GOOGLE_VERTEX, ProviderStatus.ACTIVE);
        when(repository.findAll()).thenReturn(List.of(vertexCredential));
        ProviderValidator validator = mock(ProviderValidator.class);
        when(validator.supportedProvider()).thenReturn(ProviderName.ANTHROPIC);
        ProviderService plain = new ProviderService(repository, mock(EncryptionService.class), List.of(validator),
            List.of(), mock(TenantLimits.class), mock(PlatformHostedLlmProviderService.class));

        assertNull(plain.listAll().get(0).health());
    }
}
