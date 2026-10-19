package com.agenticai.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TenantAiModelPreferenceServiceTest {

    private TenantRepository repository;
    private TenantLimits limits;
    private TenantAiModelPreferenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(TenantRepository.class);
        limits = mock(TenantLimits.class);
        service = new TenantAiModelPreferenceService(repository, new ObjectMapper(), limits);
    }

    @Test
    void aWriteForAnUnknownFeatureKeyIsRejectedWithTheAllowedValues() {
        assertThatThrownBy(() -> service.upsert(
                "t1", "nofeature", new AiModelPreference("PLATFORM_HOSTED", "mock:latest")))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getReason()).contains("nofeature").contains("default").contains("agent-chat");
            });
        verify(repository, never()).save(any());
    }

    @Test
    void anOrphanKeyCanStillBeCleared() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getAiModelPreferences()).thenReturn(
            "{\"nofeature\":{\"providerName\":\"PLATFORM_HOSTED\",\"model\":\"m\"}}");
        when(repository.findBySchemaName("t1")).thenReturn(Optional.of(tenant));

        assertThat(service.upsert("t1", "nofeature", null)).isEmpty();
        verify(tenant).updateAiModelPreferences(null);
    }

    @Test
    void aKnownFeatureKeyIsStored() {
        Tenant tenant = mock(Tenant.class);
        when(repository.findBySchemaName("t1")).thenReturn(Optional.of(tenant));

        var result = service.upsert("t1", "default", new AiModelPreference("PLATFORM_HOSTED", "mock:latest"));

        assertThat(result).containsKey("default");
        verify(repository).save(tenant);
    }
}
