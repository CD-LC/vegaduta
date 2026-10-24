package com.agenticai.core.settings;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every catalog entry, by key, in declaration order. Built once from the {@link SettingsContributor} beans; a key
 * declared twice, or a key that is a prefix of another (their i18n subtrees would clash), fails startup.
 */
public final class SettingsRegistry {

    private final Map<String, SettingDefinition> byKey = new LinkedHashMap<>();

    public SettingsRegistry(Collection<? extends SettingsContributor> contributors) {
        for (SettingsContributor c : contributors) {
            for (SettingDefinition d : c.definitions()) {
                if (byKey.putIfAbsent(d.key(), d) != null) {
                    throw new IllegalStateException("Setting key declared twice: " + d.key());
                }
            }
        }
        for (String a : byKey.keySet()) {
            for (String b : byKey.keySet()) {
                if (!a.equals(b) && b.startsWith(a + ".")) {
                    throw new IllegalStateException("Setting key " + a + " is a prefix of " + b);
                }
            }
        }
    }

    public static SettingsRegistry of(SettingDefinition... definitions) {
        return new SettingsRegistry(List.of(() -> List.of(definitions)));
    }

    public Collection<SettingDefinition> all() {
        return byKey.values();
    }

    public Optional<SettingDefinition> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public SettingDefinition require(String key) {
        return find(key).orElseThrow(() -> new SettingNotFoundException(key));
    }
}
