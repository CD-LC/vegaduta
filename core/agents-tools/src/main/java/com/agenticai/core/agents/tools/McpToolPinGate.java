package com.agenticai.core.agents.tools;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.mcp.McpToolPin;
import com.agenticai.core.mcp.McpToolPinApprovalListener;
import com.agenticai.core.mcp.McpToolPinService;
import com.agenticai.core.security.TenantContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Connects {@link DynamicMcpConnectionManager} to tool-definition pinning ({@link McpToolPin}):
 * records the tool list each dynamic registration advertises, writes an audit event whenever it
 * changes against the approved baseline, and (mode {@code enforce}) removes changed registrations
 * from what agents may call until an administrator approves the new list.
 *
 * <p>What is pinned is what the model sees - the wrapped, namespaced callbacks' name, description
 * and input schema - so a change hidden past the description cap, which never reaches the model,
 * is correctly not a change. MCP annotations are not part of the pin (the Spring AI tool
 * definition does not carry them); see docs/architecture/mcp-spec-alignment.md.
 *
 * <p>Never throws into the connection path: an observation failure is logged and the connection
 * proceeds, because pinning must not turn a database hiccup into an MCP outage. The read side
 * ({@link #allowed}) fails closed under {@code enforce}.
 */
@Component
public class McpToolPinGate implements McpToolPinApprovalListener {

    private static final Logger log = LoggerFactory.getLogger(McpToolPinGate.class);
    static final String ACTOR = "system:mcp-tool-pin";
    static final String ACTION_CHANGED = "mcp.tools.changed";
    static final String ACTION_APPROVED = "mcp.tools.approved";
    static final String TARGET = "MCP_SERVER";

    private final McpToolPinService pins;
    private final ObjectProvider<AuditLogService> audit;
    private final ObjectProvider<DynamicMcpConnectionManager> manager;

    public McpToolPinGate(
            McpToolPinService pins,
            ObjectProvider<AuditLogService> audit,
            ObjectProvider<DynamicMcpConnectionManager> manager) {
        this.pins = pins;
        this.audit = audit;
        this.manager = manager;
    }

    /** Observes {@code wrapped} for {@code registrationId} under {@code tenant}'s schema. */
    void observe(String tenant, UUID registrationId, List<ToolCallback> wrapped) {
        if (pins.mode() == McpToolPinService.Mode.OFF) {
            return;
        }
        TenantContext previous = TenantContext.current().orElse(null);
        try {
            if (previous == null || !tenant.equals(previous.getTenantId())) {
                TenantContext.set(TenantContext.of(tenant, ACTOR, Set.of()));
            }
            var observation = pins.observe(registrationId, descriptors(wrapped));
            if (observation.isPresent() && observation.get().newlyChanged()) {
                var o = observation.get();
                log.warn(
                        "MCP server '{}' ({}) changed its tool definitions for tenant '{}': {} (mode={})",
                        o.serverName(), registrationId, tenant, o.diff().summary(), pins.mode());
                record(ACTION_CHANGED, registrationId, o.serverName(), ACTOR, AuditOutcome.PENDING_APPROVAL,
                        "observedHash=" + o.hash() + " " + o.diff().summary() + " mode=" + pins.mode());
            }
        } catch (RuntimeException e) {
            log.warn("Tool pin observation failed for MCP registration {} - continuing without it", registrationId, e);
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    /** {@code usable} minus registrations whose changed tool list awaits approval (enforce mode). */
    Set<UUID> allowed(Set<UUID> usable) {
        if (pins.mode() != McpToolPinService.Mode.ENFORCE) {
            return usable;
        }
        try {
            Set<UUID> result = new HashSet<>(usable);
            result.removeAll(pins.blockedRegistrationIds());
            return result;
        } catch (RuntimeException e) {
            log.warn("Could not read MCP tool-pin state - offering NO dynamic MCP tools this turn (fail-closed)", e);
            return Set.of();
        }
    }

    @Override
    public void onApproved(UUID registrationId, String serverName, String actor, String newHash, McpToolPin.Diff approvedDiff) {
        record(ACTION_APPROVED, registrationId, serverName, actor, AuditOutcome.APPROVED,
                "pinnedHash=" + newHash + " " + approvedDiff.summary());
        DynamicMcpConnectionManager m = manager.getIfAvailable();
        if (m != null) {
            m.evictToolCache();
        }
    }

    static List<McpToolPin.ToolDescriptor> descriptors(List<ToolCallback> wrapped) {
        return wrapped.stream()
                .map(ToolCallback::getToolDefinition)
                .map(d -> new McpToolPin.ToolDescriptor(d.name(), d.description(), d.inputSchema(), null))
                .toList();
    }

    private void record(String action, UUID id, String serverName, String actor, AuditOutcome outcome, String detail) {
        AuditLogService svc = audit.getIfAvailable();
        if (svc == null) {
            return;
        }
        try {
            svc.record(actor, action, TARGET, id.toString(), outcome, "server=" + serverName + " " + detail);
        } catch (RuntimeException e) {
            log.warn("Could not write {} audit event for MCP registration {}", action, id, e);
        }
    }
}
