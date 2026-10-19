package com.agenticai.core.agents.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code app.edition.billing-enabled=false}: the monthly hosted-pool ceiling never rejects (there is no plan to
 * upgrade to) but usage is still counted, so the cost and ROI views stay truthful. Default (edition on) is
 * covered unchanged by {@link FreeTierUsageLimiterServiceTest}; the first test here pins it against the same
 * inputs.
 */
class FreeTierUsageLimiterBillingEditionTest {

    private static final String TENANT = "tenant-a";
    private static final long LIMIT = 400_000L;

    private ValueOperations<String, String> valueOps;
    private FreeTierUsageLimiterService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        service = new FreeTierUsageLimiterService(
            redis, settings, LIMIT, 2, 3, new SimpleMeterRegistry(), 2_000L, 0L, mock(ObjectProvider.class));
    }

    @Test
    void billingEditionOnStillRejectsOverTheCeiling() {
        when(valueOps.increment(contains("monthly-tokens"), eq(3000L))).thenReturn(LIMIT + 1);

        assertThatThrownBy(() -> service.checkAndReserveTokenBudget(TENANT, TenantPlan.FREE, 1000))
            .isInstanceOf(TenantLimitExceededException.class);
    }

    @Test
    void billingEditionOffNeverRejectsAndKeepsTheUsageCounted() {
        ReflectionTestUtils.setField(service, "billingEdition", false);
        when(valueOps.increment(contains("monthly-tokens"), eq(3000L))).thenReturn(LIMIT * 10);

        long reserved = service.checkAndReserveTokenBudget(TENANT, TenantPlan.FREE, 1000);

        assertThat(reserved).isEqualTo(3000L);
        // Counted, and never rolled back.
        verify(valueOps).increment(contains("monthly-tokens"), eq(3000L));
    }
}
