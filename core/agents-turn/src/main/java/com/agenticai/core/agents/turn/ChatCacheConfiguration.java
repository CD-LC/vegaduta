package com.agenticai.core.agents.turn;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ChatCacheProperties}: there is no application-wide
 * {@code @ConfigurationPropertiesScan}, so each module registers the properties class it owns.
 */
@Configuration
@EnableConfigurationProperties(ChatCacheProperties.class)
class ChatCacheConfiguration {}
