package com.agenticai.core.approval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ApprovalExpiryProperties} and {@link ApprovalRecoveryProperties}: there is no application-wide
 * {@code @ConfigurationPropertiesScan}, so each module registers the properties class it owns.
 */
@Configuration
@EnableConfigurationProperties({ApprovalExpiryProperties.class, ApprovalRecoveryProperties.class})
class ApprovalExpiryConfiguration {}
