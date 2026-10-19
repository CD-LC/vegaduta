package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.turn.ResolvedDirectives.Setting;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

class ResolvedDirectivesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);
    private static final List<ToolCallback> ONE_TOOL = List.of(mock(ToolCallback.class));

    private static ResolvedDirectives with(Map<String, Setting> settings) {
        return new ResolvedDirectives(settings, DAY);
    }

    @Test
    void defaultsPassEveryBlockThroughUnchangedAndAddNothing() {
        ResolvedDirectives none = ResolvedDirectives.DEFAULTS;

        for (PromptDirectiveRegistry.Entry e : PromptDirectiveRegistry.all()) {
            assertThat(none.resolve(e.key(), e.defaultText())).isEqualTo(e.defaultText());
            assertThat(none.resolve(e.key(), null)).isNull();
        }
        assertThat(none.customInstructionsBlock()).isNull();
    }

    @Test
    void registryDefaultsAreTheTextTheHardcodedDirectivesSend() {
        PromptDirectiveRegistry.Entry date = PromptDirectiveRegistry.find("current_date").orElseThrow();
        assertThat(date.defaultText().replace("{date}", DAY.toString()))
                .isEqualTo(ChatPromptDirectives.currentDateDirective(DAY));
        assertThat(ChatPromptDirectives.currentDateDirective(DAY))
                .isEqualTo(
                        "Today's date is 2026-10-19 (UTC). Use it for anything time-relative "
                                + "(today, latest, this week) and never guess a different date.");
        assertThat(text("tool_trust")).isEqualTo(ChatPromptDirectives.toolTrustDirective(ONE_TOOL));
        assertThat(text("mcp_untrusted")).isEqualTo(ChatPromptDirectives.mcpUntrustedDataDirective(true));
        assertThat(text("artifacts")).isEqualTo(ChatPromptDirectives.artifactsDirective(true, true, "", java.util.UUID.randomUUID()));
        assertThat(text("agentic_completion")).isEmpty();
        assertThat(ChatPromptDirectives.agenticCompletionDirective(ONE_TOOL)).isNull();
        assertThat(text("media_honesty")).isEmpty();
        assertThat(ChatPromptDirectives.mediaGenerationHonestyDirective(ONE_TOOL)).isNull();
        assertThat(PromptDirectiveRegistry.all()).extracting(PromptDirectiveRegistry.Entry::key)
                .containsExactly("current_date", "memory_usage", "media_honesty", "tool_trust",
                        "agentic_completion", "mcp_untrusted", "tool_search", "context_compaction",
                        "browser_v2", "artifacts");
        assertThat(PromptDirectiveRegistry.all().stream().filter(PromptDirectiveRegistry.Entry::securityCritical)
                        .map(PromptDirectiveRegistry.Entry::key))
                .containsExactly("tool_trust", "mcp_untrusted");
    }

    private static String text(String key) {
        return PromptDirectiveRegistry.find(key).orElseThrow().defaultText();
    }

    @Test
    void resolutionOrderIsDisabledThenOverrideThenDefault() {
        ResolvedDirectives pd =
                with(
                        Map.of(
                                "memory_usage", new Setting(false, "ignored when disabled"),
                                "tool_search", new Setting(true, "Search first."),
                                "browser_v2", new Setting(true, "   ")));

        assertThat(pd.resolve("memory_usage", "built-in")).isNull();
        assertThat(pd.resolve("tool_search", "built-in")).isEqualTo("Search first.");
        assertThat(pd.resolve("browser_v2", "built-in")).isEqualTo("built-in");
        assertThat(pd.resolve("artifacts", "built-in")).isEqualTo("built-in");
    }

    @Test
    void anOverrideNeverResurrectsADirectiveThePlatformDoesNotSendThisTurn() {
        ResolvedDirectives pd = with(Map.of("tool_search", new Setting(true, "Search first.")));

        assertThat(pd.resolve("tool_search", null)).isNull();
    }

    @Test
    void currentDateOverrideGetsThePlaceholderSubstituted() {
        ResolvedDirectives pd = with(Map.of("current_date", new Setting(true, "It is {date} today.")));

        assertThat(pd.resolve("current_date", "built-in")).isEqualTo("It is 2026-10-19 today.");
    }

    @Test
    void customInstructionsOnlyWhenEnabledAndNotBlank() {
        assertThat(with(Map.of("custom_instructions", new Setting(true, "  Be brief.  ")))
                        .customInstructionsBlock())
                .isEqualTo("Be brief.");
        assertThat(with(Map.of("custom_instructions", new Setting(false, "Be brief.")))
                        .customInstructionsBlock())
                .isNull();
        assertThat(with(Map.of("custom_instructions", new Setting(true, " ")))
                        .customInstructionsBlock())
                .isNull();
    }

    @Test
    void composerWithDefaultsIsByteIdenticalToTheOldCompositionAndHonoursOverrides() {
        PromptDirectiveComposer composer = PromptDirectiveComposer.standard(() -> true, () -> true);
        String old =
                ChatPromptDirectives.joinMemoryBlocks(
                        ChatPromptDirectives.toolTrustDirective(ONE_TOOL, false),
                        ChatPromptDirectives.agenticCompletionDirective(ONE_TOOL),
                        ChatPromptDirectives.mcpUntrustedDataDirective(true));

        assertThat(composer.compose(new PromptDirectiveComposer.Context(ONE_TOOL, true, false, ResolvedDirectives.DEFAULTS)))
                .isEqualTo(old);

        ResolvedDirectives pd =
                with(
                        Map.of(
                                "tool_trust", new Setting(true, "House rules."),
                                "mcp_untrusted", new Setting(false, null)));
        // Small-model (compact) mode takes the override too.
        assertThat(composer.compose(new PromptDirectiveComposer.Context(ONE_TOOL, true, true, pd)))
                .isEqualTo("House rules.");
        // A turn without tools sends no tool_trust, and the override does not conjure one.
        assertThat(composer.compose(new PromptDirectiveComposer.Context(List.of(), false, false, pd)))
                .isNull();
    }
}
