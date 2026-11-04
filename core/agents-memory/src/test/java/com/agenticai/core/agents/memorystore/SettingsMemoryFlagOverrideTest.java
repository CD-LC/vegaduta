package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.settings.SettingsResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The tenant and agent levels of {@code memory.unified.enabled}: platform AND tenant AND agent, any level vetoes. */
class SettingsMemoryFlagOverrideTest {

    private static final String TENANT = "tenant-a";
    private final UUID agent = UUID.randomUUID();
    private final InMemorySettingsStore store = new InMemorySettingsStore();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ApplicationContextRunner runner(boolean platform) {
        MemoryUnifiedProperties props = new MemoryUnifiedProperties();
        props.setEnabled(platform);
        SettingsProperties noCache = new SettingsProperties();
        noCache.setCacheTtl(Duration.ZERO);
        SettingsResolver resolver =
                new DefaultSettingsResolver(
                        new SettingsRegistry(List.of(new MemoryUnifiedSettings(props))), store, List.of(), noCache, null);
        return new ApplicationContextRunner()
                .withUserConfiguration(MemoryStoreConfiguration.class)
                .withBean(SettingsResolver.class, () -> resolver)
                .withPropertyValues("app.memory.unified.enabled=" + platform);
    }

    private void set(SettingScope scope, String scopeId, boolean value) {
        TenantContext.set(TenantContext.of(TENANT, "u", Set.of()));
        store.saveCurrent(new SettingOverride(scope, scopeId, MemoryUnifiedSettings.ENABLED_KEY, value, 1, "admin", Instant.now()));
    }

    private boolean gate(boolean platform) {
        boolean[] out = new boolean[1];
        TenantContext.set(TenantContext.of(TENANT, "u", Set.of()));
        runner(platform).run(ctx -> out[0] = ctx.getBean(MemoryUnifiedGate.class).enabled(agent));
        return out[0];
    }

    @Test
    void catalogEntryFollowsThePlatformPropertyAndIsTenantAndAgentScoped() {
        MemoryUnifiedProperties props = new MemoryUnifiedProperties();
        var def = new MemoryUnifiedSettings(props).definitions().get(0);
        assertThat(def.key()).isEqualTo("memory.unified.enabled");
        assertThat(def.platformValue()).isEqualTo(false);
        props.setEnabled(true);
        assertThat(def.platformValue()).isEqualTo(true);
        assertThat(def.allows(SettingScope.TENANT)).isTrue();
        assertThat(def.allows(SettingScope.AGENT)).isTrue();
        assertThat(def.platformProperty()).isEqualTo("app.memory.unified.enabled");
    }

    @Test
    void platformOnAndNoOverridesIsOn() {
        assertThat(gate(true)).isTrue();
    }

    @Test
    void platformOffStaysOffEvenWhenTenantAndAgentSayOn() {
        set(SettingScope.TENANT, "", true);
        set(SettingScope.AGENT, agent.toString(), true);
        assertThat(gate(false)).isFalse();
    }

    @Test
    void aTenantNoVetoesEvenWhenTheAgentSaysYes() {
        set(SettingScope.TENANT, "", false);
        set(SettingScope.AGENT, agent.toString(), true);
        assertThat(gate(true)).isFalse();
    }

    @Test
    void anAgentNoVetoesEvenWhenTheTenantSaysYes() {
        set(SettingScope.TENANT, "", true);
        set(SettingScope.AGENT, agent.toString(), false);
        assertThat(gate(true)).isFalse();
    }

    @Test
    void anotherAgentsOverrideDoesNotAffectThisOne() {
        set(SettingScope.AGENT, UUID.randomUUID().toString(), false);
        assertThat(gate(true)).isTrue();
    }
}
