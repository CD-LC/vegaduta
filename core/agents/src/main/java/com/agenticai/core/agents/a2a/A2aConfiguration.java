package com.agenticai.core.agents.a2a;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link A2aProperties} ({@code app.a2a.*}). Kept as its own tiny
 * configuration class rather than tacking {@code @EnableConfigurationProperties}
 * onto a controller, matching how {@code VisionConfiguration} registers
 * {@code VisionProperties} - a controller that is itself
 * {@code @ConditionalOnProperty}-gated on one of these properties must not be
 * the thing that makes the properties bean exist.
 */
@Configuration
@EnableConfigurationProperties(A2aProperties.class)
public class A2aConfiguration {
}
