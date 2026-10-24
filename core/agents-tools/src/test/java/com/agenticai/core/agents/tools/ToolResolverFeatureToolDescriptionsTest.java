package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.RememberFactToolCallback;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.prompts.tool.ToolPromptKeys;
import com.agenticai.core.prompts.tool.ToolPromptSchemas;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The real path for the tools migrated in package 4e-2: {@link ToolResolver#resolve} puts the
 * registry's wording on a REAL {@code remember_fact} tool (core/agents-turn), on both the buffered
 * and the untrimmed list, for the agent or tenant the override is for, and nobody else.
 */
class ToolResolverFeatureToolDescriptionsTest {

    private static final String KEY = "tool.description.remember_fact";

    private final UUID agentId = UUID.randomUUID();
    private final PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver prompts = new DefaultPromptResolver(catalog, store, new PromptProperties());
    private final ToolCallback remember = new RememberFactToolCallback(null, agentId, "subject");

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-a", "admin@a", Set.of("ROLE_tenant-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ToolResolver resolver() {
        AgentService agentService = mock(AgentService.class);
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of());
        ToolRelevanceSelector selector = mock(ToolRelevanceSelector.class);
        when(selector.select(any(), any(), any(), any())).thenAnswer(inv -> inv.<List<ToolCallback>>getArgument(1));
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformIssueService> issues = mock(ObjectProvider.class);
        return new ToolResolver(
                List.of(ToolSource.of("builtin", ToolSource.Kind.BUILT_IN, ctx -> List.of(remember))),
                agentService,
                selector,
                mock(AgentToolGuard.class),
                mock(AgentIntegrationToolResolver.class),
                issues,
                new ToolSearchDeferral(),
                ToolDescriptionResolver.of(prompts),
                40);
    }

    private ToolResolutionContext ctx(UUID agent) {
        return new ToolResolutionContext(
                agent, "s1", "subject", new AtomicInteger(0), "hello", null, false, true, true, List.of());
    }

    private void override(PromptScope scope, UUID agent, String key, String text) {
        store.put(
                new PromptOverride(
                        scope, scope == PromptScope.AGENT ? agent.toString() : "", key, "en", text, true, 1, "t", Instant.now()));
        prompts.evictAll();
    }

    private static Map<String, String> descriptions(ResolvedToolList list) {
        return list.stream()
                .collect(Collectors.toMap(t -> t.getToolDefinition().name(), t -> t.getToolDefinition().description()));
    }

    @Test
    void byDefaultTheModelSeesExactlyWhatTheCodeBuilt() {
        ToolCallback listed = resolver().resolve(ctx(agentId), true).get(0);
        assertThat(listed.getToolDefinition().description()).isEqualTo(remember.getToolDefinition().description());
        assertThat(listed.getToolDefinition().inputSchema()).isEqualTo(remember.getToolDefinition().inputSchema());
    }

    @Test
    void anAgentOverrideReachesBothListsAndOnlyThatAgent() {
        override(PromptScope.AGENT, agentId, KEY, "Agent-specific remember wording.");
        ToolResolver resolver = resolver();

        assertThat(descriptions(resolver.resolve(ctx(agentId), true))).containsEntry("remember_fact", "Agent-specific remember wording.");
        assertThat(descriptions(resolver.resolve(ctx(agentId), false))).containsEntry("remember_fact", "Agent-specific remember wording.");
        assertThat(descriptions(resolver.resolve(ctx(UUID.randomUUID()), true)))
                .containsEntry("remember_fact", remember.getToolDefinition().description());
    }

    @Test
    void aTenantOverrideOfTheParameterTextReachesEveryAgentWithoutChangingTheShape() {
        override(PromptScope.TENANT, null, ToolPromptKeys.param("remember_fact", "fact"), "Tenant wording of the fact.");

        ToolCallback listed = resolver().resolve(ctx(UUID.randomUUID()), true).get(0);
        String schema = listed.getToolDefinition().inputSchema();

        assertThat(ToolPromptSchemas.byPath(schema)).containsEntry("fact", "Tenant wording of the fact.");
        assertThat(ToolPromptSchemas.sameShape(remember.getToolDefinition().inputSchema(), schema)).isTrue();
        assertThat(listed.getToolDefinition().name()).isEqualTo("remember_fact");
    }
}
