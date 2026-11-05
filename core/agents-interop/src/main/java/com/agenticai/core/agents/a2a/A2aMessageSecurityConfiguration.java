package com.agenticai.core.agents.a2a;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link A2aMessageSecurityProperties} (no global properties scan exists). */
@Configuration
@EnableConfigurationProperties(A2aMessageSecurityProperties.class)
public class A2aMessageSecurityConfiguration {}
