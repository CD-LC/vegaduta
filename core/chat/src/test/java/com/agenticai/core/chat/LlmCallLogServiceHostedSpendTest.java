package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The monthly spend-cap counter feed: hosted-pool costs only, never BYOK, never fatal. */
class LlmCallLogServiceHostedSpendTest {

    private final LlmCallLogService service =
            new LlmCallLogService(
                    mock(LlmCallLogRepository.class),
                    new LlmPricingCatalog(new SimpleMeterRegistry(), System::nanoTime));
    private final List<String> seen = new ArrayList<>();
    private final List<BigDecimal> costs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service.setHostedSpendListeners(
                List.of(
                        (tenant, cost) -> {
                            seen.add(tenant);
                            costs.add(cost);
                        }));
        TenantContext.set(TenantContext.of("tenant_acme", "user-1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void record(ProviderName provider) {
        service.record(
                null,
                "s1",
                provider,
                "gemini-2.5-flash",
                1000L,
                200L,
                1200L,
                true,
                null,
                "r1",
                null,
                null,
                42L,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void hostedCallCostReachesTheListenerWithTheBoundTenant() {
        record(ProviderName.PLATFORM_HOSTED);
        assertEquals(List.of("tenant_acme"), seen);
        assertTrue(costs.get(0).signum() > 0);
    }

    @Test
    void byokCallsAreNeverCounted() {
        record(ProviderName.GEMINI);
        record(ProviderName.OPENAI);
        assertTrue(seen.isEmpty());
    }

    @Test
    void aFailingListenerNeverBreaksRecording() {
        service.setHostedSpendListeners(
                List.of(
                        (tenant, cost) -> {
                            throw new IllegalStateException("redis down");
                        }));
        assertDoesNotThrow(() -> record(ProviderName.PLATFORM_HOSTED));
    }

    @Test
    void noTenantBoundMeansNothingToCount() {
        TenantContext.clear();
        record(ProviderName.PLATFORM_HOSTED);
        assertTrue(seen.isEmpty());
    }
}
