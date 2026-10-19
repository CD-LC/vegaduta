package com.agenticai.core.agents.tools.mcpapps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.DynamicMcpConnectionManager;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The flag really gates the beans (flag off = no service, no controller, no routes), and the @Value
 * wiring parses.
 */
class McpAppsWiringTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withBean(
                            DynamicMcpConnectionManager.class,
                            () -> mock(DynamicMcpConnectionManager.class))
                    .withBean(AgentService.class, () -> mock(AgentService.class))
                    .withBean(AgentToolProvider.class, () -> mock(AgentToolProvider.class))
                    .withUserConfiguration(McpAppsService.class, McpAppsController.class);

    @Test
    void flagOffByDefaultMeansNoBeans() {
        runner.run(
                ctx -> {
                    assertEquals(0, ctx.getBeansOfType(McpAppsService.class).size());
                    assertEquals(0, ctx.getBeansOfType(McpAppsController.class).size());
                });
        runner.withPropertyValues("app.mcp.apps.enabled=false")
                .run(
                        ctx -> {
                            assertEquals(0, ctx.getBeansOfType(McpAppsService.class).size());
                            assertEquals(0, ctx.getBeansOfType(McpAppsController.class).size());
                        });
    }

    @Test
    void flagOnCreatesBothWithDefaultsAndParsesTheAllowlist() {
        runner.withPropertyValues("app.mcp.apps.enabled=true")
                .run(
                        ctx -> {
                            McpAppsService svc = ctx.getBean(McpAppsService.class);
                            assertEquals(1, ctx.getBeansOfType(McpAppsController.class).size());
                            McpAppsConfig c = svc.config();
                            assertEquals(512 * 1024, c.maxResourceBytes());
                            assertEquals(Duration.ofSeconds(5), c.fetchTimeout());
                            assertEquals(Duration.ofSeconds(60), c.cacheTtl());
                            assertEquals(List.of(), c.allowedDomains());
                            assertTrue(c.httpsImages());
                        });
        runner.withPropertyValues(
                        "app.mcp.apps.enabled=true",
                        "app.mcp.apps.allowed-domains=api.good.example, *.cdn.good.example,not a host",
                        "app.mcp.apps.max-resource-bytes=1000",
                        "app.mcp.apps.allow-https-images=false")
                .run(
                        ctx -> {
                            McpAppsConfig c = ctx.getBean(McpAppsService.class).config();
                            assertEquals(
                                    List.of("api.good.example", "*.cdn.good.example"),
                                    c.allowedDomains());
                            assertEquals(1000, c.maxResourceBytes());
                            assertEquals(false, c.httpsImages());
                        });
    }
}
