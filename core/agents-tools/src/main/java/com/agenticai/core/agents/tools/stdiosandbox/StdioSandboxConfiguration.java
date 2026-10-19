package com.agenticai.core.agents.tools.stdiosandbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code app.mcp.stdio.sandbox*} into {@link StdioSandboxProperties}. */
@Configuration
@EnableConfigurationProperties(StdioSandboxProperties.class)
public class StdioSandboxConfiguration {}
