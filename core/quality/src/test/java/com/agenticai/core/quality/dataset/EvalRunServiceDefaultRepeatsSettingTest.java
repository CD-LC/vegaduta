package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.QualitySettings;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@code quality.eval.defaultRepeats} on the real trigger path: a run that names no repetition count gets the workspace's
 * setting, else the platform's; an explicit count always wins; the bounds still clamp; a pinned key ignores the workspace;
 * another workspace keeps the platform value.
 */
class EvalRunServiceDefaultRepeatsSettingTest extends EvalRunServiceFixture {

    private InMemorySettingsStore store;
    private SettingsProperties settingsProperties;

    @BeforeEach
    void wire() {
        store = new InMemorySettingsStore();
        settingsProperties = new SettingsProperties();
        settingsProperties.setCacheTtl(Duration.ZERO);
        when(runExecutor.trySubmit(any())).thenReturn(true);
        service.defaultRepeats = 3; // the platform value (app.quality.eval.default-repeats)
    }

    private void attach() {
        MockEnvironment env = new MockEnvironment().withProperty("app.quality.eval.default-repeats", "3");
        service.settings = new DefaultSettingsResolver(
                new SettingsRegistry(List.of(new QualitySettings(env))), store, List.of(), settingsProperties, env);
    }

    private void tenantOverride(Object value) {
        store.saveCurrent(new SettingOverride(
                SettingScope.TENANT, "", QualitySettings.EVAL_DEFAULT_REPEATS, value, 1, "tester", Instant.now()));
    }

    private int repeatsOfUnnamedTrigger() {
        return service.triggerRun(AGENT_ID, DATASET_ID, options(null, null, null, null, null)).getRepeats();
    }

    @Test
    void withNoOverrideThePlatformValueApplies() {
        attach();
        assertEquals(3, repeatsOfUnnamedTrigger());
    }

    @Test
    void aWorkspaceOverrideChangesTheRepeatsOfARunThatNamesNone() {
        attach();
        tenantOverride(5);
        assertEquals(5, repeatsOfUnnamedTrigger());
        tenantOverride(1);
        assertEquals(1, repeatsOfUnnamedTrigger());
    }

    @Test
    void anExplicitRepeatCountBeatsTheWorkspaceSetting() {
        attach();
        tenantOverride(5);
        assertEquals(2, service.triggerRun(AGENT_ID, DATASET_ID, options(null, null, 2, null, null)).getRepeats());
    }

    @Test
    void aPinnedKeyIgnoresTheWorkspaceOverride() {
        settingsProperties.getLocked().put(QualitySettings.EVAL_DEFAULT_REPEATS, true);
        attach();
        tenantOverride(5);
        assertEquals(3, repeatsOfUnnamedTrigger());
    }

    @Test
    void anotherWorkspaceKeepsThePlatformValue() {
        attach();
        tenantOverride(5);
        TenantContext.set(TenantContext.of("tenant-b", "tester", Set.of()));
        assertEquals(3, repeatsOfUnnamedTrigger());
    }

    @Test
    void anAgentLevelRowOfAWorkspaceLevelKeyIsIgnored() {
        attach();
        store.saveCurrent(new SettingOverride(SettingScope.AGENT, UUID.randomUUID().toString(),
                QualitySettings.EVAL_DEFAULT_REPEATS, 5, 1, "tester", Instant.now()));
        assertEquals(3, repeatsOfUnnamedTrigger());
    }

    @Test
    void aResolverThatFailsGivesThePlatformValue() {
        service.settings = new DefaultSettingsResolver(
                new SettingsRegistry(List.of()), store, List.of(), settingsProperties, new MockEnvironment()); // key unknown: throws
        assertEquals(3, repeatsOfUnnamedTrigger());
    }
}
