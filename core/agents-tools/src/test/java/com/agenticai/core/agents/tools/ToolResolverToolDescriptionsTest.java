package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.tool.ToolPromptGolden;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The real path: {@link ToolResolver#resolve} puts the registry's wording on the tool list of a
 * turn, for a REAL built-in tool definition (data_table_query), on the paths that go through it.
 */
class ToolResolverToolDescriptionsTest {

    private static final String TOOL = "data_table_query";
    private static final String KEY = "tool.description." + TOOL;

    private final UUID agentId = UUID.randomUUID();
    private final PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver prompts = new DefaultPromptResolver(catalog, store, new PromptProperties());
    private ToolPromptGolden.Probed real;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.set(TenantContext.of("tenant-a", "admin@a", Set.of("ROLE_tenant-admin")));
        real = ToolPromptGolden.probe(Class.forName("com.agenticai.core.agents.tools.tables.DataTableQueryToolCallback"));
        assertThat(real.name()).isEqualTo(TOOL);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ToolCallback builtIn() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(real.name())
                        .description(real.description())
                        .inputSchema(real.inputSchema())
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "ran";
            }
        };
    }

    private ToolResolver resolver() {
        AgentService agentService = mock(AgentService.class);
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of());
        ToolRelevanceSelector selector = mock(ToolRelevanceSelector.class);
        when(selector.select(any(), any(), any(), any())).thenAnswer(inv -> inv.<List<ToolCallback>>getArgument(1));
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformIssueService> issues = mock(ObjectProvider.class);
        return new ToolResolver(
                List.of(ToolSource.of("builtin", ToolSource.Kind.BUILT_IN, ctx -> List.of(builtIn()))),
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

    private static ToolDefinition only(ResolvedToolList list) {
        assertThat(list).hasSize(1);
        return list.get(0).getToolDefinition();
    }

    @Test
    void byDefaultTheModelSeesExactlyWhatTheCodeBuilt() {
        ToolDefinition def = only(resolver().resolve(ctx(agentId), true));
        assertThat(def.description()).isEqualTo(real.description());
        assertThat(def.inputSchema()).isEqualTo(real.inputSchema());
    }

    @Test
    void anAgentOverrideReachesBothTheBufferedAndTheUntrimmedListsAndOnlyThatAgent() {
        override(PromptScope.AGENT, agentId, KEY, "Agent-specific table query wording.");
        ToolResolver resolver = resolver();

        assertThat(only(resolver.resolve(ctx(agentId), true)).description()).isEqualTo("Agent-specific table query wording.");
        assertThat(only(resolver.resolve(ctx(agentId), false)).description()).isEqualTo("Agent-specific table query wording.");
        assertThat(only(resolver.resolve(ctx(UUID.randomUUID()), true)).description()).isEqualTo(real.description());
    }

    @Test
    void aTenantOverrideOfAParameterAppliesToEveryAgentOfTheTenant() {
        String firstParam = com.agenticai.core.prompts.tool.ToolPromptSchemas.params(real.inputSchema()).get(0).path();
        String paramKey = com.agenticai.core.prompts.tool.ToolPromptKeys.param(TOOL, firstParam);
        override(PromptScope.TENANT, null, paramKey, "Tenant wording of the first parameter.");

        ToolDefinition def = only(resolver().resolve(ctx(UUID.randomUUID()), true));

        assertThat(com.agenticai.core.prompts.tool.ToolPromptSchemas.byPath(def.inputSchema()))
                .containsEntry(firstParam, "Tenant wording of the first parameter.");
        assertThat(def.description()).isEqualTo(real.description());
        assertThat(com.agenticai.core.prompts.tool.ToolPromptSchemas.sameShape(real.inputSchema(), def.inputSchema())).isTrue();
    }

    @Test
    void theOverrideHasNoEffectOnWhatTheToolDoes() {
        override(PromptScope.PLATFORM, null, KEY, "Reworded.");
        ToolCallback tool = resolver().resolve(ctx(agentId), true).get(0);
        assertThat(tool.getToolDefinition().name()).isEqualTo(TOOL);
        assertThat(tool.call("{}")).isEqualTo("ran");
    }
}
