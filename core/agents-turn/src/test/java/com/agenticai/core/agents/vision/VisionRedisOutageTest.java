package com.agenticai.core.agents.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Redis DOWN (a real connection failure, not the null-return case the older tests cover): usage
 * metering fails open as its javadoc promises, and a vision session - which IS a Redis record -
 * answers a clean 503 instead of an unmapped exception (a 500 plus an Issues-portal record).
 */
class VisionRedisOutageTest {

    private static final String TENANT = "acme";
    private static final String USER = "user-1";

    private ValueOperations<String, String> valueOperations;
    private StringRedisTemplate redisTemplate;
    private VisionUsageService usage;
    private VisionSessionService sessions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisConnectionFailureException down =
                new RedisConnectionFailureException("Unable to connect to Redis");
        when(valueOperations.get(anyString())).thenThrow(down);
        when(valueOperations.increment(anyString(), anyLong())).thenThrow(down);
        doThrow(down).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.delete(anyString())).thenThrow(down);
        usage =
                new VisionUsageService(
                        redisTemplate, new VisionProperties(), new SimpleMeterRegistry());
    }

    @Test
    void usageMeteringFailsOpenWhenRedisIsDown() {
        assertThatCode(() -> usage.recordFrames(TENANT, USER, TenantPlan.FREE, 1))
                .doesNotThrowAnyException();
        assertThatCode(() -> usage.recordWatchMinutes(TENANT, USER, 3)).doesNotThrowAnyException();
        assertThatCode(() -> usage.checkWatchMinutesHeadroom(TENANT, USER, TenantPlan.PREMIUM))
                .doesNotThrowAnyException();
        // nothing could be counted, so the whole allowance reads as unused
        assertThat(usage.framesRemaining(TENANT, USER, TenantPlan.FREE))
                .isEqualTo(usage.framesDailyCapFor(TenantPlan.FREE));
    }

    @Test
    void startingASessionIsACleanServiceUnavailableWhenRedisIsDown() {
        TenantLimits tenantLimits = mock(TenantLimits.class);
        ApprovalPolicyService approval = mock(ApprovalPolicyService.class);
        when(tenantLimits.plan(TENANT)).thenReturn(TenantPlan.PREMIUM);
        when(tenantLimits.liveVisionAllowed(TENANT)).thenReturn(true);
        sessions =
                new VisionSessionService(
                        redisTemplate,
                        com.agenticai.core.json.JsonMappers.plain(),
                        new VisionProperties(),
                        usage,
                        tenantLimits,
                        approval);

        assertThatThrownBy(
                        () ->
                                sessions.start(
                                        TENANT,
                                        USER,
                                        UUID.randomUUID(),
                                        VisionSessionMode.SNAPSHOT,
                                        VisionSource.CAMERA))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(
                        e ->
                                assertThat(((ResponseStatusException) e).getStatusCode())
                                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void endingASessionIsACleanServiceUnavailableWhenRedisIsDown() {
        sessions =
                new VisionSessionService(
                        redisTemplate,
                        com.agenticai.core.json.JsonMappers.plain(),
                        new VisionProperties(),
                        usage,
                        mock(TenantLimits.class),
                        mock(ApprovalPolicyService.class));
        assertThatThrownBy(() -> sessions.end("some-session", TENANT, USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(
                        e ->
                                assertThat(((ResponseStatusException) e).getStatusCode())
                                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void frameAttributionStaysBestEffort() {
        sessions =
                new VisionSessionService(
                        redisTemplate,
                        com.agenticai.core.json.JsonMappers.plain(),
                        new VisionProperties(),
                        usage,
                        mock(TenantLimits.class),
                        mock(ApprovalPolicyService.class));
        assertThatCode(() -> sessions.recordFrames("some-session", TENANT, 2))
                .doesNotThrowAnyException();
    }
}
