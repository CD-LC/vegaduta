package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.agents.tools.BrowserToolPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Implements the kernel's {@link BrowserToolPort} (ADR-0023): builds the raw browser tool families
 * that {@code AgentCrewBrowserToolResolver} used to build in place. The offer gates are unchanged:
 * the {@link BrowserUseSessionManager} bean must be present and {@link
 * BrowserUseSessionManager#isEnabled()} (off by default: Chromium must be provisioned first); the
 * automation tools additionally need {@link BrowserAutomationTools}, which decides the per-feature
 * flags. Both collaborators are ObjectProviders so a slim context stays constructible.
 */
@Component
public class BrowserToolAdapter implements BrowserToolPort {

    private final ObjectProvider<BrowserUseSessionManager> sessionManager;
    private final ObjectProvider<BrowserAutomationTools> automationTools;

    public BrowserToolAdapter(
            ObjectProvider<BrowserUseSessionManager> sessionManager,
            ObjectProvider<BrowserAutomationTools> automationTools) {
        this.sessionManager = sessionManager;
        this.automationTools = automationTools;
    }

    /**
     * The five v1 tools resolve together or not at all; browser agent v2 ({@code
     * app.browser.v2.enabled}, default OFF) adds the indexed snapshot + index-addressed actions and
     * may drop the legacy click/type. Flag off = exactly the five tools, in the same order.
     */
    @Override
    public List<ToolCallback> browserUseTools(UUID agentId, String sessionId) {
        BrowserUseSessionManager manager = sessionManager.getIfAvailable();
        if (manager == null || !manager.isEnabled()) {
            return List.of();
        }
        List<ToolCallback> raw =
                new ArrayList<>(
                        List.of(
                                new BrowserNavigateToolCallback(manager, agentId, sessionId),
                                new BrowserClickToolCallback(manager, agentId, sessionId),
                                new BrowserTypeToolCallback(manager, agentId, sessionId),
                                new BrowserReadPageTextToolCallback(manager, agentId, sessionId),
                                new BrowserScreenshotToolCallback(manager, agentId, sessionId)));
        if (manager.isV2Enabled()) {
            if (!manager.isLegacyClickTypeOffered()) {
                raw.removeIf(
                        tool ->
                                tool instanceof BrowserClickToolCallback
                                        || tool instanceof BrowserTypeToolCallback);
            }
            raw.add(new BrowserSnapshotToolCallback(manager, agentId, sessionId));
            raw.add(new BrowserClickElementToolCallback(manager, agentId, sessionId));
            raw.add(new BrowserConfirmClickToolCallback(manager, agentId, sessionId));
            raw.add(new BrowserTypeIntoToolCallback(manager, agentId, sessionId));
            raw.add(new BrowserSelectOptionToolCallback(manager, agentId, sessionId));
            raw.add(new BrowserScrollToolCallback(manager, agentId, sessionId));
            raw.add(new BrowserGoBackToolCallback(manager, agentId, sessionId));
            raw.add(new BrowserExtractTextToolCallback(manager, agentId, sessionId));
        }
        return raw;
    }

    /**
     * {@code browser_totp} (app.browser.totp.enabled) and {@code run_browser_recipe}
     * (app.browser.recipes.enabled), both default OFF; empty when everything is off.
     */
    @Override
    public List<ToolCallback> browserAutomationTools(UUID agentId, String sessionId) {
        BrowserUseSessionManager manager = sessionManager.getIfAvailable();
        BrowserAutomationTools tools = automationTools.getIfAvailable();
        if (manager == null || !manager.isEnabled() || tools == null) {
            return List.of();
        }
        return tools.create(manager, agentId, sessionId);
    }
}
