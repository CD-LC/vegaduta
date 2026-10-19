package com.agenticai.core.agents.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.guardrails.ContentGuardrailCheckResult;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

class ResponsePiiFlagAdvisorTest {

    private final ContentGuardrailEngine engine = mock(ContentGuardrailEngine.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ResponsePiiFlagAdvisor advisor;

    ResponsePiiFlagAdvisorTest() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meterRegistry);
        advisor = new ResponsePiiFlagAdvisor(engine, provider);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static ChatClientRequest request() {
        return new ChatClientRequest(new Prompt(List.of(new UserMessage("hi"))), Map.of());
    }

    private static CallAdvisorChain answering(String text) {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(ChatClientResponse.builder()
            .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
            .build());
        return chain;
    }

    private double detected() {
        return meterRegistry.counter("llm_gateway_response_pii_detected").count();
    }

    @Test
    void noTenantContextIsPurePassThrough() {
        ChatClientRequest request = request();
        CallAdvisorChain chain = answering("mail me at a@b.com");

        advisor.adviseCall(request, chain);

        verify(chain).nextCall(request);
        verify(engine, never()).evaluate(anyString(), any(), any());
    }

    @Test
    void flagsPiiInTheResponseForATenantCall() {
        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of()));
        when(engine.evaluate(anyString(), any(), any()))
            .thenReturn(ContentGuardrailCheckResult.blocked(ContentGuardrailType.PII, "email", 90));

        advisor.adviseCall(request(), answering("mail me at a@b.com"));

        assertEquals(1.0, detected());
    }

    @Test
    void flagsAtMostOnceWhenAttachedTwice() {
        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of()));
        when(engine.evaluate(anyString(), any(), any()))
            .thenReturn(ContentGuardrailCheckResult.blocked(ContentGuardrailType.PII, "email", 90));
        CallAdvisorChain inner = answering("mail me at a@b.com");
        CallAdvisorChain outer = mock(CallAdvisorChain.class);
        when(outer.nextCall(any())).thenAnswer(invocation -> advisor.adviseCall(invocation.getArgument(0), inner));

        advisor.adviseCall(request(), outer);

        verify(engine, times(1)).evaluate(anyString(), any(), any());
        assertEquals(1.0, detected());
    }
}
