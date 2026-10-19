package com.agenticai.core.agents.tools.browseruse;

import java.util.Map;
import java.util.UUID;

/**
 * {@code browser_select_option} (browser agent v2): chooses an option of the native {@code
 * <select>} listed at {@code index}, by option value or visible label. Classified like {@code
 * browser_click_element} ({@code EGRESS}, not irreversible). Custom (non-native) dropdowns are
 * driven with click_element instead.
 */
public class BrowserSelectOptionToolCallback extends BrowserV2ToolBase {

    public static final String NAME = "browser_select_option";

    public BrowserSelectOptionToolCallback(
            BrowserUseSessionManager sessionManager, UUID agentId, String sessionId) {
        super(sessionManager, agentId, sessionId);
    }

    @Override
    String toolName() {
        return NAME;
    }

    @Override
    String toolDescription() {
        return "Selects an option in the native dropdown [index] from the latest browser_snapshot, by option value or visible "
                + "label. For custom dropdowns, click the dropdown and then the option instead.";
    }

    @Override
    String toolInputSchema() {
        return """
            {
                "type": "object",
                "properties": {
                    "index": {
                        "type": "integer",
                        "description": "The dropdown number from the latest browser_snapshot."
                    },
                    "value": {
                        "type": "string",
                        "description": "The option's value or visible label."
                    }
                },
                "required": ["index", "value"]
            }
            """;
    }

    @Override
    String execute(String tenantId, Map<String, Object> args) {
        Integer index = asIndex(args.get("index"));
        if (index == null) {
            return missingIndex();
        }
        String value = BrowserUseToolSupport.asTrimmedString(args.get("value"));
        if (value == null) {
            return BrowserUseToolSupport.errorJson("The 'value' argument is required.");
        }
        Map<String, Object> result =
                sessionManager.runV2(
                        tenantId,
                        agentId,
                        sessionId,
                        (session, page, settings) ->
                                BrowserV2Engine.selectOption(
                                        session, page, settings, index, value));
        return BrowserUseToolSupport.toJson(result);
    }
}
