package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.browseruse.BrowserAutomationTools;
import com.agenticai.core.agents.tools.browseruse.BrowserToolAdapter;
import com.agenticai.core.agents.tools.browseruse.BrowserUseSessionManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Which browser tools {@link AgentCrewBrowserToolResolver#resolveBrowserUseTools} offers. Flag off
 * (the default) must be exactly today's five tools in today's order; v2 on adds the eight indexed
 * tools. Because the SAME method feeds both {@code resolveToolsFor} and {@code
 * listAvailableToolsFor} (conventions #3), this one test covers both lists. The tools are built by
 * core/agents-browser's {@link BrowserToolAdapter} behind the kernel's {@link BrowserToolPort}; the
 * resolver only guards them, so this test wires both halves.
 */
class AgentCrewBrowserToolResolverBrowserV2Test {

    private static final List<String> LEGACY =
            List.of(
                    "browser_navigate",
                    "browser_click",
                    "browser_type",
                    "browser_read_page_text",
                    "browser_screenshot");
    private static final List<String> V2 =
            List.of(
                    "browser_snapshot",
                    "browser_click_element",
                    "browser_confirm_click",
                    "browser_type_into",
                    "browser_select_option",
                    "browser_scroll",
                    "browser_go_back",
                    "browser_extract_text");

    private BrowserUseSessionManager manager;
    private AgentCrewBrowserToolResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AgentToolGuard toolGuard = mock(AgentToolGuard.class);
        when(toolGuard.guardAndLog(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        manager = mock(BrowserUseSessionManager.class);
        ObjectProvider<BrowserUseSessionManager> managerProvider = mock(ObjectProvider.class);
        when(managerProvider.getIfAvailable()).thenReturn(manager);
        ObjectProvider<BrowserToolPort> portProvider = mock(ObjectProvider.class);
        when(portProvider.getIfAvailable())
                .thenReturn(
                        new BrowserToolAdapter(
                                managerProvider,
                                (ObjectProvider<BrowserAutomationTools>)
                                        mock(ObjectProvider.class)));
        resolver =
                new AgentCrewBrowserToolResolver(
                        toolGuard,
                        portProvider,
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class));
    }

    private List<String> offered() {
        List<ToolCallback> tools =
                resolver.resolveBrowserUseTools(
                        UUID.randomUUID(), "session", new AtomicInteger(), null, null, false);
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void browserDisabledOffersNothing() {
        when(manager.isEnabled()).thenReturn(false);
        when(manager.isV2Enabled()).thenReturn(false);
        assertThat(offered()).isEmpty();
    }

    @Test
    void v2OffIsExactlyTheFiveLegacyToolsInTheirOriginalOrder() {
        when(manager.isEnabled()).thenReturn(true);
        when(manager.isV2Enabled()).thenReturn(false);
        when(manager.isLegacyClickTypeOffered()).thenReturn(true);
        assertThat(offered()).containsExactlyElementsOf(LEGACY);
    }

    @Test
    void v2OffIgnoresTheLegacyToggle() {
        when(manager.isEnabled()).thenReturn(true);
        when(manager.isV2Enabled()).thenReturn(false);
        when(manager.isLegacyClickTypeOffered()).thenReturn(false);
        assertThat(offered()).containsExactlyElementsOf(LEGACY);
    }

    @Test
    void v2OnAddsTheEightIndexedToolsAfterTheLegacyFive() {
        when(manager.isEnabled()).thenReturn(true);
        when(manager.isV2Enabled()).thenReturn(true);
        when(manager.isLegacyClickTypeOffered()).thenReturn(true);
        List<String> expected = new java.util.ArrayList<>(LEGACY);
        expected.addAll(V2);
        assertThat(offered()).containsExactlyElementsOf(expected);
    }

    @Test
    void v2OnCanDropTheLegacyClickAndType() {
        when(manager.isEnabled()).thenReturn(true);
        when(manager.isV2Enabled()).thenReturn(true);
        when(manager.isLegacyClickTypeOffered()).thenReturn(false);
        List<String> names = offered();
        assertThat(names).doesNotContain("browser_click", "browser_type");
        assertThat(names)
                .contains("browser_navigate", "browser_read_page_text", "browser_screenshot");
        assertThat(names).containsAll(V2);
    }
}
