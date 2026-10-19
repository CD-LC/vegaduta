package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@link AgentChatService#offersGenerationTool} - the live-stream half of the
 * fabricated-generation-URL guard ({@code bufferAndGuardGenerationClaims}) buffers a FAST-depth
 * reply (trading away live token streaming) whenever this turn offers a generation tool OR any
 * MCP-sourced tool at all - see the {@code bufferForGenerationSafety} comment in {@link
 * AgentChatService#chat} for why the original message-keyword heuristic ({@code
 * isGenerationRequest}, since removed) was dropped: it only ever looked at the CURRENT turn's
 * text, so a short multi-turn follow-up like "try again" slipped past it even when the actual
 * request was stated in an earlier turn.
 */
class AgentChatServiceGenerationBufferingTest {

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException("not exercised by this test");
            }
        };
    }

    @Test
    void offersGenerationToolDetectsDocumentImageAndVideo() {
        assertTrue(ChatPromptDirectives.offersGenerationTool(List.of(tool("generate_document"))));
        assertTrue(ChatPromptDirectives.offersGenerationTool(List.of(tool("generate_image"))));
        assertTrue(ChatPromptDirectives.offersGenerationTool(List.of(tool("generate_video"))));
    }

    @Test
    void offersGenerationToolFalseWhenNoneOffered() {
        assertFalse(ChatPromptDirectives.offersGenerationTool(List.of(tool("search_code"))));
        assertFalse(ChatPromptDirectives.offersGenerationTool(List.of()));
        assertFalse(ChatPromptDirectives.offersGenerationTool(null));
    }
}
