package com.agenticai.core.agents.tools.browseruse;

import java.util.Map;
import java.util.UUID;

/**
 * {@code browser_snapshot} (browser agent v2): the page as a numbered list of its visible
 * interactive elements - {@code [index] role 'name' (value, state)} in bounding-box order,
 * de-duplicated and capped ({@code app.browser.v2.max-elements}, default 120) - plus URL, title and
 * a short text digest, computed in one in-page evaluate. The action tools ({@code
 * browser_click_element}, {@code browser_type_into}, {@code browser_select_option}, {@code
 * browser_extract_text}) take the {@code index}, which replaces fragile CSS selectors. Read-only:
 * classified like {@code browser_read_page_text} ({@code UNTRUSTED_EXTERNAL}/{@code READ_PUBLIC}) -
 * what comes back is third-party content.
 */
public class BrowserSnapshotToolCallback extends BrowserV2ToolBase {

    public static final String NAME = "browser_snapshot";

    public BrowserSnapshotToolCallback(
            BrowserUseSessionManager sessionManager, UUID agentId, String sessionId) {
        super(sessionManager, agentId, sessionId);
    }

    @Override
    String toolName() {
        return NAME;
    }

    @Override
    String toolDescription() {
        return "Lists the current browser page's visible interactive elements as a numbered list '[index] role 'name' "
                + "(value, state)' plus URL, title and a text digest. Call it before browser_click_element, browser_type_into "
                + "or browser_select_option, and again after the page changes. Requires browser_navigate first.";
    }

    @Override
    String toolInputSchema() {
        return """
            {
                "type": "object",
                "properties": {
                    "maxElements": {
                        "type": "integer",
                        "description": "Optional lower cap on listed elements (the platform maximum applies)."
                    }
                }
            }
            """;
    }

    @Override
    String execute(String tenantId, Map<String, Object> args) {
        int max = 0;
        Object requested = args.get("maxElements");
        if (requested != null) {
            try {
                max = Integer.parseInt(requested.toString().strip());
            } catch (NumberFormatException e) {
                max = 0;
            }
        }
        final int cap = max;
        return sessionManager.runV2(
                tenantId,
                agentId,
                sessionId,
                (session, page, settings) ->
                        BrowserV2Engine.snapshot(session, page, settings, cap));
    }
}
