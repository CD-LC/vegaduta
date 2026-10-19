package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.mcp.McpRegistryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * 2026-09-28 cross-tenant leak fix, resolver half: a static server the {@link
 * StaticMcpExposurePolicy} hides must never even be discovered (listTools) - with
 * application-mcp.yml's lazy {@code initialized: false} that also means its shared subprocess is
 * never spawned.
 */
class AgentMcpToolResolverStaticExposureTest {

    private static McpSyncClient clientWithNoTools(String name) {
        McpSyncClient client = StaticMcpExposurePolicyTest.client(name);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));
        return client;
    }

    private static AgentMcpToolResolver resolver(
            List<McpSyncClient> clients, StaticMcpExposurePolicy policy) {
        McpRegistryService registry = mock(McpRegistryService.class);
        when(registry.anyServerEnabled()).thenReturn(true);
        return new AgentMcpToolResolver(
                // toolGuard is only reached when a callback exists; these servers advertise none.
                null,
                StaticMcpExposurePolicyTest.<ToolCallbackProvider>providerOf(
                        mock(ToolCallbackProvider.class)),
                registry,
                StaticMcpExposurePolicyTest.<DynamicMcpConnectionManager>providerOf(null),
                StaticMcpExposurePolicyTest.<List<McpSyncClient>>providerOf(clients),
                StaticMcpExposurePolicyTest.<PlatformIssueService>providerOf(null),
                new SimpleMeterRegistry(),
                Duration.ofSeconds(20),
                () -> policy);
    }

    private static void resolve(AgentMcpToolResolver resolver) {
        resolver.resolveStaticMcpTools(
                UUID.randomUUID(), "s", new AtomicInteger(), "hi", null, false);
    }

    @Test
    void statefulStaticServersAreNeverDiscoveredByDefault() {
        McpSyncClient filesystem = clientWithNoTools("filesystem");
        McpSyncClient memory = clientWithNoTools("memory");
        McpSyncClient puppeteer = clientWithNoTools("puppeteer");
        McpSyncClient sequential = clientWithNoTools("sequential-thinking");

        resolve(
                resolver(
                        List.of(filesystem, memory, puppeteer, sequential),
                        StaticMcpExposurePolicy.FAIL_CLOSED));

        verify(filesystem, never()).listTools();
        verify(memory, never()).listTools();
        verify(puppeteer, never()).listTools();
        verify(sequential).listTools();
    }

    @Test
    void sharedStatefulFlagRestoresDiscoveryOfEveryStaticServer() {
        McpSyncClient filesystem = clientWithNoTools("filesystem");
        McpSyncClient sequential = clientWithNoTools("sequential-thinking");

        resolve(
                resolver(
                        List.of(filesystem, sequential),
                        new StaticMcpExposurePolicy(List.of("sequential-thinking"), () -> true)));

        verify(filesystem).listTools();
        verify(sequential).listTools();
    }

    @Test
    void flippingTheFlagNeverServesTheOtherShapesCachedList() {
        McpSyncClient filesystem = clientWithNoTools("filesystem");
        AtomicBoolean flag = new AtomicBoolean(false);
        AgentMcpToolResolver resolver =
                resolver(
                        List.of(filesystem, clientWithNoTools("sequential-thinking")),
                        new StaticMcpExposurePolicy(List.of("sequential-thinking"), flag::get));

        resolve(resolver);
        verify(filesystem, never()).listTools();

        flag.set(true);
        resolve(resolver); // separate cache key -> fresh discovery including filesystem, within the
        // same TTL window
        verify(filesystem).listTools();
    }

    @Test
    void noExposedStaticServerMeansNoToolsAndNoDiscovery() {
        McpSyncClient memory = clientWithNoTools("memory");

        AgentMcpToolResolver resolver =
                resolver(List.of(memory), StaticMcpExposurePolicy.FAIL_CLOSED);

        assertTrue(
                resolver.resolveStaticMcpTools(
                                UUID.randomUUID(), "s", new AtomicInteger(), "hi", null, false)
                        .isEmpty());
        verify(memory, never()).listTools();
    }
}
