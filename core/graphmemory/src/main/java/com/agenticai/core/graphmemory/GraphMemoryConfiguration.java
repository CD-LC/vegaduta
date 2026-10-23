package com.agenticai.core.graphmemory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code app.graph-memory.*} into {@link GraphMemoryProperties}. */
@Configuration
@EnableConfigurationProperties(GraphMemoryProperties.class)
public class GraphMemoryConfiguration {
}
