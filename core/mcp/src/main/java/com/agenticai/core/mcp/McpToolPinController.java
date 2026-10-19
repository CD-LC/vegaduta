package com.agenticai.core.mcp;

import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review surface for tool-definition pinning ({@link McpToolPin}). Reads are open to any
 * authenticated tenant member; approving a changed tool list is the same admin action class as
 * registering the server ({@code PERM_MANAGE_INTEGRATIONS}, see {@code AdminIntegrationRules}).
 * The audit event for an approval is written through {@link McpToolPinApprovalListener}, which
 * core/agents implements because it owns the audit dependency this leaf module does not have.
 */
@RestController
@RequestMapping("/api/mcp/tool-pins")
public class McpToolPinController {

    private final McpToolPinService pins;
    private final Optional<McpToolPinApprovalListener> listener;

    public McpToolPinController(McpToolPinService pins, Optional<McpToolPinApprovalListener> listener) {
        this.pins = pins;
        this.listener = listener;
    }

    /** Registrations whose tool list differs from the approved baseline. */
    @GetMapping
    public List<McpToolPinService.PinStatus> changed() {
        return pins.changedStatuses();
    }

    @GetMapping("/{id}")
    public McpToolPinService.PinStatus get(@PathVariable UUID id) {
        return pins.status(id);
    }

    /** Accepts the currently observed tool list as the approved baseline. */
    @PostMapping("/{id}/approve")
    public McpToolPinService.PinStatus approve(@PathVariable UUID id) {
        String actor = TenantContext.current().map(TenantContext::getSubject).orElse("unknown");
        McpToolPinService.Approval approval = pins.approveWithDiff(id, actor);
        McpToolPinService.PinStatus status = approval.status();
        listener.ifPresent(l -> l.onApproved(id, status.name(), actor, status.pinnedHash(), approval.approvedDiff()));
        return status;
    }
}
