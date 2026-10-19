package com.agenticai.core.agents.tools;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code mcp-stdio-sidecar.*} into {@link McpStdioSidecarProperties}. Always active - {@link
 * DynamicMcpConnectionManager} takes the properties bean unconditionally (it just reads {@code
 * isEnabled()} internally), same split {@code ToolSandboxConfiguration}/{@code
 * ToolSandboxProperties} already uses for the identical "off by default" shape, copied deliberately
 * rather than reinvented.
 */
@Configuration
@EnableConfigurationProperties(McpStdioSidecarProperties.class)
public class McpStdioSidecarConfiguration {}
