package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import com.google.genai.Client;
import com.google.genai.errors.ServerException;
import io.micrometer.observation.ObservationRegistry;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

/**
 * Guards the shared model policy from the 2026-09-28 Spring AI audit: bounded retries that never
 * repeat a read timeout, OpenAI reasoning-model parameter rules, per-provider stream usage,
 * default-advisor attachment, the BYOK Gemini thinking bound, and the genai Client cache.
 */
class LlmModelSupportTest {

    // ------------------------------------------------------------ retry policy

    @Test
    void transientClassificationRetriesServerErrorsAndConnectFailuresOnly() {
        assertTrue(LlmModelSupport.isTransient(new TransientAiException("503 - overloaded")));
        assertTrue(
                LlmModelSupport.isTransient(
                        new ResourceAccessException("I/O error", new ConnectException("refused"))));
        assertTrue(
                LlmModelSupport.isTransient(
                        new ResourceAccessException(
                                "I/O error",
                                new HttpConnectTimeoutException("connect timed out"))));
        // GoogleGenAiChatModel wraps SDK errors in a plain RuntimeException.
        assertTrue(
                LlmModelSupport.isTransient(
                        new RuntimeException(
                                "Failed to generate content",
                                new ServerException(503, "UNAVAILABLE", "overloaded"))));

        // 4xx - including 429 - goes straight to the caller's provider fallback.
        assertFalse(LlmModelSupport.isTransient(new NonTransientAiException("429 - rate limited")));
        assertFalse(LlmModelSupport.isTransient(new IllegalStateException("bug")));
    }

    @Test
    void aReadTimeoutIsNeverRetriedBecauseItAlreadyCostTheFullReadTimeout() {
        assertFalse(
                LlmModelSupport.isTransient(
                        new ResourceAccessException(
                                "I/O error", new SocketTimeoutException("Read timed out"))));
        assertFalse(
                LlmModelSupport.isTransient(
                        new ResourceAccessException(
                                "I/O error", new HttpTimeoutException("request timed out"))));
    }

    @Test
    void retryTemplateMakesAtMostTheConfiguredAttemptsForTransientFailures() {
        RetryTemplate retry =
                LlmModelSupport.boundedRetryTemplate(2, Duration.ofMillis(1), Duration.ofMillis(2));
        AtomicInteger calls = new AtomicInteger();

        assertThrows(
                TransientAiException.class,
                () ->
                        retry.invoke(
                                () -> {
                                    calls.incrementAndGet();
                                    throw new TransientAiException("500 - boom");
                                }));
        assertEquals(2, calls.get());
    }

    @Test
    void retryTemplateDoesNotRetryNonTransientFailures() {
        RetryTemplate retry =
                LlmModelSupport.boundedRetryTemplate(2, Duration.ofMillis(1), Duration.ofMillis(2));
        AtomicInteger calls = new AtomicInteger();

        assertThrows(
                NonTransientAiException.class,
                () ->
                        retry.invoke(
                                () -> {
                                    calls.incrementAndGet();
                                    throw new NonTransientAiException("400 - bad request");
                                }));
        assertEquals(1, calls.get());
    }

    @Test
    void degenerateBackoffSettingsDoNotBreakContextStartup() {
        // the retry policy needs max > initial; a max <= initial config must not fail boot.
        RetryTemplate retry = LlmModelSupport.boundedRetryTemplate(0, Duration.ZERO, Duration.ZERO);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                TransientAiException.class,
                () ->
                        retry.invoke(
                                () -> {
                                    calls.incrementAndGet();
                                    throw new TransientAiException("500");
                                }));
        assertEquals(1, calls.get());
    }

    // ------------------------------------------------- OpenAI reasoning models

    @Test
    void recognisesOpenAiReasoningFamiliesIncludingOpenRouterIds() {
        assertTrue(LlmModelSupport.isOpenAiReasoningModel("gpt-5"));
        assertTrue(LlmModelSupport.isOpenAiReasoningModel("gpt-5-mini"));
        assertTrue(LlmModelSupport.isOpenAiReasoningModel("o1"));
        assertTrue(LlmModelSupport.isOpenAiReasoningModel("o3-mini"));
        assertTrue(LlmModelSupport.isOpenAiReasoningModel("o4-mini-2025-04-16"));
        assertTrue(LlmModelSupport.isOpenAiReasoningModel("openai/gpt-5"));
        assertTrue(LlmModelSupport.isOpenAiReasoningModel("openai/o3"));

        assertFalse(LlmModelSupport.isOpenAiReasoningModel("gpt-4o"));
        assertFalse(LlmModelSupport.isOpenAiReasoningModel("gpt-5-chat-latest"));
        assertFalse(LlmModelSupport.isOpenAiReasoningModel("openai/gpt-oss-120b"));
        assertFalse(LlmModelSupport.isOpenAiReasoningModel("anthropic/claude-sonnet-4"));
        assertFalse(LlmModelSupport.isOpenAiReasoningModel("omni-moderation-latest"));
        assertFalse(LlmModelSupport.isOpenAiReasoningModel(null));
    }

    @Test
    void reasoningModelsGetMaxCompletionTokensAndNoSamplingTheyWouldReject() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("gpt-5");
        SamplingOptions sampling = new SamplingOptions(40, List.of("END"), 0.5, 0.5, 7);

        LlmModelSupport.applyOpenAiTokensAndSampling(
                builder, ProviderName.OPENAI, "gpt-5", 2048, 0.2, 0.9, sampling);
        OpenAiChatOptions options = builder.build();

        assertEquals(2048, options.getMaxCompletionTokens());
        assertNull(options.getMaxTokens());
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
        assertNull(options.getFrequencyPenalty());
        assertTrue(options.getStop() == null || options.getStop().isEmpty());
        assertEquals(7, options.getSeed());
    }

    @Test
    void ordinaryModelsKeepMaxTokensTemperatureTopPAndSampling() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("gpt-4o");
        SamplingOptions sampling = new SamplingOptions(40, List.of("END"), 0.5, null, null);

        LlmModelSupport.applyOpenAiTokensAndSampling(
                builder, ProviderName.OPENAI, "gpt-4o", 2048, 0.2, 0.9, sampling);
        OpenAiChatOptions options = builder.build();

        assertEquals(2048, options.getMaxTokens());
        assertNull(options.getMaxCompletionTokens());
        assertEquals(0.2, options.getTemperature());
        assertEquals(0.9, options.getTopP());
        assertEquals(0.5, options.getFrequencyPenalty());
        assertEquals(List.of("END"), options.getStop());
    }

    @Test
    void setFieldsReportsOnlyTheSamplingFieldsTheAgentActuallySet() {
        SamplingOptions sampling = new SamplingOptions(40, List.of(), null, 0.1, null);
        assertEquals(
                List.of("topK", "presencePenalty"),
                LlmModelSupport.setFields(
                        sampling, "topK", "stop", "frequencyPenalty", "presencePenalty", "seed"));
    }

    // ---------------------------------------------- bean-level policy wiring

    @Test
    void streamUsageIsOnByDefaultAndOffForListedProviders() {
        LlmModelSupport support =
                support("MISTRAL, not-a-provider", true, ObservationRegistry.create(), List.of());

        assertTrue(support.streamUsage(ProviderName.OPENAI));
        assertTrue(support.streamUsage(ProviderName.PLATFORM_HOSTED));
        assertFalse(support.streamUsage(ProviderName.MISTRAL));
    }

    @Test
    void usesTheAppObservationRegistryUnlessObservationsAreDisabled() {
        ObservationRegistry registry = ObservationRegistry.create();

        assertSame(registry, support("", true, registry, List.of()).observationRegistry());
        assertSame(
                ObservationRegistry.NOOP,
                support("", false, registry, List.of()).observationRegistry());
    }

    @Test
    void everyFactoryGetsPerCallAdvisorsFirstThenTheDefaultAgentAdvisors() {
        DefaultAgentAdvisor rl = mock(DefaultAgentAdvisor.class);
        Advisor metrics = mock(Advisor.class);
        Advisor merge = mock(Advisor.class);

        List<Advisor> advisors =
                support("", true, ObservationRegistry.NOOP, List.of(rl)).advisors(merge, metrics);

        assertEquals(List.of(merge, metrics, rl), advisors);
    }

    @Test
    void byokGeminiThinkingIsBoundedForThinkingFamiliesOnly() {
        LlmModelSupport support = support("", true, ObservationRegistry.NOOP, List.of());

        GoogleGenAiChatOptions.Builder flash =
                GoogleGenAiChatOptions.builder().model("gemini-2.5-flash");
        support.applyGeminiByokThinking(flash, "gemini-2.5-flash");
        assertEquals(1024, flash.build().getThinkingBudget());

        // Flash-Lite does not think by default - a budget would switch thinking on.
        GoogleGenAiChatOptions.Builder lite =
                GoogleGenAiChatOptions.builder().model("gemini-2.5-flash-lite");
        support.applyGeminiByokThinking(lite, "gemini-2.5-flash-lite");
        assertNull(lite.build().getThinkingBudget());

        GoogleGenAiChatOptions.Builder old =
                GoogleGenAiChatOptions.builder().model("gemini-2.0-flash");
        support.applyGeminiByokThinking(old, "gemini-2.0-flash");
        assertNull(old.build().getThinkingBudget());
    }

    // ------------------------------------------------------- genai client cache

    @Test
    void genAiClientsAreReusedPerKeyAndEvictedClientsAreClosed() {
        LlmModelSupport.GenAiClientCache cache =
                new LlmModelSupport.GenAiClientCache(1, Duration.ofMinutes(30));
        Client first = mock(Client.class);
        Client second = mock(Client.class);
        AtomicInteger builds = new AtomicInteger();

        Client a =
                cache.get(
                        "key-a",
                        () -> {
                            builds.incrementAndGet();
                            return first;
                        });
        Client again =
                cache.get(
                        "key-a",
                        () -> {
                            builds.incrementAndGet();
                            return second;
                        });
        assertSame(a, again);
        assertEquals(1, builds.get());
        verify(first, never()).close();

        Client b = cache.get("key-b", () -> second);
        assertNotSame(a, b);
        assertEquals(1, cache.size());
        verify(first).close();

        cache.closeAll();
        verify(second).close();
        assertEquals(0, cache.size());
    }

    @Test
    void idleGenAiClientsExpire() throws InterruptedException {
        LlmModelSupport.GenAiClientCache cache =
                new LlmModelSupport.GenAiClientCache(10, Duration.ofMillis(1));
        Client stale = mock(Client.class);

        cache.get("old", () -> stale);
        Thread.sleep(20);
        cache.get("new", () -> mock(Client.class));

        verify(stale).close();
        assertEquals(1, cache.size());
    }

    @Test
    void fingerprintsNeverCollideAcrossPartBoundaries() {
        assertFalse(
                LlmModelSupport.fingerprint("ab", "c")
                        .equals(LlmModelSupport.fingerprint("a", "bc")));
        assertEquals(LlmModelSupport.fingerprint("k", "p"), LlmModelSupport.fingerprint("k", "p"));
    }

    @Test
    void applyStructuredSchemaIsNoopWithoutSpecAndAppliesWithOne() {
        LlmModelSupport s =
                support("", true, io.micrometer.observation.ObservationRegistry.NOOP, List.of());
        org.springframework.ai.openai.OpenAiChatOptions base =
                org.springframework.ai.openai.OpenAiChatOptions.builder().model("gpt-4o").build();
        assertSame(base, s.applyStructuredSchema(base, java.util.Map.of()));
        var out =
                s.applyStructuredSchema(
                        base,
                        com.agenticai.core.chat.structured.StructuredSchemaSpec.options(
                                "n", "{\"type\":\"object\"}"));
        assertEquals(
                org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA,
                out.getResponseFormat().getType());
        // A bare {"type":"object"} is an open object: valid as json_schema, never as strict.
        assertFalse(Boolean.TRUE.equals(out.getResponseFormat().getStrict()));
    }

    @Test
    void applyStructuredSchemaLeavesRequestUntouchedWhenOffOrModelUnsupported() {
        var spec =
                com.agenticai.core.chat.structured.StructuredSchemaSpec.options(
                        "n", "{\"type\":\"object\"}");
        org.springframework.ai.openai.OpenAiChatOptions supported =
                org.springframework.ai.openai.OpenAiChatOptions.builder().model("gpt-4o").build();
        org.springframework.ai.openai.OpenAiChatOptions legacy =
                org.springframework.ai.openai.OpenAiChatOptions.builder().model("gpt-4").build();

        // Flag off (the shipped default): no response_format at all, same instance back.
        LlmModelSupport off =
                support(
                        "",
                        true,
                        io.micrometer.observation.ObservationRegistry.NOOP,
                        List.of(),
                        false);
        assertSame(supported, off.applyStructuredSchema(supported, spec));
        // Flag on, model outside the allowlist: gpt-4 rejects json_object, so nothing is sent.
        LlmModelSupport on =
                support("", true, io.micrometer.observation.ObservationRegistry.NOOP, List.of());
        assertSame(legacy, on.applyStructuredSchema(legacy, spec));
    }

    // ------------------------------------------------------------------ helpers

    private static LlmModelSupport support(
            String streamUsageDisabled,
            boolean observationsEnabled,
            ObservationRegistry registry,
            List<DefaultAgentAdvisor> defaultAdvisors) {
        return support(streamUsageDisabled, observationsEnabled, registry, defaultAdvisors, true);
    }

    @SuppressWarnings("unchecked")
    private static LlmModelSupport support(
            String streamUsageDisabled,
            boolean observationsEnabled,
            ObservationRegistry registry,
            List<DefaultAgentAdvisor> defaultAdvisors,
            boolean nativeStructuredOutputs) {
        ObjectProvider<ObservationRegistry> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable(any())).thenReturn(registry);
        ObjectProvider<DefaultAgentAdvisor> advisorProvider = mock(ObjectProvider.class);
        when(advisorProvider.orderedStream()).thenAnswer(inv -> defaultAdvisors.stream());
        return new LlmModelSupport(
                registryProvider,
                advisorProvider,
                2,
                Duration.ofMillis(1),
                Duration.ofMillis(2),
                observationsEnabled,
                streamUsageDisabled,
                "low",
                16,
                Duration.ofMinutes(30),
                nativeStructuredOutputs);
    }
}
