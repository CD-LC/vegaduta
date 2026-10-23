package com.agenticai.core.agents.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.generation.AgentGenerationProperties;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The hosted-pool token reservation clamps a stored max-tokens value at the platform ceiling
 * ({@code app.agents.max-tokens-ceiling}), the same one the create / update validation uses.
 */
class FreeTierUsageLimiterMaxTokensCeilingTest {

    @Test
    @SuppressWarnings("unchecked")
    void theHostedPoolReservationIsCappedAtTheCeiling() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(ops.increment(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        FreeTierUsageLimiterService limiter = new FreeTierUsageLimiterService(
            redis, settings, 400_000L, 2, 3, new SimpleMeterRegistry(), 2_000L, 0L, mock(ObjectProvider.class));

        // Default ceiling: a stored 50000 reserves 8192 + the prompt estimate, as before.
        assertThat(limiter.checkAndReserveTokenBudget("t", TenantPlan.FREE, 50_000))
            .isEqualTo(8192L + 2_000L);

        AgentGenerationProperties lowered = new AgentGenerationProperties();
        lowered.setMaxTokensCeiling(4096);
        limiter.useGenerationProperties(lowered);
        assertThat(limiter.checkAndReserveTokenBudget("t", TenantPlan.FREE, 50_000))
            .isEqualTo(4096L + 2_000L);
    }
}
