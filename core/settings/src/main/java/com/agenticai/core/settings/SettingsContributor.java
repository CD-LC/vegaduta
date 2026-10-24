package com.agenticai.core.settings;

import java.util.List;

/**
 * A module's catalog entries. Implement as a Spring bean in the module that owns the behaviour (and its property
 * classes); the {@link SettingsRegistry} collects every contributor. A duplicate key fails startup.
 *
 * <p>An entry whose overrides are stored elsewhere than {@code setting_override} (the runtime limits live on the
 * {@code agent} table and in {@code tenant_runtime_limits}) also provides a {@link SettingBackend}.
 */
public interface SettingsContributor {

    List<SettingDefinition> definitions();
}
