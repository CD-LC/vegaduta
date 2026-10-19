package com.agenticai.core.codeintel;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code code-intel.*} into {@link CodeIntelProperties}. Always active
 * (so the properties are available for {@link CodeIntelClient}'s
 * {@code @ConditionalOnProperty} to read), but it creates no client itself -
 * the client bean exists only when {@code code-intel.enabled=true}. Same
 * {@code @EnableConfigurationProperties} pattern this codebase already uses
 * for {@code StorageProperties}.
 */
@Configuration
@EnableConfigurationProperties(CodeIntelProperties.class)
public class CodeIntelConfiguration {
}
