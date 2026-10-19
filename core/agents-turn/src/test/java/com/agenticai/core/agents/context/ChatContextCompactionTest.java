package com.agenticai.core.agents.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.tools.context.RestoreToolResultToolCallback;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The Spring-facing collaborator: the master switch (flag off = nothing at all), window lookup, the
 * restore tool.
 */
class ChatContextCompactionTest {

    private static final UUID AGENT = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private static ChatContextCompaction compaction(
            boolean enabled,
            boolean restoreToolEnabled,
            ProviderModelCatalogService catalog,
            ToolCallLogService logs) {
        return new ChatContextCompaction(
                providerOf((LlmCompactionSummarizer) null),
                providerOf(catalog),
                providerOf(logs),
                providerOf((io.micrometer.core.instrument.MeterRegistry) null),
                enabled,
                0.6,
                0.85,
                2,
                restoreToolEnabled,
                2,
                64_000,
                100,
                3);
    }

    private static ProviderModelCatalogService catalogReturning(Integer window) {
        ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
        ProviderModelResponse response =
                new ProviderModelResponse("m", "M", window, false, false, false, "summary");
        when(catalog.findModel(ProviderName.ANTHROPIC, "m")).thenReturn(Optional.of(response));
        return catalog;
    }

    private static ToolCallback tool(String name) {
        return new RestoreToolResultToolCallback(mock(ToolCallLogService.class), AGENT, "s") {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(name)
                        .description("d")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }
        };
    }

    private static List<Message> toolRun(int rounds) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("sys"));
        messages.add(new UserMessage("go"));
        for (int i = 1; i <= rounds; i++) {
            messages.add(
                    AssistantMessage.builder()
                            .content("")
                            .toolCalls(
                                    List.of(
                                            new AssistantMessage.ToolCall(
                                                    "c" + i,
                                                    "function",
                                                    "search",
                                                    "{\"i\":" + i + "}")))
                            .build());
            messages.add(
                    ToolResponseMessage.builder()
                            .responses(
                                    List.of(
                                            new ToolResponseMessage.ToolResponse(
                                                    "c" + i, "search", "x".repeat(8_000))))
                            .build());
        }
        return messages;
    }

    private static String clearedPlaceholder(List<Message> out) {
        return ((ToolResponseMessage) out.get(3)).getResponses().get(0).responseData();
    }

    // ---------------------------------------------------------------- flag off

    @Test
    void flagOffOpensNoRunOffersNoToolAndIsDisabled() {
        ChatContextCompaction off =
                compaction(false, true, catalogReturning(10_000), mock(ToolCallLogService.class));

        assertEquals(false, off.isEnabled());
        assertNull(off.openRun(ProviderName.ANTHROPIC, "m", AGENT, "s", List.of(), null));
        assertTrue(off.restoreToolsFor(AGENT, "s").isEmpty());
    }

    @Test
    void theShippedDefaultIsOff() {
        // The @Value default repeated in code is what a slim context sees; application.yml says the
        // same.
        ChatContextCompaction viaDefaults = compaction(false, true, null, null);

        assertEquals(false, viaDefaults.isEnabled());
    }

    // ---------------------------------------------------------------- restore tool

    @Test
    void theRestoreToolIsOfferedOnlyWhenCompactionAndTheRestoreSwitchAreOn() {
        ToolCallLogService logs = mock(ToolCallLogService.class);

        List<ToolCallback> on = compaction(true, true, null, logs).restoreToolsFor(AGENT, "s");

        assertEquals(1, on.size());
        assertEquals("restore_tool_result", on.get(0).getToolDefinition().name());
        assertTrue(compaction(true, false, null, logs).restoreToolsFor(AGENT, "s").isEmpty());
        assertTrue(compaction(false, true, null, logs).restoreToolsFor(AGENT, "s").isEmpty());
    }

    @Test
    void noRestoreToolWithoutALogServiceOrARunIdentity() {
        assertTrue(compaction(true, true, null, null).restoreToolsFor(AGENT, "s").isEmpty());
        ToolCallLogService logs = mock(ToolCallLogService.class);
        assertTrue(compaction(true, true, null, logs).restoreToolsFor(null, "s").isEmpty());
        assertTrue(compaction(true, true, null, logs).restoreToolsFor(AGENT, null).isEmpty());
    }

    // ---------------------------------------------------------------- window lookup

    @Test
    void theCatalogWindowWinsWhenKnown() {
        assertEquals(
                32_000,
                compaction(true, true, catalogReturning(32_000), null)
                        .resolveWindow(ProviderName.ANTHROPIC, "m"));
    }

    @Test
    void aMissingCatalogWindowFallsBackToTheNameFloorThenTheConfiguredDefault() {
        ChatContextCompaction c = compaction(true, true, catalogReturning(null), null);

        assertEquals(
                64_000,
                c.resolveWindow(ProviderName.ANTHROPIC, "m"),
                "unknown name -> configured default");
        assertEquals(
                200_000,
                c.resolveWindow(ProviderName.ANTHROPIC, "claude-sonnet-x"),
                "claude floor");
        assertEquals(64_000, c.resolveWindow(null, null));
    }

    @Test
    void aCatalogFailureDegradesToTheFallbackAndNeverThrows() {
        ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
        when(catalog.findModel(any(), any())).thenThrow(new IllegalStateException("db down"));

        assertEquals(
                64_000,
                compaction(true, true, catalog, null).resolveWindow(ProviderName.ANTHROPIC, "m"));
    }

    // ---------------------------------------------------------------- an opened run

    @Test
    void anOpenedRunClearsOldResultsUsingTheCatalogWindowAndPointsAtTheRestoreTool() {
        ChatContextCompaction c =
                compaction(true, true, catalogReturning(10_000), mock(ToolCallLogService.class));
        CompactionRun run =
                c.openRun(
                        ProviderName.ANTHROPIC,
                        "m",
                        AGENT,
                        "s",
                        List.of(tool("restore_tool_result")),
                        null);
        assertNotNull(run);
        List<Message> history = toolRun(5);

        List<Message> out = run.transform(history, "m");

        assertNotSame(history, out);
        assertTrue(
                clearedPlaceholder(out).contains("restore_tool_result"), clearedPlaceholder(out));
    }

    @Test
    void withoutTheRestoreToolOnTheListPlaceholdersTellTheModelToRunTheToolAgain() {
        ChatContextCompaction c =
                compaction(true, true, catalogReturning(10_000), mock(ToolCallLogService.class));
        CompactionRun run =
                c.openRun(ProviderName.ANTHROPIC, "m", AGENT, "s", List.of(tool("search")), null);

        String placeholder = clearedPlaceholder(run.transform(toolRun(5), "m"));

        assertTrue(!placeholder.contains("restore_tool_result"), placeholder);
        assertTrue(placeholder.contains("run the tool again"), placeholder);
    }

    @Test
    void aSmallPromptThroughAnOpenedRunIsUntouched() {
        ChatContextCompaction c = compaction(true, true, catalogReturning(10_000), null);
        CompactionRun run = c.openRun(ProviderName.ANTHROPIC, "m", AGENT, "s", List.of(), null);
        List<Message> history = toolRun(1);

        assertSame(history, run.transform(history, "m"));
    }

    // ------------------------------------------------------- chat-path entry points

    @Test
    void offeredRestoreToolsAreGuardWrappedAndDroppedWhenTheNameIsAlreadyTaken() {
        ChatContextCompaction c = compaction(true, true, null, mock(ToolCallLogService.class));
        List<ToolCallback> wrapped = new ArrayList<>();

        List<ToolCallback> offered =
                c.offeredRestoreTools(
                        AGENT,
                        "s",
                        List.of(tool("search")),
                        t -> {
                            wrapped.add(t);
                            return t;
                        });

        assertEquals(1, offered.size());
        assertEquals(1, wrapped.size());
        assertTrue(
                c.offeredRestoreTools(AGENT, "s", List.of(tool("restore_tool_result")), t -> t)
                        .isEmpty(),
                "an MCP tool of the same name wins - a duplicate tool name is fatal");
        assertTrue(
                compaction(false, true, null, mock(ToolCallLogService.class))
                        .offeredRestoreTools(AGENT, "s", List.of(), t -> t)
                        .isEmpty());
    }

    @Test
    void openRunForResolvesTheModelAndNeverFailsTheTurn() {
        ChatContextCompaction c = compaction(true, true, catalogReturning(10_000), null);
        com.agenticai.core.agents.core.Agent agent = mock(com.agenticai.core.agents.core.Agent.class);
        when(agent.getProviderName()).thenReturn(ProviderName.ANTHROPIC);

        assertNotNull(c.openRunFor(agent, null, null, AGENT, "s", List.of(), null, a -> "m"));
        assertNotNull(
                c.openRunFor(
                        agent,
                        "m",
                        null,
                        AGENT,
                        "s",
                        List.of(),
                        null,
                        a -> {
                            throw new IllegalStateException(
                                    "not consulted when an RL override is set");
                        }));
        assertNull(
                c.openRunFor(
                        agent,
                        null,
                        null,
                        AGENT,
                        "s",
                        List.of(),
                        null,
                        a -> {
                            throw new IllegalStateException("no model");
                        }),
                "a failure to resolve the model means no run, not a failed turn");
        assertNull(
                compaction(false, true, null, null)
                        .openRunFor(agent, null, null, AGENT, "s", List.of(), null, a -> "m"));
    }

    @Test
    void compactionCountersAreRecordedOnTheSpringRegistry() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ChatContextCompaction c =
                new ChatContextCompaction(
                        providerOf((LlmCompactionSummarizer) null),
                        providerOf(catalogReturning(10_000)),
                        providerOf((ToolCallLogService) null),
                        providerOf((io.micrometer.core.instrument.MeterRegistry) registry),
                        true,
                        0.6,
                        0.85,
                        2,
                        true,
                        2,
                        64_000,
                        100,
                        3);
        CompactionRun run = c.openRun(ProviderName.ANTHROPIC, "m", AGENT, "s", List.of(), null);

        run.transform(toolRun(5), "m");

        assertEquals(3.0, registry.counter("chat.context.compaction", "action", "clear").count());
    }
}
