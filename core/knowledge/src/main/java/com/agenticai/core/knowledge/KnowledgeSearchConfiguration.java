package com.agenticai.core.knowledge;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Binds {@code app.knowledge.search.*} into {@link KnowledgeSearchProperties}. */
@Configuration
@Profile("knowledge")
@EnableConfigurationProperties(KnowledgeSearchProperties.class)
public class KnowledgeSearchConfiguration {
}
