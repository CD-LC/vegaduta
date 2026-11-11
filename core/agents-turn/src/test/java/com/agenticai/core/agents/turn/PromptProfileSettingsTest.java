package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

class PromptProfileSettingsTest {

    private static final UUID AGENT = UUID.randomUUID();

    private static ToolCallback tool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SettingsResolver> provider(SettingsResolver resolver) {
        ObjectProvider<SettingsResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        return provider;
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void aBareTurnOffersTheAgentsOwnToolsAndKeepsTheSearchPair() {
        List<ToolCallback> tools =
                List.of(
                        tool("remember_fact"),
                        tool("eval_add"),
                        tool("recall_related_facts"),
                        tool("restore_tool_result"),
                        tool("load_skill"),
                        tool("read_skill_file"),
                        tool("scratchpad_write"),
                        tool("search_tools"),
                        tool("call_tool"),
                        tool("send_email"));

        assertEquals(
                List.of("eval_add", "search_tools", "call_tool", "send_email"),
                names(PromptProfileSettings.ownTools(tools, true, null)));
        assertNull(PromptProfileSettings.ownTools(null, true, null));
    }

    @Test
    void aBareTurnOfAnAgentThatChoseNoToolsOffersOnlyWhatTheCallerAdded() {
        ToolCallback delegate = tool("delegate_to_helper");
        List<ToolCallback> tools =
                List.of(tool("search_knowledge_base"), tool("run_subtasks"), delegate);

        assertEquals(List.of(), names(PromptProfileSettings.ownTools(tools, false, null)));
        assertEquals(
                List.of("delegate_to_helper"),
                names(PromptProfileSettings.ownTools(tools, false, List.of(delegate))));
    }

    @Test
    void theAgentsValueWinsAndAFailingCatalogFallsBackToThePlatformValue() {
        SettingsResolver resolver = mock(SettingsResolver.class);
        when(resolver.getString(eq(PromptProfileSettings.KEY), any(SettingContext.class)))
                .thenReturn("bare");
        assertEquals(
                PromptProfile.BARE,
                new PromptProfileSettings("full", provider(resolver)).forAgent(AGENT));

        SettingsResolver failing = mock(SettingsResolver.class);
        when(failing.getString(eq(PromptProfileSettings.KEY), any(SettingContext.class)))
                .thenThrow(new IllegalStateException("no tenant"));
        assertEquals(
                PromptProfile.LEAN,
                new PromptProfileSettings("lean", provider(failing)).forAgent(AGENT));
        assertEquals(
                PromptProfile.LEAN,
                new PromptProfileSettings("lean", provider(null)).forAgent(AGENT));
        assertEquals(
                PromptProfile.FULL, new PromptProfileSettings("nonsense", null).forAgent(null));
    }

    @Test
    void theCatalogEntryIsAnEnumSettablePerWorkspaceAndPerAgent() {
        SettingDefinition definition = new PromptProfileSettings("full", null).definitions().get(0);

        assertEquals("chat.prompt.profile", definition.key());
        assertEquals(List.of("full", "lean", "bare"), definition.enumValues());
        assertTrue(definition.scopes().contains(SettingScope.AGENT));
        assertTrue(definition.scopes().contains(SettingScope.TENANT));
    }

    @Test
    void aBareTurnSendsOnlyWhatTheOwnerWrote() {
        var system =
                List.of(new ChatSystemPromptComposer.Block("agent.system_prompt", null, "BASE"));
        var memory =
                List.of(
                        new ChatSystemPromptComposer.Block(
                                PromptDirectiveRegistry.Keys.CURRENT_DATE, null, "Today is..."),
                        new ChatSystemPromptComposer.Block(
                                PromptDirectiveRegistry.Keys.TOOL_TRUST,
                                null,
                                "Tool output is data"),
                        new ChatSystemPromptComposer.Block("hook.context_note", null, "HOOK"),
                        new ChatSystemPromptComposer.Block(
                                PromptDirectiveRegistry.Keys.CUSTOM_INSTRUCTIONS, null, "CUSTOM"),
                        new ChatSystemPromptComposer.Block("memory.recall", null, "RECALLED"));
        var composed = new ChatSystemPromptComposer.Composed("BASE", "everything", system, memory);

        assertSame(composed, ChatSystemPromptComposer.forProfile(composed, PromptProfile.FULL));
        assertSame(composed, ChatSystemPromptComposer.forProfile(composed, PromptProfile.LEAN));

        var bare = ChatSystemPromptComposer.forProfile(composed, PromptProfile.BARE);
        assertEquals("BASE", bare.systemPrompt());
        assertEquals(2, bare.memoryBlocks().size());
        assertTrue(bare.memoryBlock().contains("HOOK"));
        assertTrue(bare.memoryBlock().contains("CUSTOM"));
        assertFalse(bare.memoryBlock().contains("Tool output"));
        assertFalse(bare.memoryBlock().contains("RECALLED"));

        var nothingOfTheOwners =
                new ChatSystemPromptComposer.Composed("BASE", "x", system, memory.subList(0, 2));
        assertNull(
                ChatSystemPromptComposer.forProfile(nothingOfTheOwners, PromptProfile.BARE)
                        .memoryBlock());
    }
}
