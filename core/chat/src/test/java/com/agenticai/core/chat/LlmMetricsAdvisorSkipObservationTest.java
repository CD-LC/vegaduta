package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A platform probe (the model warm-up) marks its request with {@link
 * LlmMetricsAdvisor#SKIP_OBSERVATION_PARAM}: it runs on a member's request thread, so without the
 * mark its one-token call was booked as an {@code llm_call_log} row in that member's tenant.
 */
class LlmMetricsAdvisorSkipObservationTest {

    private final CallAdvisorChain chain = mock(CallAdvisorChain.class);
    private final LlmCallLogService logService = mock(LlmCallLogService.class);

    private LlmMetricsAdvisor advisor() {
        return new LlmMetricsAdvisor(
                new LlmCallMetricsService(),
                logService,
                ProviderName.PLATFORM_HOSTED,
                "llama3.2",
                new SimpleMeterRegistry(),
                new LlmPricingCatalog());
    }

    private ChatClientResponse answer(ChatClientRequest request) {
        ChatResponse response =
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage("ok"))),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
        ChatClientResponse out = new ChatClientResponse(response, Map.of());
        when(chain.nextCall(request)).thenReturn(out);
        return out;
    }

    @Test
    void aProbeCallIsAnsweredButNeverRecorded() {
        ChatClientRequest request =
                new ChatClientRequest(
                        new Prompt("hi"), Map.of(LlmMetricsAdvisor.SKIP_OBSERVATION_PARAM, true));
        ChatClientResponse expected = answer(request);

        assertEquals(expected, advisor().adviseCall(request, chain));

        verify(logService, never())
                .record(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void anOrdinaryCallIsStillRecorded() {
        ChatClientRequest request = new ChatClientRequest(new Prompt("hi"), Map.of());
        answer(request);

        advisor().adviseCall(request, chain);

        verify(logService)
                .record(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }
}
