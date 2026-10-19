package com.agenticai.core.billing.invoice;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link InvoiceSellerProperties} - same per-module
 * {@code @EnableConfigurationProperties} pattern as {@code RazorpayBillingConfiguration} (there is
 * no application-wide {@code @ConfigurationPropertiesScan}).
 */
@Configuration
@EnableConfigurationProperties(InvoiceSellerProperties.class)
public class InvoiceConfiguration {}
