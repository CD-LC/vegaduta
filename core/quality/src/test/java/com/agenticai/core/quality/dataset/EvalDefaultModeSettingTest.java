package com.agenticai.core.quality.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.quality.QualitySettings;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@code quality.eval.defaultMode}, the workspace's own default for how eval runs replay: an ordinary, non-security,
 * workspace-level setting that an admin can switch back to the quick text-only replay, whose platform value is the
 * deployment's {@code app.quality.eval.default-mode} (the full pipeline as shipped).
 */
class EvalDefaultModeSettingTest {

    private static final String TENANT = "tenant-a";

    private InMemorySettingsStore store;
    private SettingsProperties properties;
    private MockEnvironment env;

    @BeforeEach
    void setUp() {
        store = new InMemorySettingsStore();
        properties = new SettingsProperties();
        properties.setCacheTtl(Duration.ZERO);
        env = new MockEnvironment();
        TenantContext.set(TenantContext.of(TENANT, "tester", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private DefaultSettingsResolver resolver() {
        return new DefaultSettingsResolver(
            new SettingsRegistry(List.of(new QualitySettings(env))), store, List.of(), properties, env);
    }

    private SettingDefinition definition() {
        return resolver().registry().require(QualitySettings.EVAL_DEFAULT_MODE);
    }

    @Test
    void itIsAnOrdinaryWorkspaceLevelChoiceBetweenFullAndQuick() {
        SettingDefinition d = definition();

        assertThat(d.type()).isEqualTo(SettingType.ENUM);
        assertThat(d.enumValues()).containsExactly("full", "quick");
        assertThat(d.risk()).as("a cost and coverage choice, not a security one").isNotEqualTo(SettingRisk.SECURITY);
        assertThat(d.risk().requiresReason()).isFalse();
        assertThat(d.scopes()).containsExactly(SettingScope.TENANT);
        assertThat(d.platformProperty()).isEqualTo("app.quality.eval.default-mode");
    }

    @Test
    void theShippedPlatformValueIsFull() {
        assertThat(definition().platformValue()).isEqualTo("full");
        assertThat(resolver().getEnum(QualitySettings.EVAL_DEFAULT_MODE, SettingContext.tenant(), EvalMode.class))
            .isEqualTo(EvalMode.FULL);
    }

    @Test
    void theDeploymentPropertyIsThePlatformValue() {
        env.setProperty("app.quality.eval.default-mode", "QUICK");

        assertThat(definition().platformValue()).as("normalised to the catalog's spelling").isEqualTo("quick");
        assertThat(resolver().getEnum(QualitySettings.EVAL_DEFAULT_MODE, SettingContext.tenant(), EvalMode.class))
            .isEqualTo(EvalMode.QUICK);
    }

    @Test
    void aWorkspaceOverrideSwitchesOnlyThatWorkspace() {
        store.saveCurrent(new SettingOverride(
            SettingScope.TENANT, "", QualitySettings.EVAL_DEFAULT_MODE, "quick", 1, "tester", Instant.now()));
        DefaultSettingsResolver resolver = resolver();

        assertThat(resolver.getEnum(QualitySettings.EVAL_DEFAULT_MODE, SettingContext.tenant(), EvalMode.class))
            .isEqualTo(EvalMode.QUICK);
        TenantContext.set(TenantContext.of("tenant-b", "tester", Set.of()));
        assertThat(resolver.getEnum(QualitySettings.EVAL_DEFAULT_MODE, SettingContext.tenant(), EvalMode.class))
            .isEqualTo(EvalMode.FULL);
    }

    @Test
    void anOperatorPinMakesTheWorkspaceOverrideInert() {
        properties.getLocked().put(QualitySettings.EVAL_DEFAULT_MODE, true);
        store.saveCurrent(new SettingOverride(
            SettingScope.TENANT, "", QualitySettings.EVAL_DEFAULT_MODE, "quick", 1, "tester", Instant.now()));

        assertThat(resolver().getEnum(QualitySettings.EVAL_DEFAULT_MODE, SettingContext.tenant(), EvalMode.class))
            .isEqualTo(EvalMode.FULL);
    }
}
