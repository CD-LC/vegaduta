package com.agenticai.core.billing.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link RazorpayPlanCatalog} - same per-module {@code @EnableConfigurationProperties}
 * pattern as {@code McpRegistryConfiguration}/{@code CodeIntelConfiguration} (there is no
 * application-wide {@code @ConfigurationPropertiesScan}, so each module that owns a
 * {@code @ConfigurationProperties} class registers it itself).
 */
@Configuration
@EnableConfigurationProperties(RazorpayPlanCatalog.class)
public class RazorpayBillingConfiguration {}
