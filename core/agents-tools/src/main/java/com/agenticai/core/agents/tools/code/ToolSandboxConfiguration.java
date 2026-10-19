package com.agenticai.core.agents.tools.code;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code tool-sandbox.*} into {@link ToolSandboxProperties}. Always active - the properties
 * must exist unconditionally so that {@link ToolSandboxClient}'s {@code @ConditionalOnProperty} has
 * something to read, and so {@code CustomCodeToolService} can quote the configured caps in its
 * error messages even while the feature is off. It creates no client itself; that bean exists only
 * when {@code tool-sandbox.enabled=true}.
 *
 * <p>Same split {@code CodeIntelConfiguration}/{@code CodeIntelClient} already uses, copied
 * deliberately rather than reinvented.
 */
@Configuration
@EnableConfigurationProperties(ToolSandboxProperties.class)
public class ToolSandboxConfiguration {}
