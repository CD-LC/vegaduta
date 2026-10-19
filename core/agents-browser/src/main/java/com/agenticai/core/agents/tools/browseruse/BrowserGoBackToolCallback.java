package com.agenticai.core.agents.tools.browseruse;

import java.util.Map;
import java.util.UUID;

/**
 * {@code browser_go_back} (browser agent v2): browser-history back. The resulting page is
 * re-checked against the domain allow/deny policy (a back-navigation to a blocked domain is refused
 * and the page blanked), and every request still passes the route guard. Classified like {@code
 * browser_navigate} ({@code UNTRUSTED_EXTERNAL}/{@code READ_PUBLIC}).
 */
public class BrowserGoBackToolCallback extends BrowserV2ToolBase {

    public static final String NAME = "browser_go_back";

    public BrowserGoBackToolCallback(
            BrowserUseSessionManager sessionManager, UUID agentId, String sessionId) {
        super(sessionManager, agentId, sessionId);
    }

    @Override
    String toolName() {
        return NAME;
    }

    @Override
    String toolDescription() {
        return "Goes back one page in the browser history. Take a new browser_snapshot afterwards.";
    }

    @Override
    String toolInputSchema() {
        return """
            {
                "type": "object",
                "properties": {}
            }
            """;
    }

    @Override
    String execute(String tenantId, Map<String, Object> args) {
        Map<String, Object> result =
                sessionManager.runV2(
                        tenantId,
                        agentId,
                        sessionId,
                        (session, page, settings) ->
                                BrowserV2Engine.goBack(session, page, settings, agentId));
        return BrowserUseToolSupport.toJson(result);
    }
}
