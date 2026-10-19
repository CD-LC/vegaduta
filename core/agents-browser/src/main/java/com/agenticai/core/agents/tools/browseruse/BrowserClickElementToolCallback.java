package com.agenticai.core.agents.tools.browseruse;

import java.util.Map;
import java.util.UUID;

/**
 * {@code browser_click_element} (browser agent v2): clicks the element a {@code browser_snapshot}
 * listed at {@code index}. Stale indexes (the page changed) fail with a clear "take a new snapshot"
 * error.
 *
 * <p>Classified {@code EGRESS} but NOT irreversible, like any click on a page. The conservative
 * irreversibility heuristic ({@link BrowserIrreversibleClick}:
 * buy/pay/confirm/submit/delete/send-like names, decided from what the SERVER recorded in the
 * snapshot) is enforced here by refusing such a click and pointing at {@link
 * BrowserConfirmClickToolCallback}, whose classification IS irreversible so the confirmation gate
 * prompts a human. The gate itself works on tool names, not arguments - that is why the flagged
 * path is a separate tool.
 */
public class BrowserClickElementToolCallback extends BrowserV2ToolBase {

    public static final String NAME = "browser_click_element";

    public BrowserClickElementToolCallback(
            BrowserUseSessionManager sessionManager, UUID agentId, String sessionId) {
        super(sessionManager, agentId, sessionId);
    }

    @Override
    String toolName() {
        return NAME;
    }

    @Override
    String toolDescription() {
        return "Clicks element [index] from the latest browser_snapshot. If the page changed you get an error: take a new "
                + "snapshot. Buy/pay/confirm/submit/delete/send-style elements are NOT clicked by this tool - use "
                + "browser_confirm_click for those.";
    }

    @Override
    String toolInputSchema() {
        return """
            {
                "type": "object",
                "properties": {
                    "index": {
                        "type": "integer",
                        "description": "The element number from the latest browser_snapshot."
                    }
                },
                "required": ["index"]
            }
            """;
    }

    @Override
    String execute(String tenantId, Map<String, Object> args) {
        Integer index = asIndex(args.get("index"));
        if (index == null) {
            return missingIndex();
        }
        Map<String, Object> result =
                sessionManager.runV2(
                        tenantId,
                        agentId,
                        sessionId,
                        (session, page, settings) ->
                                BrowserV2Engine.click(session, page, settings, index, false, null));
        return BrowserUseToolSupport.toJson(result);
    }
}
