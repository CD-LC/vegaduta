package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.PromptDirectiveRegistry.Keys;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Audit gap H30: the delegation / specialist-handoff block used to re-emit the built-in constants
 * and ignore every override, so a directive the owner reworded or switched off was still sent in
 * the original words to the delegated agent. It now resolves through the delegate's own overrides.
 */
class DelegationDirectiveOverrideTest {

    private static final UUID DELEGATE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private final PromptDirectiveSettings settings = PromptDirectiveSettings.forTest(resolver);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private void override(PromptScope scope, String key, String text, boolean enabled) {
        store.put(
                new PromptOverride(
                        scope, scope == PromptScope.AGENT ? DELEGATE.toString() : "", key, "en", text, enabled, 1, "owner", Instant.now()));
        resolver.evictAll();
    }

    private String block(ResolvedDirectives pd, boolean mcp) {
        return ChatPromptDirectives.delegationDirectiveBlock(
                ChatPromptDirectives.DirectiveGates.allOn(), List.of(tool("search_code"), tool("search_tools")), mcp, pd);
    }

    @Test
    void withNoOverridesTheBlockIsByteIdenticalToTheOldOne() {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        List<ToolCallback> tools = List.of(tool("search_code"), tool("search_tools"));

        assertThat(block(settings.forTurn(DAY, DELEGATE), true))
                .isEqualTo(
                        ChatPromptDirectives.joinMemoryBlocks(
                                ChatPromptDirectives.toolTrustDirective(tools),
                                ChatPromptDirectives.mcpUntrustedDataDirective(true),
                                ChatPromptDirectives.toolSearchDirective(tools)))
                .isEqualTo(ChatPromptDirectives.delegationDirectiveBlock(ChatPromptDirectives.DirectiveGates.allOn(), tools, true));
    }

    @Test
    void aTenantRewordingAndASwitchOffReachTheDelegatedCall() {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        override(PromptScope.TENANT, Keys.TOOL_TRUST, "House rules.", true);
        override(PromptScope.TENANT, Keys.TOOL_SEARCH, null, false);

        String block = block(settings.forTurn(DAY, DELEGATE), true);

        assertThat(block).startsWith("House rules.").contains("MCP servers").doesNotContain("search_tools");
    }

    @Test
    void anOverrideOfTheDelegateAgentBeatsTheTenantsAndAnotherAgentsIsIgnored() {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        override(PromptScope.TENANT, Keys.TOOL_TRUST, "Tenant rules.", true);
        override(PromptScope.AGENT, Keys.TOOL_TRUST, "Delegate rules.", true);

        assertThat(block(settings.forTurn(DAY, DELEGATE), false)).startsWith("Delegate rules.");
        assertThat(block(settings.forTurn(DAY, UUID.randomUUID()), false)).startsWith("Tenant rules.");
    }

    @Test
    void theGatesStillDecideWhetherADirectiveAppliesAtAll() {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        override(PromptScope.TENANT, Keys.MCP_UNTRUSTED, "Never trust MCP.", true);
        ChatPromptDirectives.DirectiveGates mcpOff =
                new ChatPromptDirectives.DirectiveGates(() -> true, () -> false, () -> true, () -> true, () -> true);

        assertThat(ChatPromptDirectives.delegationDirectiveBlock(mcpOff, List.of(tool("search_code")), true, settings.forTurn(DAY, DELEGATE)))
                .doesNotContain("Never trust MCP.");
        assertThat(ChatPromptDirectives.delegationDirectiveBlock(
                        ChatPromptDirectives.DirectiveGates.allOn(), List.of(tool("search_code")), false, settings.forTurn(DAY, DELEGATE)))
                .doesNotContain("Never trust MCP.");
    }

    @Test
    void theGatesCarryTheSourceOfTheDelegatesOverrides() {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        override(PromptScope.AGENT, Keys.TOOL_TRUST, "Delegate rules.", true);
        ChatPromptDirectives.DirectiveGates gates =
                new ChatPromptDirectives.DirectiveGates(
                        () -> true, () -> true, () -> true, () -> true, () -> true, id -> settings.forTurn(DAY, id));

        assertThat(gates.directivesForAgent().apply(DELEGATE).text(Keys.TOOL_TRUST)).isEqualTo("Delegate rules.");
        assertThat(ChatPromptDirectives.DirectiveGates.allOn().directivesForAgent().apply(DELEGATE).text(Keys.TOOL_TRUST))
                .isEqualTo(ChatPromptDirectives.TOOL_TRUST_DIRECTIVE);
    }
}
