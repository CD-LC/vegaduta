package com.agenticai.core.agents.tools.browseruse;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code browser_request_user} (human takeover, {@code app.browser.takeover.enabled}): the agent
 * asks the PERSON to take over the browser for a step it cannot or must not do itself - a captcha,
 * a 2FA prompt, a login with credentials it does not hold. Offered only when takeover (and browser
 * v2) is on.
 *
 * <p>It parks the agent the same way a pending human approval does ({@code GuardedToolCallback}):
 * the call records the request and returns an explanatory result instead of acting, and the model
 * is told to tell the user what it needs and end its turn. From that moment every other {@code
 * browser_*} call on the session is refused until the person hands control back ({@link
 * BrowserTakeoverService#requireAgentControl}).
 *
 * <p>{@code action: "status"} asks where things stand and, once the person has handed control back,
 * returns the outcome: that the user completed the step, plus the page text read back AFTER
 * redaction (vault values and anything the person typed are scrubbed). The agent never sees what
 * was typed.
 *
 * <p>Classified non-destructive and not irreversible ({@code BuiltInToolClassifications}): it moves
 * no money, sends nothing and changes nothing on a site - it only pauses the agent - so it needs no
 * confirmation of its own. Identity never travels as an argument: tenant from {@code
 * TenantContext}, agent and chat session fixed at construction.
 */
public class BrowserRequestUserToolCallback extends BrowserV2ToolBase {

    public static final String NAME = "browser_request_user";

    private final BrowserTakeoverService takeover;

    public BrowserRequestUserToolCallback(
            BrowserUseSessionManager sessionManager,
            BrowserTakeoverService takeover,
            UUID agentId,
            String sessionId) {
        super(sessionManager, agentId, sessionId);
        this.takeover = takeover;
    }

    @Override
    String toolName() {
        return NAME;
    }

    @Override
    String toolDescription() {
        return "Ask the user to take over the browser for a step you cannot or must not do yourself: a captcha, a 2FA or "
                + "one-time code prompt, or a login you have no saved credentials for. The browser pauses for them and you "
                + "cannot use it until they hand it back. Give a short 'reason' telling them what to do. Never ask the user to "
                + "type a password or code to you in chat. After they say they are done, call again with action \"status\".";
    }

    @Override
    String toolInputSchema() {
        return """
            {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["request", "status"],
                        "description": "request (default): ask the user to take over. status: check whether they handed control back and get the outcome."
                    },
                    "reason": {
                        "type": "string",
                        "description": "One short sentence shown to the user: what they need to do (e.g. 'Solve the captcha and sign in')."
                    },
                    "kind": {
                        "type": "string",
                        "enum": ["captcha", "two_factor", "login", "other"],
                        "description": "What kind of step it is."
                    }
                }
            }
            """;
    }

    @Override
    String execute(String tenantId, Map<String, Object> args) {
        String sessionKey = BrowserUseSessionManager.sessionKey(tenantId, agentId, sessionId);
        String action = BrowserUseToolSupport.asTrimmedString(args.get("action"));
        action = action == null ? "request" : action.toLowerCase(Locale.ROOT);
        BrowserTakeoverService.AgentOutcome outcome;
        switch (action) {
            case "request" -> {
                String reason = BrowserUseToolSupport.asTrimmedString(args.get("reason"));
                if (reason == null) {
                    return BrowserUseToolSupport.errorJson(
                            "Give a short 'reason': what the user needs to do in the browser.");
                }
                outcome =
                        takeover.agentRequest(
                                sessionKey,
                                BrowserUseToolSupport.asTrimmedString(args.get("kind")),
                                reason);
            }
            case "status" -> outcome = takeover.agentStatus(sessionKey);
            default -> {
                return BrowserUseToolSupport.errorJson(
                        "'action' must be \"request\" or \"status\".");
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", outcome.state());
        result.put("message", outcome.message());
        return BrowserUseToolSupport.toJson(result);
    }
}
