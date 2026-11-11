package com.agenticai.core.guardrails;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Shared fixture for the guardrail prompt-registry tests: an in-memory override store behind a real
 * resolver, tenant/agent override helpers and the mocks the call-path tests build on.
 */
abstract class GuardrailPromptRegistryFixture {

    static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID OTHER_AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    final DefaultPromptResolver resolver =
            new DefaultPromptResolver(
                    PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
        GuardrailFailMode.set(GuardrailFailMode.CLOSED);
    }

    void tenant() {
        TenantContext.set(TenantContext.of("t1", "owner", Set.of()));
    }

    void override(PromptScope scope, UUID agent, String key, String text) {
        store.put(
                new PromptOverride(
                        scope,
                        scope == PromptScope.AGENT ? agent.toString() : "",
                        key,
                        "en",
                        text,
                        true,
                        1,
                        "owner",
                        Instant.now()));
        resolver.evictAll();
    }

    static String shipped(String key, String... pairs) {
        return GuardrailPrompts.text(PromptResolver.defaultsOnly(), key, null, pairs);
    }

    ToolCallback tool(String name) {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(anyString())).thenReturn("{\"ok\":true}");
        return delegate;
    }

    GuardedToolCallback guarded(
            UUID agent,
            ToolDispatchGate gate,
            GuardrailService guardrail,
            ApprovalPolicyService approvalPolicy,
            HumanApprovalGateService gateService,
            ToolCallVerifierService verifier,
            boolean rigorous,
            ChatClient client) {
        return new GuardedToolCallback(
                        tool("send_invoice"),
                        new AtomicInteger(0),
                        10,
                        guardrail,
                        approvalPolicy,
                        gateService,
                        agent,
                        "s1",
                        verifier,
                        rigorous,
                        client,
                        "pay the invoice",
                        ApprovalGateMode.POLICY_DEFAULT,
                        null,
                        null,
                        null,
                        gate,
                        null)
                .withPrompts(resolver);
    }

    GuardrailService passThrough() {
        GuardrailService service = mock(GuardrailService.class);
        when(service.sanitizeToolOutput(anyString(), anyString(), any()))
                .thenAnswer(i -> i.getArgument(1));
        return service;
    }

    static HumanApprovalGateService approvalGate() {
        HumanApprovalGateService gate = mock(HumanApprovalGateService.class);
        ApprovalRequest pending = mock(ApprovalRequest.class);
        when(pending.getId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000beef"));
        when(gate.recordToolCallForApproval(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        any()))
                .thenReturn(pending);
        return gate;
    }

    static final class RecordingClient {
        final ChatClient client = mock(ChatClient.class);
        final ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        final ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);

        RecordingClient(String answer) {
            ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
            when(client.prompt()).thenReturn(spec);
            when(spec.system(anyString())).thenReturn(spec);
            when(spec.user(anyString())).thenReturn(spec);
            when(spec.options(any(ChatOptions.class))).thenReturn(spec);
            when(spec.call()).thenReturn(call);
            when(call.content()).thenReturn(answer);
        }

        String systemText() {
            verify(spec).system(system.capture());
            return system.getValue();
        }
    }

    static ContentGuardrailEngine engineOf(ContentGuardrail... guardrails) {
        List<ContentGuardrail> list = List.of(guardrails);
        org.springframework.beans.factory.ObjectProvider<List<ContentGuardrail>> provider =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public List<ContentGuardrail> getIfAvailable() {
                        return list;
                    }
                };
        return new ContentGuardrailEngine(provider);
    }
}
