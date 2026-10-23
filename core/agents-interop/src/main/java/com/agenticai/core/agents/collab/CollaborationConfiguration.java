package com.agenticai.core.agents.collab;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link CollaborationProperties}: there is no application-wide
 * {@code @ConfigurationPropertiesScan}, so each module registers the properties class it owns.
 */
@Configuration
@EnableConfigurationProperties(CollaborationProperties.class)
class CollaborationConfiguration {}
