package com.agenticai.core.agents.tools.mcpreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.mcpreview.dto.McpPendingReviewResponse;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpServerRegistration;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link McpRegistryReviewService} is the CROSS-tenant seam for the platform-staff MCP review queue
 * - see its own javadoc for why the id alone is never enough to target a row (a tenant schema is
 * required too). This suite mocks {@link TenantSchemaIterator} the same way {@code
 * DynamicMcpConnectionManagerTest} mocks it elsewhere in this module (a plain, non-final Spring
 * component, not an interface) and drives its {@code forEachActiveTenantSchema} callback manually
 * via {@code doAnswer}, since the real implementation needs a JPA {@code TenantRepository} this
 * suite has no reason to stand up.
 */
class McpRegistryReviewServiceTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Stubs {@code forEachActiveTenantSchema} to synchronously invoke {@code action} once per given
     * schema, exactly like the real implementation does - including setting {@link TenantContext}
     * to that schema for the duration of the call (the real {@code TenantSchemaIteratorImpl} does
     * this; {@link McpRegistryReviewService#listPending()} relies on it to make {@code
     * McpRegistryService#pendingReview()} tenant-scoped, so a mock that skips it makes every call
     * see no tenant context and return nothing).
     */
    private static TenantSchemaIterator iteratorOver(String... schemas) {
        TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
        doAnswer(
                        invocation -> {
                            Consumer<String> action = invocation.getArgument(0);
                            for (String schema : schemas) {
                                try {
                                    TenantContext.set(
                                            TenantContext.of(schema, "system", java.util.Set.of()));
                                    action.accept(schema);
                                } finally {
                                    TenantContext.clear();
                                }
                            }
                            return null;
                        })
                .when(iterator)
                .forEachActiveTenantSchema(any());
        return iterator;
    }

    private static McpServerRegistration pendingEntity(String name) {
        McpServerRegistration entity =
                new McpServerRegistration(name, McpServerType.STDIO, "d", true);
        entity.configureDynamic("npx", "[]", null);
        entity.markReviewPending();
        return entity;
    }

    // --- listPending(): aggregates across every tenant schema -------------------

    @Test
    void
            listPendingAggregatesPendingRegistrationsAcrossEveryTenantSchemaTaggedWithTheirOwnSchema() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator iterator = iteratorOver("tenant-a", "tenant-b");
        McpServerRegistration rowA = pendingEntity("custom-a");
        McpServerRegistration rowB1 = pendingEntity("custom-b1");
        McpServerRegistration rowB2 = pendingEntity("custom-b2");
        // pendingReview() is tenant-scoped and reads whatever the ambient
        // TenantContext currently points at - since this mock has no way to
        // vary its answer by ambient context on its own, stub it to look at
        // TenantContext.current() itself (exactly what the REAL, tenant-scoped
        // implementation does under the hood).
        when(registryService.pendingReview())
                .thenAnswer(
                        inv -> {
                            String tenant =
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null);
                            if ("tenant-a".equals(tenant)) {
                                return List.of(rowA);
                            }
                            if ("tenant-b".equals(tenant)) {
                                return List.of(rowB1, rowB2);
                            }
                            return List.of();
                        });
        McpRegistryReviewService service =
                new McpRegistryReviewService(registryService, iterator, mock(ObjectProvider.class));

        List<McpPendingReviewResponse> pending = service.listPending();

        assertEquals(3, pending.size());
        assertTrue(
                pending.stream()
                        .anyMatch(
                                r ->
                                        r.tenantId().equals("tenant-a")
                                                && r.name().equals("custom-a")));
        assertTrue(
                pending.stream()
                        .anyMatch(
                                r ->
                                        r.tenantId().equals("tenant-b")
                                                && r.name().equals("custom-b1")));
        assertTrue(
                pending.stream()
                        .anyMatch(
                                r ->
                                        r.tenantId().equals("tenant-b")
                                                && r.name().equals("custom-b2")));
    }

    @Test
    void listPendingReturnsEmptyWhenNoTenantHasAnythingPending() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        when(registryService.pendingReview()).thenReturn(List.of());
        TenantSchemaIterator iterator = iteratorOver("tenant-a");
        McpRegistryReviewService service =
                new McpRegistryReviewService(registryService, iterator, mock(ObjectProvider.class));

        assertTrue(service.listPending().isEmpty());
    }

    // --- approve()/reject(): correct tenant targeted, context cleaned up afterward ---

    @Test
    void approveRunsWithTheGivenTenantsContextAndClearsItAfterward() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator iterator = mock(TenantSchemaIterator.class); // unused by approve()
        UUID id = UUID.randomUUID();
        McpServerRegistrationResponse approvedResponse =
                new McpServerRegistrationResponse(
                        id,
                        "custom-a",
                        McpServerType.STDIO,
                        "d",
                        true,
                        true,
                        "npx",
                        "[]",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        com.agenticai.core.mcp.McpReviewStatus.APPROVED,
                        null,
                        false,
                        false,
                        false,
                        List.of());
        when(registryService.approveReview(id))
                .thenAnswer(
                        inv -> {
                            assertEquals(
                                    "tenant-a",
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null),
                                    "approveReview must run with the target tenant's context set");
                            return approvedResponse;
                        });
        when(registryService.listAll()).thenReturn(List.of(approvedResponse));
        @SuppressWarnings("unchecked")
        ObjectProvider<McpDynamicConnector> connector = mock(ObjectProvider.class);
        McpDynamicConnector connectorBean = mock(McpDynamicConnector.class);
        when(connector.getIfAvailable()).thenReturn(connectorBean);
        McpRegistryReviewService service =
                new McpRegistryReviewService(registryService, iterator, connector);

        McpServerRegistrationResponse result = service.approve("tenant-a", id);

        assertEquals(id, result.id());
        assertTrue(
                TenantContext.current().isEmpty(),
                "TenantContext must be cleared after the call, not leaked to the caller");
        verify(connectorBean).connect(id);
    }

    @Test
    void approveNeverThrowsWhenNoDynamicConnectorBeanIsAvailable() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpServerRegistrationResponse approvedResponse =
                new McpServerRegistrationResponse(
                        id,
                        "custom-a",
                        McpServerType.STDIO,
                        "d",
                        true,
                        true,
                        "npx",
                        "[]",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        com.agenticai.core.mcp.McpReviewStatus.APPROVED,
                        null,
                        false,
                        false,
                        false,
                        List.of());
        when(registryService.approveReview(id)).thenReturn(approvedResponse);
        when(registryService.listAll()).thenReturn(List.of(approvedResponse));
        @SuppressWarnings("unchecked")
        ObjectProvider<McpDynamicConnector> connector = mock(ObjectProvider.class);
        when(connector.getIfAvailable())
                .thenReturn(
                        null); // degraded/slim context, same as McpRegistryController's own posture
        McpRegistryReviewService service =
                new McpRegistryReviewService(registryService, iterator, connector);

        McpServerRegistrationResponse result = service.approve("tenant-a", id);

        assertEquals(id, result.id());
    }

    @Test
    void rejectRunsWithTheGivenTenantsContextEvictsTheToolCacheAndNeverAttemptsAConnection() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpServerRegistrationResponse rejectedResponse =
                new McpServerRegistrationResponse(
                        id,
                        "custom-a",
                        McpServerType.STDIO,
                        "d",
                        true,
                        true,
                        "npx",
                        "[]",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        com.agenticai.core.mcp.McpReviewStatus.REJECTED,
                        "unverified publisher",
                        false,
                        false,
                        false,
                        List.of());
        when(registryService.rejectReview(eq(id), anyString()))
                .thenAnswer(
                        inv -> {
                            assertEquals(
                                    "tenant-a",
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null));
                            return rejectedResponse;
                        });
        @SuppressWarnings("unchecked")
        ObjectProvider<McpDynamicConnector> connector = mock(ObjectProvider.class);
        McpDynamicConnector connectorBean = mock(McpDynamicConnector.class);
        when(connector.getIfAvailable()).thenReturn(connectorBean);
        McpRegistryReviewService service =
                new McpRegistryReviewService(registryService, iterator, connector);

        McpServerRegistrationResponse result =
                service.reject("tenant-a", id, "unverified publisher");

        assertEquals("unverified publisher", result.reviewRejectionReason());
        assertFalse(TenantContext.current().isPresent());
        // reject() must evict the cached dynamic-tool list (L1 fix - a reject
        // can revoke a previously-APPROVED, currently-CONNECTED registration,
        // and the discovery cache must not keep serving its tools) but must
        // still never attempt a connection - that stays approve()'s job alone.
        verify(connectorBean).evictToolCache();
        verify(connectorBean, never()).connect(any());
    }

    @Test
    void rejectNeverThrowsWhenNoDynamicConnectorBeanIsAvailable() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpServerRegistrationResponse rejectedResponse =
                new McpServerRegistrationResponse(
                        id,
                        "custom-a",
                        McpServerType.STDIO,
                        "d",
                        true,
                        true,
                        "npx",
                        "[]",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        com.agenticai.core.mcp.McpReviewStatus.REJECTED,
                        "unverified publisher",
                        false,
                        false,
                        false,
                        List.of());
        when(registryService.rejectReview(eq(id), anyString())).thenReturn(rejectedResponse);
        @SuppressWarnings("unchecked")
        ObjectProvider<McpDynamicConnector> connector = mock(ObjectProvider.class);
        when(connector.getIfAvailable())
                .thenReturn(null); // degraded/slim context, same as approve()'s own posture
        McpRegistryReviewService service =
                new McpRegistryReviewService(registryService, iterator, connector);

        McpServerRegistrationResponse result =
                service.reject("tenant-a", id, "unverified publisher");

        assertEquals(id, result.id());
    }
}
