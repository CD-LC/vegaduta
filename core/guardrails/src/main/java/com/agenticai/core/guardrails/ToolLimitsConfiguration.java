package com.agenticai.core.guardrails;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ToolOutputProperties} and {@link ToolStepLimitProperties}: there is no
 * application-wide {@code @ConfigurationPropertiesScan}, so each module registers the properties
 * classes it owns.
 */
@Configuration
@EnableConfigurationProperties({ToolOutputProperties.class, ToolStepLimitProperties.class})
class ToolLimitsConfiguration {}
