package com.agenticai.core.prompts;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the catalog (classpath scan, once), the resolver and its properties. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PromptProperties.class)
public class PromptsConfiguration {

    @Bean
    PromptCatalog promptCatalog(PromptProperties properties) {
        return PromptCatalog.load(
                PromptsConfiguration.class.getClassLoader(),
                properties.getDefaultLocale(),
                properties.getDefaultMaxLength());
    }

    @Bean
    DefaultPromptResolver promptResolver(
            PromptCatalog catalog,
            ObjectProvider<PromptOverrideStore> store,
            PromptProperties properties,
            ObjectProvider<PromptDefaultAlias> aliases) {
        return new DefaultPromptResolver(
                catalog, store.getIfAvailable(), properties, aliases.orderedStream().toList());
    }
}
