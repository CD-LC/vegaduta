package com.agenticai.core.agents.vision;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link VisionProperties} - same per-module {@code @EnableConfigurationProperties}
 * pattern as {@code RazorpayBillingConfiguration} (there is no application-wide
 * {@code @ConfigurationPropertiesScan}, so each module that owns a {@code @ConfigurationProperties}
 * class registers it itself).
 */
@Configuration
@EnableConfigurationProperties(VisionProperties.class)
public class VisionConfiguration {}
