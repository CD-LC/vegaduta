package com.agenticai.core.chat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AgentTurnRepairProperties}: there is no application-wide
 * {@code @ConfigurationPropertiesScan}, so each module registers the properties class it owns.
 */
@Configuration
@EnableConfigurationProperties(AgentTurnRepairProperties.class)
class AgentTurnRepairConfiguration {}
