package com.agenticai.core.mcp.registry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code mcp.registry.*} into {@link RegistryProperties} for {@link
 * OfficialMcpRegistryClient} to read - same {@code @EnableConfigurationProperties}
 * pattern this codebase already uses for {@code CodeIntelProperties} /
 * {@code StorageProperties}.
 */
@Configuration
@EnableConfigurationProperties(RegistryProperties.class)
public class McpRegistryConfiguration {
}
