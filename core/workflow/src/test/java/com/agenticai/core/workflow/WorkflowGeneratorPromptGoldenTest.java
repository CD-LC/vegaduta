package com.agenticai.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.turn.quality.AnswerConsistencyChecker;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.chat.ChatClientRegistry;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.workflow.expression.BuiltIn;
import com.agenticai.core.workflow.expression.BuiltIns;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Golden test of the migration of the workflow generator's meta-prompts onto the prompt registry. The fixtures in
 * {@code src/test/resources/golden/workflow-generator/} were written by the code that held these prompts as Java text
 * blocks, before it moved (the live list of built-in functions is the token {@code <<FUNCTIONS>>}); the text each key
 * resolves to by default must equal them byte for byte. The override test goes through the real
 * {@link WorkflowGraphGeneratorService#generate} path.
 */
class WorkflowGeneratorPromptGoldenTest {

    private static final String AGENTS = "<<AGENTS>>";

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static String golden(String name) {
        try (InputStream in =
                WorkflowGeneratorPromptGoldenTest.class.getResourceAsStream("/golden/workflow-generator/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            throw new AssertionError("missing golden fixture " + name, e);
        }
    }

    /** The live function list, computed independently of the production code. */
    private static String functions() {
        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (BuiltIn fn : BuiltIns.registry().values()) {
            byCategory.computeIfAbsent(fn.category(), k -> new ArrayList<>()).add(fn.name());
        }
        return byCategory.entrySet().stream()
                .map(e -> "  " + e.getKey() + ": " + String.join(", ", e.getValue()))
                .collect(Collectors.joining("\n"));
    }

    @Test
    void theShippedPromptsAreByteIdenticalToTheTextBlocksTheyReplaced() {
        assertThat(PromptResolver.defaultsOnly().catalog().problems()).isEmpty();
        assertThat(WorkflowGeneratorPrompts.buildSystemPrompt(AGENTS, true))
                .isEqualTo(golden("system.on.txt").replace("<<FUNCTIONS>>", functions()));
        assertThat(WorkflowGeneratorPrompts.buildSystemPrompt(AGENTS, false)).isEqualTo(golden("system.off.txt"));
        assertThat(WorkflowGeneratorPrompts.expressionSection())
                .isEqualTo(golden("expression-section.txt").replace("<<FUNCTIONS>>", functions()));
        assertThat(WorkflowGeneratorPrompts.refModeAddendum("<<KNOW>>")).isEqualTo(golden("refmode.txt"));
        assertThat(WorkflowGeneratorSupport.directAddendum(true)).isEqualTo(golden("direct.on.txt"));
        assertThat(WorkflowGeneratorSupport.directAddendum(false)).isEqualTo(golden("direct.off.txt"));
        assertThat(WorkflowGeneratorSupport.editSuffix("<<GRAPH>>")).isEqualTo(golden("edit.txt"));
    }

    @Test
    void anAgentValueWithBracesIsInsertedAsPlainText() {
        String agents = "- id=\"{agent_list}\" name=\"{expression_block}\"\n";
        String system = WorkflowGeneratorPrompts.buildSystemPrompt(agents, false);
        assertThat(system).contains(agents).doesNotContain("<<AGENTS>>");
    }

    @Test
    void aTenantOverrideChangesTheSystemPromptThatReachesTheModel() {
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", WorkflowGeneratorSupport.APPROVAL_RULE_KEY, "en", "\nCUSTOM APPROVAL RULE.\n", true, 1, "owner", Instant.now()));
        store.put(new PromptOverride(PromptScope.TENANT, "", WorkflowGeneratorSupport.EDIT_SUFFIX_KEY, "en", "\n\nEDIT: {current_graph}", true, 1, "owner", Instant.now()));
        resolver.evictAll();

        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(any(String.class)).user(any(String.class)).call().content())
                .thenReturn("{\"graph\":{\"nodes\":[{\"id\":\"t\",\"type\":\"trigger\",\"position\":{\"x\":80,\"y\":160},\"data\":{\"label\":\"Start\",\"config\":{\"channel\":\"WEBHOOK\"}}}],\"edges\":[]},\"notes\":[]}");
        WorkflowGraphGeneratorService service = service(chatClient);
        service.setPromptResolver(resolver);

        service.generate("Remind customers every Monday", ReasoningDepth.FAST, null);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt(), atLeastOnce()).system(system.capture());
        // The deep-stubbed chain keeps the matcher call made while stubbing (recorded with a null argument): the
        // first real system prompt is the first non-null value.
        String sent =
                system.getAllValues().stream()
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("the generator never sent a system prompt"));
        assertThat(sent).contains("CUSTOM APPROVAL RULE.").contains("SCHEDULE: a schedule is NOT a node");
        assertThat(sent).startsWith(WorkflowGeneratorPrompts.buildSystemPrompt("(none)", false).substring(0, 200));
    }

    @SuppressWarnings("unchecked")
    private WorkflowGraphGeneratorService service(ChatClient chatClient) {
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.plan("acme")).thenReturn(TenantPlan.FREE);
        when(limits.usesPlatformPool("acme", TenantPlan.FREE)).thenReturn(true);
        FreeTierChatClientService freeTier = mock(FreeTierChatClientService.class);
        when(freeTier.buildClientForFreeTenant(anyString(), any(TenantPlan.class), anyInt(), anyDouble(), anyDouble()))
                .thenReturn(chatClient);
        AgentService agents = mock(AgentService.class);
        when(agents.listAll()).thenReturn(List.of());
        ObjectProvider<WhatsAppDispatchService> whatsApp = mock(ObjectProvider.class);
        ObjectProvider<ApprovalPolicyService> policy = mock(ObjectProvider.class);
        when(whatsApp.getIfAvailable()).thenReturn(mock(WhatsAppDispatchService.class));
        when(policy.getIfAvailable()).thenReturn(mock(ApprovalPolicyService.class));
        return new WorkflowGraphGeneratorService(
                mock(ProviderCredentialResolver.class), mock(ChatClientRegistry.class), mock(LlmGatewayService.class),
                mock(ProviderModelCatalogService.class), agents, mock(OrchestratorService.class), new ObjectMapper(), limits,
                freeTier, mock(AnswerConsistencyChecker.class), mock(ReflexionService.class), mock(TaskPlanningService.class),
                "test-model", 4096, true, whatsApp, policy);
    }
}
