package com.agenticai.core.agents.tools.mcpreview;

import com.agenticai.core.agents.tools.mcpreview.dto.McpPendingReviewResponse;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpServerRegistration;
import com.agenticai.core.mcp.McpServerRegistrationNotFoundException;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The CROSS-tenant half of the MCP-STDIO-SANDBOXING approval gate - platform staff need to see and
 * act on every PENDING self-service STDIO registration across every tenant, but {@link
 * McpRegistryService} (core/mcp, a leaf module with no dependency on {@code core/tenancy}) can only
 * ever read/write whatever ONE schema the ambient {@link TenantContext} points at - see that
 * service's own "MCP-STDIO-SANDBOXING review queue" comment block for the full reasoning. This
 * class is the seam: it owns {@link TenantSchemaIterator} (core/agents already depends on both
 * {@code core/mcp} and {@code core/tenancy} - the same boundary {@code DynamicMcpConnectionManager}
 * already sits on for the identical reason) and delegates the actual per-row read/write down to
 * {@link McpRegistryService}'s tenant-scoped methods, one tenant schema at a time.
 *
 * <p>A registration's UUID is only unique WITHIN its own tenant schema (a separate physical table
 * per tenant, not a shared one with a tenant_id column) - so unlike every other admin action in
 * this codebase, approving/ rejecting one requires BOTH the tenant schema and the id, not the id
 * alone. {@link #listPending()} is what hands the caller that pairing in the first place (each row
 * carries its own {@code tenantId}).
 */
@Service
public class McpRegistryReviewService {

    private static final Logger log = LoggerFactory.getLogger(McpRegistryReviewService.class);
    private static final String REVIEW_ACTOR = "system:mcp-review";

    private final McpRegistryService registryService;
    private final TenantSchemaIterator tenantSchemaIterator;
    // Same ObjectProvider cross-module seam McpRegistryController/
    // McpRegistrationTestController already use (core/mcp) - optional so this
    // class stays constructible in a slim/degraded context with no live-
    // connection bean, same posture those two controllers already document.
    private final ObjectProvider<McpDynamicConnector> connector;

    public McpRegistryReviewService(
            McpRegistryService registryService,
            TenantSchemaIterator tenantSchemaIterator,
            ObjectProvider<McpDynamicConnector> connector) {
        this.registryService = registryService;
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.connector = connector;
    }

    /**
     * Every PENDING self-service STDIO registration, across every active tenant - the reviewer's
     * inbox.
     */
    public List<McpPendingReviewResponse> listPending() {
        List<McpPendingReviewResponse> all = new ArrayList<>();
        tenantSchemaIterator.forEachActiveTenantSchema(
                schema -> {
                    for (McpServerRegistration entity : registryService.pendingReview()) {
                        all.add(McpPendingReviewResponse.from(schema, entity));
                    }
                });
        return all;
    }

    /**
     * Approves the registration and immediately attempts a real connect (same "no separate activate
     * step" posture {@code McpRegistryController#register}/ {@code #connectNow} already take
     * elsewhere in this feature) - a reviewer clicking "Approve" expects to see whether it actually
     * works, not just that the gate opened. The connect attempt itself is best- effort: {@link
     * McpDynamicConnector#connect} never throws, and its outcome (CONNECTED or a fresh ERROR) is
     * what the returned response reflects.
     */
    public McpServerRegistrationResponse approve(String tenantId, UUID id) {
        log.info("Platform review: APPROVING MCP registration {} for tenant '{}'", id, tenantId);
        return withTenant(
                tenantId,
                () -> {
                    registryService.approveReview(id);
                    McpDynamicConnector c = connector.getIfAvailable();
                    if (c != null) {
                        c.connect(id);
                    }
                    return currentStateOrThrow(id);
                });
    }

    /**
     * Rejects the registration - {@code reason} (nullable) is surfaced back to the tenant via the
     * connect refusal hint. Never attempts a connection, but DOES evict the cached dynamic-tool
     * list (same {@code evictToolCache()} seam {@code connect}/{@code disconnect}/{@code approve}/
     * the controller's {@code toggle} already use): a reject can target a registration that was
     * previously APPROVED and is currently CONNECTED with live cached tools (platform staff
     * revoking, not just declining a PENDING one), and without this the ~20s discovery cache could
     * still let an agent turn call the now-rejected server.
     */
    public McpServerRegistrationResponse reject(String tenantId, UUID id, String reason) {
        log.info(
                "Platform review: REJECTING MCP registration {} for tenant '{}' (reason: {})",
                id,
                tenantId,
                reason);
        return withTenant(
                tenantId,
                () -> {
                    McpServerRegistrationResponse result = registryService.rejectReview(id, reason);
                    McpDynamicConnector c = connector.getIfAvailable();
                    if (c != null) {
                        c.evictToolCache();
                    }
                    return result;
                });
    }

    private McpServerRegistrationResponse currentStateOrThrow(UUID id) {
        return registryService.listAll().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new McpServerRegistrationNotFoundException(id));
    }

    /**
     * Runs {@code action} with {@link TenantContext} set to {@code tenantId} for its duration -
     * same shape {@link TenantSchemaIterator#forEachActiveTenantSchema} uses internally, but for a
     * SINGLE, caller-specified tenant rather than every active one (an approve/reject call already
     * knows exactly which tenant it targets - iterating all of them to find one row would be
     * needless work and a needless cross-tenant read). Trusts the caller- supplied {@code tenantId}
     * the same way {@code DynamicMcpConnectionManager#reconnectEnabledOnStartup}'s per-tenant
     * reconnect threads already do - this endpoint is platform-staff-only ({@code
     * ROLE_platform-admin}, enforced by {@code SecurityConfig}'s blanket {@code /api/platform/**}
     * rule) and the only source of a {@code tenantId} value is this class's own {@link
     * #listPending()} response, not free-form tenant input. An unknown/bogus schema simply finds
     * nothing (404 via {@link McpServerRegistrationNotFoundException}), never a cross-tenant leak.
     */
    private <T> T withTenant(String tenantId, Supplier<T> action) {
        try {
            TenantContext.set(TenantContext.of(tenantId, REVIEW_ACTOR, Set.of()));
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }
}
