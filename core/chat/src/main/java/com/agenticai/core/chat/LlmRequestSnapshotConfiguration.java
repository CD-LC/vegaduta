package com.agenticai.core.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link LlmRequestSnapshotProperties} and hands the bound values to {@link
 * LlmRequestSnapshotter}, which is static because {@link LlmMetricsAdvisor} is built by hand in every
 * provider factory and cannot be injected.
 */
@Configuration
@EnableConfigurationProperties(LlmRequestSnapshotProperties.class)
class LlmRequestSnapshotConfiguration {

    @Autowired
    void applyTo(LlmRequestSnapshotProperties properties) {
        LlmRequestSnapshotter.configure(properties);
    }
}
