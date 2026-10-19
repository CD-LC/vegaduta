package com.agenticai.core.providers;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code vertex.pool.*} into {@link VertexPoolProperties}. Same
 * {@code @EnableConfigurationProperties} pattern as {@code RlConfiguration} -
 * there is no application-wide {@code @ConfigurationPropertiesScan}.
 */
@Configuration
@EnableConfigurationProperties(VertexPoolProperties.class)
public class VertexConfiguration {
}
