package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.turn.PromptDirectiveRegistry.Keys;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.prompts.PromptSource;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

class ResolvedDirectivesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final List<ToolCallback> ONE_TOOL = List.of(mock(ToolCallback.class));

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(
                    PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private final PromptDirectiveSettings settings = PromptDirectiveSettings.forTest(resolver);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void tenant(String id) {
        TenantContext.set(TenantContext.of(id, "owner", Set.of()));
    }

    private void override(PromptScope scope, String key, String text, boolean enabled) {
        store.put(
                new PromptOverride(
                        scope,
                        scope == PromptScope.AGENT ? AGENT.toString() : "",
                        key,
                        "en",
                        text,
                        enabled,
                        1,
                        "owner",
                        Instant.now()));
        resolver.evictAll();
    }

    @Test
    void defaultsPassEveryDirectiveThroughUnchangedAndAddNothing() {
        ResolvedDirectives none = ResolvedDirectives.DEFAULTS;

        for (PromptDirectiveRegistry.Entry e : PromptDirectiveRegistry.all()) {
            for (String key : e.promptKeys()) {
                if (e.overridable()) {
                    assertThat(none.text(key)).as(key).isNotBlank();
                } else {
                    assertThat(none.text(key)).as(key).isNull();
                }
            }
            assertThat(none.text(null)).isNull();
        }
        assertThat(none.text(Keys.TOOL_TRUST)).isEqualTo(ChatPromptDirectives.TOOL_TRUST_DIRECTIVE);
        assertThat(none.customInstructionsBlock()).isNull();
    }

    @Test
    void registryDefaultsAreTheTextTheHardcodedDirectivesSend() {
        PromptDirectiveRegistry.Entry date =
                PromptDirectiveRegistry.find("current_date").orElseThrow();
        assertThat(date.defaultText().replace("{date}", DAY.toString()))
                .isEqualTo(ChatPromptDirectives.currentDateDirective(DAY));
        assertThat(ChatPromptDirectives.currentDateDirective(DAY))
                .isEqualTo(
                        "Today's date is 2026-10-19 (UTC). Use it for anything time-relative "
                                + "(today, latest, this week) and never guess a different date.");
        assertThat(text("tool_trust")).isEqualTo(ChatPromptDirectives.toolTrustDirective(ONE_TOOL));
        assertThat(text("mcp_untrusted"))
                .isEqualTo(ChatPromptDirectives.mcpUntrustedDataDirective(true));
        assertThat(text("artifacts"))
                .isEqualTo(
                        ChatPromptDirectives.artifactsDirective(true, true, "", UUID.randomUUID()));
        assertThat(text("agentic_completion")).isEmpty();
        assertThat(ChatPromptDirectives.agenticCompletionDirective(ONE_TOOL)).isNull();
        assertThat(text("media_honesty")).isEmpty();
        assertThat(ChatPromptDirectives.mediaGenerationHonestyDirective(ONE_TOOL)).isNull();
        assertThat(PromptDirectiveRegistry.all())
                .extracting(PromptDirectiveRegistry.Entry::key)
                .containsExactly(
                        "current_date",
                        "memory_usage",
                        "media_honesty",
                        "tool_trust",
                        "agentic_completion",
                        "mcp_untrusted",
                        "tool_search",
                        "scratchpad",
                        "context_compaction",
                        "browser_v2",
                        "artifacts");
        assertThat(
                        PromptDirectiveRegistry.all().stream()
                                .filter(PromptDirectiveRegistry.Entry::securityCritical)
                                .map(PromptDirectiveRegistry.Entry::key))
                .containsExactly("tool_trust", "mcp_untrusted");
        assertThat(PromptDirectiveRegistry.find("tool_trust").orElseThrow().promptKeys())
                .containsExactly("directive.tool_trust", "directive.tool_trust.compact");
        assertThat(PromptDirectiveRegistry.find("context_compaction").orElseThrow().promptKeys())
                .containsExactly(
                        "directive.context_compaction", "directive.context_compaction.no_restore");
    }

    private static String text(String key) {
        return PromptDirectiveRegistry.find(key).orElseThrow().defaultText();
    }

    @Test
    void resolutionOrderIsDisabledThenOverrideThenDefault() {
        tenant("a");
        override(PromptScope.TENANT, Keys.MEMORY_USAGE, "ignored when disabled", false);
        override(PromptScope.TENANT, Keys.TOOL_SEARCH, "Search first.", true);
        override(PromptScope.TENANT, Keys.BROWSER_V2, "   ", true);
        ResolvedDirectives pd = settings.forToday(DAY);

        assertThat(pd.text(Keys.MEMORY_USAGE)).isNull();
        assertThat(pd.text(Keys.TOOL_SEARCH)).isEqualTo("Search first.");
        assertThat(pd.text(Keys.BROWSER_V2)).isEqualTo(ChatPromptDirectives.BROWSER_V2_DIRECTIVE);
        assertThat(pd.text(Keys.ARTIFACTS)).isEqualTo(ArtifactsDirective.TEXT);
    }

    @Test
    void anOverrideNeverResurrectsADirectiveThePlatformDoesNotSendThisTurn() {
        tenant("a");
        override(PromptScope.TENANT, Keys.TOOL_SEARCH, "Search first.", true);

        // The assembler passes no key for a directive that does not apply this turn.
        assertThat(settings.forToday(DAY).text(DirectiveKeys.toolSearchKey(List.of()))).isNull();
    }

    @Test
    void currentDateGetsThePlaceholderSubstitutedInTheDefaultAndInAnOverride() {
        tenant("a");
        assertThat(settings.forToday(DAY).text(Keys.CURRENT_DATE))
                .isEqualTo(ChatPromptDirectives.currentDateDirective(DAY));
        override(PromptScope.TENANT, Keys.CURRENT_DATE, "It is {date} today.", true);

        assertThat(settings.forToday(DAY).text(Keys.CURRENT_DATE))
                .isEqualTo("It is 2026-10-19 today.");
    }

    @Test
    void customInstructionsOnlyWhenEnabledAndNotBlank() {
        tenant("a");
        override(PromptScope.TENANT, Keys.CUSTOM_INSTRUCTIONS, "  Be brief.  ", true);
        assertThat(settings.forToday(DAY).customInstructionsBlock()).isEqualTo("Be brief.");
        override(PromptScope.TENANT, Keys.CUSTOM_INSTRUCTIONS, "Be brief.", false);
        assertThat(settings.forToday(DAY).customInstructionsBlock()).isNull();
        override(PromptScope.TENANT, Keys.CUSTOM_INSTRUCTIONS, " ", true);
        assertThat(settings.forToday(DAY).customInstructionsBlock()).isNull();
    }

    @Test
    void anAgentOverrideBeatsTheTenantsForThatAgentOnly() {
        tenant("a");
        override(PromptScope.TENANT, Keys.TOOL_SEARCH, "Tenant search rule.", true);
        override(PromptScope.AGENT, Keys.TOOL_SEARCH, "Agent search rule.", true);

        assertThat(settings.forTurn(DAY, AGENT).text(Keys.TOOL_SEARCH))
                .isEqualTo("Agent search rule.");
        assertThat(settings.forTurn(DAY, UUID.randomUUID()).text(Keys.TOOL_SEARCH))
                .isEqualTo("Tenant search rule.");
        assertThat(settings.forToday(DAY).text(Keys.TOOL_SEARCH)).isEqualTo("Tenant search rule.");
    }

    @Test
    void recordingRemembersWhichKeyScopeAndVersionProducedEachBlock() {
        tenant("a");
        override(PromptScope.AGENT, Keys.TOOL_SEARCH, "Agent search rule.", true);
        ResolvedDirectives pd = settings.forTurn(DAY, AGENT).recording();

        pd.text(Keys.TOOL_SEARCH);
        pd.text(Keys.BROWSER_V2);
        pd.text(null);

        assertThat(pd.trace())
                .extracting(r -> r.key() + ":" + r.source())
                .containsExactly(
                        "directive.tool_search:" + PromptSource.AGENT,
                        "directive.browser_v2:" + PromptSource.DEFAULT);
        assertThat(pd.trace().get(0).scope()).isEqualTo(PromptScope.AGENT);
        assertThat(pd.trace().get(0).version()).isEqualTo(1);
        assertThat(ResolvedDirectives.DEFAULTS.trace()).isEmpty();
    }

    @Test
    void composerWithDefaultsIsByteIdenticalToTheOldCompositionAndHonoursOverrides() {
        PromptDirectiveComposer composer = PromptDirectiveComposer.standard(() -> true, () -> true);
        String old =
                ChatPromptDirectives.joinMemoryBlocks(
                        ChatPromptDirectives.toolTrustDirective(ONE_TOOL, false),
                        ChatPromptDirectives.agenticCompletionDirective(ONE_TOOL),
                        ChatPromptDirectives.mcpUntrustedDataDirective(true));

        assertThat(
                        composer.compose(
                                new PromptDirectiveComposer.Context(
                                        ONE_TOOL, true, false, ResolvedDirectives.DEFAULTS)))
                .isEqualTo(old);

        tenant("a");
        override(PromptScope.TENANT, Keys.TOOL_TRUST, "House rules.", true);
        override(PromptScope.TENANT, Keys.TOOL_TRUST_COMPACT, "Short house rules.", true);
        override(PromptScope.TENANT, Keys.MCP_UNTRUSTED, null, false);
        ResolvedDirectives pd = settings.forToday(DAY);
        assertThat(composer.compose(new PromptDirectiveComposer.Context(ONE_TOOL, true, false, pd)))
                .isEqualTo("House rules.");
        // Small-model (compact) mode is a key of its own: the full wording's override does not leak
        // into it.
        assertThat(composer.compose(new PromptDirectiveComposer.Context(ONE_TOOL, true, true, pd)))
                .isEqualTo("Short house rules.");
        // A turn without tools sends no tool_trust, and the override does not conjure one.
        assertThat(
                        composer.compose(
                                new PromptDirectiveComposer.Context(List.of(), false, false, pd)))
                .isNull();
    }

    @Test
    void theCompactWordingFallsBackToItsOwnDefaultWhenOnlyTheFullOneIsOverridden() {
        tenant("a");
        override(PromptScope.TENANT, Keys.TOOL_TRUST, "House rules.", true);
        PromptDirectiveComposer composer =
                PromptDirectiveComposer.standard(() -> true, () -> false);

        assertThat(
                        composer.compose(
                                new PromptDirectiveComposer.Context(
                                        ONE_TOOL, false, true, settings.forToday(DAY))))
                .isEqualTo(ChatPromptDirectives.TOOL_TRUST_DIRECTIVE_COMPACT);
    }

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void compactionHasASeparateKeyWhenTheRestoreToolIsNotOffered() {
        assertThat(DirectiveKeys.contextCompactionKey(true, List.of(tool("search_code"))))
                .isEqualTo(Keys.CONTEXT_COMPACTION_NO_RESTORE);
        assertThat(DirectiveKeys.contextCompactionKey(true, List.of(tool("restore_tool_result"))))
                .isEqualTo(Keys.CONTEXT_COMPACTION);
        assertThat(DirectiveKeys.contextCompactionKey(false, List.of(tool("search_code"))))
                .isNull();
        assertThat(DirectiveKeys.contextCompactionKey(true, List.of())).isNull();
        assertThat(
                        ChatPromptDirectives.contextCompactionDirective(
                                true, List.of(tool("search_code"))))
                .isEqualTo(ChatPromptDirectives.CONTEXT_COMPACTION_DIRECTIVE);
    }
}
