package com.agenticai.core.agents.auxiliary;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AuxiliaryModelProperties}: there is no application-wide
 * {@code @ConfigurationPropertiesScan}, so each module registers the properties class it owns.
 */
@Configuration
@EnableConfigurationProperties(AuxiliaryModelProperties.class)
class AuxiliaryModelConfiguration {}
