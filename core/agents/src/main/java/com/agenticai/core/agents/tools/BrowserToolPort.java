package com.agenticai.core.agents.tools;

import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * Port (ADR-0023): the browser-use tool families that {@link AgentCrewBrowserToolResolver} offers.
 * Implemented in core/agents-browser ({@code browseruse.BrowserToolAdapter}), which owns {@code
 * tools.browseruse}. Both methods return RAW (unguarded) tools and an empty list when the browser
 * is not available or switched off; the resolver wraps each one in the tool guard. No bean (a slim
 * context) = no browser tools offered.
 */
public interface BrowserToolPort {

    /**
     * {@code browser_navigate/click/type/read_page_text/screenshot}, plus the v2 indexed tools when
     * {@code app.browser.v2.enabled}.
     */
    List<ToolCallback> browserUseTools(UUID agentId, String sessionId);

    /** {@code browser_totp} and {@code run_browser_recipe}, each behind its own flag. */
    List<ToolCallback> browserAutomationTools(UUID agentId, String sessionId);
}
