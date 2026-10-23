package com.agenticai.core.agents.memory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link MemoryLimitsProperties}: there is no application-wide
 * {@code @ConfigurationPropertiesScan}, so each module registers the properties class it owns.
 */
@Configuration
@EnableConfigurationProperties(MemoryLimitsProperties.class)
class MemoryLimitsConfiguration {}
