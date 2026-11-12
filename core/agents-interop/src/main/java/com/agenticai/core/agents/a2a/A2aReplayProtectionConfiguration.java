package com.agenticai.core.agents.a2a;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link A2aReplayProtectionProperties} (no global properties scan exists). */
@Configuration
@EnableConfigurationProperties(A2aReplayProtectionProperties.class)
public class A2aReplayProtectionConfiguration {}
