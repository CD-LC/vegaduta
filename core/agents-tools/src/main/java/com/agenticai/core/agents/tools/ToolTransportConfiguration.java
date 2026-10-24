package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.openapi.OpenApiToolProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link McpClientProperties} and {@link OpenApiToolProperties}: there is no application-wide
 * {@code @ConfigurationPropertiesScan}, so each module registers the properties classes it owns.
 */
@Configuration
@EnableConfigurationProperties({McpClientProperties.class, OpenApiToolProperties.class})
public class ToolTransportConfiguration {}
