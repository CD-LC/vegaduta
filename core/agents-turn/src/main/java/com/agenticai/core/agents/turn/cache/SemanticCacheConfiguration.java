package com.agenticai.core.agents.turn.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SemanticCacheProperties}: there is no application-wide {@code @ConfigurationPropertiesScan}, so each
 * module registers the properties class it owns. Not behind the {@code knowledge} profile, because the settings catalog
 * lists the cache's knobs in every shape.
 */
@Configuration
@EnableConfigurationProperties(SemanticCacheProperties.class)
class SemanticCacheConfiguration {}
