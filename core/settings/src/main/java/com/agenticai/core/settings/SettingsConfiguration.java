package com.agenticai.core.settings;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Wires the registry (every {@link SettingsContributor} bean), the resolver and its properties. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SettingsProperties.class)
public class SettingsConfiguration {

    @Bean
    SettingsRegistry settingsRegistry(ObjectProvider<SettingsContributor> contributors) {
        return new SettingsRegistry(contributors.orderedStream().toList());
    }

    @Bean
    SettingsService settingsService(
            SettingsRegistry registry,
            ObjectProvider<SettingsStore> store,
            ObjectProvider<SettingBackend> backends,
            DefaultSettingsResolver resolver,
            ObjectProvider<com.agenticai.core.audit.AuditLogService> audit,
            ObjectProvider<SettingsAgentAccess> agentAccess,
            SettingsProperties properties) {
        return new SettingsService(
                registry,
                store.getObject(),
                backends.orderedStream().toList(),
                resolver,
                audit.getObject(),
                agentAccess,
                properties);
    }

    @Bean
    DefaultSettingsResolver settingsResolver(
            SettingsRegistry registry,
            ObjectProvider<SettingsStore> store,
            ObjectProvider<SettingBackend> backends,
            SettingsProperties properties,
            Environment environment) {
        List<SettingBackend> list = backends.orderedStream().toList();
        return new DefaultSettingsResolver(registry, store.getIfAvailable(), list, properties, environment);
    }
}
