package com.agenticai.core.agents.tools.scratchpad;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code app.tools.scratchpad.*} into {@link ScratchpadProperties}. Always active - the
 * properties must exist unconditionally so {@code AgentScratchpadService} can quote the configured
 * caps in its error messages even while the feature is off. Same split {@code
 * ToolSandboxConfiguration} uses, copied deliberately.
 */
@Configuration
@EnableConfigurationProperties(ScratchpadProperties.class)
public class ScratchpadConfiguration {}
