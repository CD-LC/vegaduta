package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

class PromptDirectiveComposerTest {

    private static final List<ToolCallback> ONE_TOOL = List.of(mock(ToolCallback.class));

    @Test
    void composeEqualsTheThreeDirectivesJoinedAsAdjacentArguments() {
        PromptDirectiveComposer composer = PromptDirectiveComposer.standard(() -> true, () -> true);

        String composed = composer.compose(new PromptDirectiveComposer.Context(ONE_TOOL, true));

        assertThat(composed)
                .isEqualTo(
                        ChatPromptDirectives.joinMemoryBlocks(
                                ChatPromptDirectives.toolTrustDirective(ONE_TOOL),
                                ChatPromptDirectives.agenticCompletionDirective(ONE_TOOL),
                                ChatPromptDirectives.mcpUntrustedDataDirective(true)))
                .contains(ChatPromptDirectives.mcpUntrustedDataDirective(true));
    }

    @Test
    void gatesAreReadAtComposeTimeAndSilenceTheirSection() {
        boolean[] toolTrustOn = {true};
        PromptDirectiveComposer composer =
                PromptDirectiveComposer.standard(() -> toolTrustOn[0], () -> false);
        PromptDirectiveComposer.Context ctx = new PromptDirectiveComposer.Context(ONE_TOOL, true);

        assertThat(composer.compose(ctx)).isEqualTo(ChatPromptDirectives.toolTrustDirective(ONE_TOOL));
        toolTrustOn[0] = false;
        assertThat(composer.compose(ctx)).isNull();
    }

    @Test
    void noToolsAndNoMcpYieldsNull() {
        PromptDirectiveComposer composer = PromptDirectiveComposer.standard(() -> true, () -> true);

        assertThat(composer.compose(new PromptDirectiveComposer.Context(List.of(), false))).isNull();
    }
}
