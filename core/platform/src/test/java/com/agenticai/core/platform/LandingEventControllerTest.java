package com.agenticai.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The landing beacon's whitelist and its unchanged guard rails. Added with the
 * /start onboarding events (web/app/start/events.ts): every name web fires must
 * be in BOTH {@code ALLOWED_EVENTS} (accepted) and {@code ALLOWED_EVENTS_ORDERED}
 * (read out), otherwise the endpoint answers 204 and the counter stays a flat
 * zero that reads exactly like "nobody did this".
 */
@SuppressWarnings("unchecked")
class LandingEventControllerTest {

    /** The closed /start vocabulary - kept in step with web/app/start/events.ts ONBOARDING_EVENTS. */
    private static final List<String> ONBOARDING_EVENTS = List.of(
        "onboarding_start",
        "onboarding_goal_selected_learn",
        "onboarding_goal_selected_research",
        "onboarding_goal_selected_analyze",
        "onboarding_goal_selected_automate",
        "onboarding_goal_selected_draft",
        "onboarding_goal_selected_build",
        "onboarding_agent_created",
        "onboarding_prompt_prefilled",
        "onboarding_first_run_completed",
        "onboarding_first_run_lt1m",
        "onboarding_first_run_lt2m",
        "onboarding_first_run_lt5m",
        "onboarding_first_run_lt10m",
        "onboarding_first_run_gte10m",
        "onboarding_skipped_goal",
        "onboarding_skipped_create",
        "onboarding_skipped_try"
    );

    /** Fired by web before this change and silently dropped by core. */
    private static final List<String> PREVIOUSLY_DROPPED_EVENTS = List.of(
        "sec_shipped",
        "shipped_signup_click",
        "shipped_try_click",
        "sec_category",
        "sec_control",
        "sec_usecases"
    );

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ObjectProvider<StringRedisTemplate> provider;
    private HttpServletRequest request;
    private LandingEventController controller;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        provider = mock(ObjectProvider.class);
        request = mock(HttpServletRequest.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(ops);
        when(request.getRemoteAddr()).thenReturn("203.0.113.7");
        when(ops.increment(anyString())).thenReturn(1L);
        controller = new LandingEventController(provider);
    }

    private void beacon(String event) {
        controller.record(new LandingEventController.LandingEventRequest(event), request);
    }

    private static Set<String> allowedEvents() throws Exception {
        Field field = LandingEventController.class.getDeclaredField("ALLOWED_EVENTS");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    private static List<String> orderedEvents() throws Exception {
        Field field = LandingEventController.class.getDeclaredField("ALLOWED_EVENTS_ORDERED");
        field.setAccessible(true);
        return (List<String>) field.get(null);
    }

    @Test
    void everyOnboardingEventIsAcceptedAndCounted() {
        ONBOARDING_EVENTS.forEach(this::beacon);

        for (String event : ONBOARDING_EVENTS) {
            // one increment of this event's own per-day counter key (the trailing ':' keeps lt1m and lt10m apart)
            verify(ops).increment(startsWith("agentic:landing:" + event + ":"));
        }
    }

    @Test
    void thePreviouslyDroppedEventsAreNowAcceptedAndCounted() {
        PREVIOUSLY_DROPPED_EVENTS.forEach(this::beacon);

        for (String event : PREVIOUSLY_DROPPED_EVENTS) {
            verify(ops).increment(startsWith("agentic:landing:" + event + ":"));
        }
    }

    @Test
    void newNamesAreInBothTheAcceptedSetAndTheAdminReadOut() throws Exception {
        Set<String> allowed = allowedEvents();
        List<String> ordered = orderedEvents();

        assertThat(allowed).containsAll(ONBOARDING_EVENTS).containsAll(PREVIOUSLY_DROPPED_EVENTS);
        assertThat(ordered).containsAll(ONBOARDING_EVENTS).containsAll(PREVIOUSLY_DROPPED_EVENTS);
        // the read-out never lists a name the beacon would refuse, and never lists one twice
        assertThat(allowed).containsAll(ordered);
        assertThat(new HashSet<>(ordered)).hasSameSizeAs(ordered);
    }

    @Test
    void namesOutsideTheVocabularyAreDroppedBeforeRedisIsTouched() {
        beacon("onboarding_goal_selected_hacker");
        beacon("onboarding_first_run_lt3m");
        beacon("onboarding_");
        beacon("ONBOARDING_START");
        beacon("");
        beacon(null);
        controller.record(null, request);

        verifyNoInteractions(ops);
        verify(provider, never()).getIfAvailable();
    }

    @Test
    void thePerIpHourlyLidStillApplies() {
        when(ops.increment(startsWith("agentic:landing-rate:"))).thenReturn(301L);

        beacon("onboarding_start");

        verify(ops, never()).increment(startsWith("agentic:landing:"));
    }

    @Test
    void anEventWithinTheLidIsCountedAgainstTodaysKey() {
        beacon("onboarding_agent_created");

        verify(ops).increment(startsWith("agentic:landing-rate:203.0.113.7:"));
        verify(ops).increment(startsWith("agentic:landing:onboarding_agent_created:"));
    }

    @Test
    void failsOpenWhenRedisIsAbsentOrErroring() {
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatCode(() -> beacon("onboarding_start")).doesNotThrowAnyException();

        when(provider.getIfAvailable()).thenReturn(redis);
        when(ops.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        assertThatCode(() -> beacon("onboarding_start")).doesNotThrowAnyException();
    }

    @Test
    void theReadOutListsTheNewCounters() {
        when(ops.multiGet(anyCollection())).thenReturn(null);

        LandingEventController.LandingMetricsResponse metrics = controller.metrics(1);

        assertThat(metrics.days()).hasSize(1);
        assertThat(metrics.days().get(0).events())
            .containsKeys("onboarding_start", "onboarding_first_run_lt5m", "onboarding_first_run_completed", "sec_shipped")
            .containsEntry("onboarding_start", 0L);
    }
}
