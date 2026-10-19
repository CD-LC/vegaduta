package com.agenticai.core.agents.tools.browseruse;

import java.util.Map;
import java.util.UUID;

/**
 * {@code browser_confirm_click} (browser agent v2): the click for elements {@link
 * BrowserIrreversibleClick} flags (buy/pay/confirm/submit/delete/send-like). Classified {@code
 * EGRESS} + irreversible, so the tool-classification confirmation gate parks the call for human
 * approval before it runs ({@code requiresHumanConfirmation}); it is deliberately NOT in {@code
 * app.tools.classification.confirmation-gate-exempt-tools}. The approval prompt shows the
 * arguments, which is why {@code label} is required and checked against the element the server
 * recorded: the approver sees "click [7] 'Pay now'", not an anonymous number.
 *
 * <p>Honest limitations: agents on the NEVER_GATE autonomy tier skip the approval (see {@code
 * GuardedToolCallback}), and with {@code app.tools.classification.confirmation-gate=false} the gate
 * is shadow-only - in both cases this tool runs without a human, and the heuristic is then only a
 * speed bump. An approved call is replayed by name after the human answers, so it needs the browser
 * session (idle timeout 5 min) and snapshot to still be current; otherwise it fails with the
 * stale-index error and the model must snapshot again.
 */
public class BrowserConfirmClickToolCallback extends BrowserV2ToolBase {

    public static final String NAME = "browser_confirm_click";

    public BrowserConfirmClickToolCallback(
            BrowserUseSessionManager sessionManager, UUID agentId, String sessionId) {
        super(sessionManager, agentId, sessionId);
    }

    @Override
    String toolName() {
        return NAME;
    }

    @Override
    String toolDescription() {
        return "Clicks a buy/pay/confirm/submit/delete/send-style element after human approval. Call it only when the user "
                + "explicitly asked for that exact action. 'label' must be the element's name from the snapshot.";
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
                    },
                    "label": {
                        "type": "string",
                        "description": "The element's name exactly as listed in the snapshot (shown to the approver)."
                    }
                },
                "required": ["index", "label"]
            }
            """;
    }

    @Override
    String execute(String tenantId, Map<String, Object> args) {
        Integer index = asIndex(args.get("index"));
        if (index == null) {
            return missingIndex();
        }
        String label = BrowserUseToolSupport.asTrimmedString(args.get("label"));
        if (label == null) {
            return BrowserUseToolSupport.errorJson(
                    "The 'label' argument is required: the element's name from the snapshot.");
        }
        Map<String, Object> result =
                sessionManager.runV2(
                        tenantId,
                        agentId,
                        sessionId,
                        (session, page, settings) ->
                                BrowserV2Engine.click(session, page, settings, index, true, label));
        return BrowserUseToolSupport.toJson(result);
    }
}
