package com.agenticai.core.security.edition;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Publishes the {@link EditionInfo} bean. Not profile-gated: every shape can say what edition it
 * is.
 */
@Configuration(proxyBeanMethods = false)
class EditionInfoConfiguration {

    @Bean
    EditionInfo editionInfo(Environment environment) {
        return EditionInfo.from(environment);
    }
}
