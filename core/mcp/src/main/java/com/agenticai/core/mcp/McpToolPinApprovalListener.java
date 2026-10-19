package com.agenticai.core.mcp;

import java.util.UUID;

/**
 * Callback for modules that own an audit trail (core/mcp does not depend on core/audit). Optional:
 * with no implementation present an approval is still persisted, just not audited.
 */
public interface McpToolPinApprovalListener {

    void onApproved(UUID registrationId, String serverName, String actor, String newHash, McpToolPin.Diff approvedDiff);
}
