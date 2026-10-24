package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The MCP client timeouts ({@code app.tools.mcp.*}) are settings, and the manager hands out what they say. */
class McpClientTimeoutPropertiesTest extends DynamicMcpConnectionManagerTestBase {

    @Test
    void theShippedDefaultsAreTheTimeoutsTheClientAlwaysHad() {
        McpClientProperties p = new McpClientProperties();
        assertThat(p.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.testTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void theManagerUsesTheConfiguredRequestAndTestTimeouts() {
        DynamicMcpConnectionManager manager = newManager(
            org.mockito.Mockito.mock(com.agenticai.core.mcp.McpRegistryService.class),
            org.mockito.Mockito.mock(com.agenticai.core.tenancy.TenantSchemaIterator.class),
            mockProviderOf(null));
        assertThat(manager.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(manager.testTimeout()).isEqualTo(Duration.ofSeconds(20));

        McpClientProperties configured = new McpClientProperties();
        configured.setRequestTimeoutSeconds(15);
        configured.setTestTimeoutSeconds(5);
        manager.useClientProperties(configured);

        assertThat(manager.requestTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(manager.testTimeout()).isEqualTo(Duration.ofSeconds(5));
    }
}
