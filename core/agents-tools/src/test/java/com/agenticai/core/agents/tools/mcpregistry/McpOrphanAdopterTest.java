package com.agenticai.core.agents.tools.mcpregistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.McpConnectionStatus;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpServerRegistration;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

class McpOrphanAdopterTest {

    private McpRegistryService service;
    private TenantSchemaIterator iterator;
    private McpOwnershipRegistry ownership;
    private McpDynamicConnector connector;
    private McpOrphanAdopter adopter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        service = mock(McpRegistryService.class);
        iterator = mock(TenantSchemaIterator.class);
        ownership = mock(McpOwnershipRegistry.class);
        connector = mock(McpDynamicConnector.class);
        ObjectProvider<McpDynamicConnector> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(connector);
        when(ownership.active()).thenReturn(true);
        when(ownership.storeReachable()).thenReturn(true);
        when(ownership.ownerOf(any(), any())).thenReturn(Optional.empty());
        Mockito.doAnswer(
                        i -> {
                            ((Consumer<String>) i.getArgument(0)).accept("t1");
                            return null;
                        })
                .when(iterator)
                .forEachActiveTenantSchema(any());
        adopter = new McpOrphanAdopter(service, iterator, ownership, provider);
    }

    private McpServerRegistration reg(UUID id, McpConnectionStatus status) {
        McpServerRegistration r = mock(McpServerRegistration.class);
        when(r.getId()).thenReturn(id);
        when(r.getConnectionStatus()).thenReturn(status);
        return r;
    }

    @Test
    void adoptsOnlyConnectedOwnerless() {
        UUID ok = UUID.randomUUID();
        UUID err = UUID.randomUUID();
        List<McpServerRegistration> regs =
                List.of(
                        reg(ok, McpConnectionStatus.CONNECTED),
                        reg(err, McpConnectionStatus.ERROR));
        when(service.enabledDynamicRegistrations()).thenReturn(regs);
        adopter.adoptOrphans();
        verify(connector, timeout(2000)).connect(ok);
        verify(connector, never()).connect(err);
    }

    @Test
    void skipsWholeSweepWhenStoreUnreachable() {
        when(ownership.storeReachable()).thenReturn(false);
        List<McpServerRegistration> regs =
                List.of(reg(UUID.randomUUID(), McpConnectionStatus.CONNECTED));
        when(service.enabledDynamicRegistrations()).thenReturn(regs);
        adopter.adoptOrphans();
        verify(connector, never()).connect(any());
    }
}
