package com.agenticai.core.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingValue;
import com.agenticai.core.settings.SettingsContributor;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.settings.Strictness;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@code app.guardrails.supervised.gate-read-only} and its catalog key {@code guardrails.supervised.readOnlyCalls}: whether
 * the SUPERVISED tier still asks a human before an explicitly classified read-only call. It is a SECURITY key, so a workspace
 * or an agent can only choose the stricter side ({@code gate}); a platform {@code true} (the base value) is never loosened, by
 * a stored row written around the API, an unknown value or a resolver that fails.
 */
class GuardrailSettingsSupervisedTest {

    private static final String KEY = "guardrails.supervised.readOnlyCalls";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OTHER_AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private InMemorySettingsStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySettingsStore();
        TenantContext.set(TenantContext.of("tenant-a", "tester", Set.of()));
    }

    @AfterEach
    void tearDown() {
        GuardrailSettings.clear();
        TenantContext.clear();
    }

    private static GuardrailTuningProperties platform(boolean gateReadOnly) {
        return new Binder(new MapConfigurationPropertySource(Map.of("app.guardrails.supervised.gate-read-only", String.valueOf(gateReadOnly))))
            .bind("app.guardrails", GuardrailTuningProperties.class)
            .orElseGet(GuardrailTuningProperties::new);
    }

    private DefaultSettingsResolver install(GuardrailTuningProperties tuning) {
        SettingsContributor contributor = () -> GuardrailSettingDefinitions.all(tuning);
        SettingsProperties props = new SettingsProperties();
        props.setCacheTtl(Duration.ZERO);
        DefaultSettingsResolver resolver = new DefaultSettingsResolver(
            new SettingsRegistry(List.of(contributor)), store, List.of(), props, new MockEnvironment());
        GuardrailSettings.use(resolver);
        return resolver;
    }

    private void tenant(Object value) {
        store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", KEY, value, 1, "tester", Instant.now()));
    }

    private void agent(UUID id, Object value) {
        store.saveCurrent(new SettingOverride(SettingScope.AGENT, id.toString(), KEY, value, 1, "tester", Instant.now()));
    }

    // ------------------------------------------------------------------------------ defaults

    @Test
    void theCodeDefaultGatesEveryCall() {
        GuardrailTuningProperties code = new GuardrailTuningProperties();

        assertThat(code.getSupervised().isGateReadOnly()).isTrue();
        assertThat(GuardrailSettings.supervisedGatesReadOnly(code, AGENT)).as("no resolver set").isTrue();
    }

    @Test
    void theYmlKeyBindsToTheTypedProperty() {
        assertThat(platform(false).getSupervised().isGateReadOnly()).isFalse();
        assertThat(platform(true).getSupervised().isGateReadOnly()).isTrue();
    }

    // ------------------------------------------------------------------------------ the platform is true: nothing loosens it

    @Test
    void aPlatformTrueIsNeverLoosenedByAStoredRow() {
        GuardrailTuningProperties tuning = platform(true);
        install(tuning);
        tenant("auto-allow");
        agent(AGENT, "auto-allow");

        assertThat(GuardrailSettings.supervisedGatesReadOnly(tuning, AGENT)).isTrue();
        assertThat(GuardrailSettings.supervisedGatesReadOnly(tuning, OTHER_AGENT)).isTrue();
        assertThat(GuardrailSettings.supervisedGatesReadOnly(tuning, null)).isTrue();
    }

    @Test
    void aResolverThatAnswersAutoAllowCannotLoosenAPlatformTrueAtTheUseSite() {
        GuardrailTuningProperties tuning = platform(true);
        GuardrailSettings.use(new FixedResolver("auto-allow"));

        assertThat(GuardrailSettings.supervisedGatesReadOnly(tuning, AGENT)).isTrue();
    }

    // ------------------------------------------------------------------------------ the platform is false (personal)

    @Test
    void aPlatformFalseLetsReadOnlyCallsThroughWithNoOverride() {
        GuardrailTuningProperties tuning = platform(false);
        install(tuning);

        assertThat(GuardrailSettings.supervisedGatesReadOnly(tuning, AGENT)).isFalse();
    }

    @Test
    void aWorkspaceCanChooseToGateAgain() {
        GuardrailTuningProperties tuning = platform(false);
        install(tuning);
        tenant("gate");

        assertThat(GuardrailSettings.supervisedGatesReadOnly(tuning, AGENT)).isTrue();
        assertThat(GuardrailSettings.supervisedGatesReadOnly(tuning, OTHER_AGENT)).isTrue();
    }

    @Test
    void anAgentCanChooseToGateAgainForItselfOnly() {
        GuardrailTuningProperties tuning = platform(false);
        install(tuning);
        agent(AGENT, "gate");

        assertThat(GuardrailSettings.supervisedGatesReadOnly(tuning, AGENT)).isTrue();
        assertThat(GuardrailSettings.supervisedGatesReadOnly(tuning, OTHER_AGENT)).isFalse();
    }

    @Test
    void theStricterOfWorkspaceAndAgentWins() {
        GuardrailTuningProperties tuning = platform(false);
        install(tuning);
        tenant("gate");
        agent(AGENT, "auto-allow");

        assertThat(GuardrailSettings.supervisedGatesReadOnly(tuning, AGENT)).as("the workspace's gate stands").isTrue();
    }

    @Test
    void aValueThatIsNotARecognisedChoiceGates() {
        GuardrailTuningProperties tuning = platform(false);
        GuardrailSettings.use(new FixedResolver("whatever"));

        assertThat(GuardrailSettings.supervisedGatesReadOnly(tuning, AGENT)).isTrue();
    }

    @Test
    void aResolverThatFailsFallsBackToThePlatformValue() {
        GuardrailTuningProperties off = platform(false);
        GuardrailTuningProperties on = platform(true);
        GuardrailSettings.use(new ThrowingResolver());

        assertThat(GuardrailSettings.supervisedGatesReadOnly(off, AGENT)).as("a settings outage changes nothing").isFalse();
        assertThat(GuardrailSettings.supervisedGatesReadOnly(on, AGENT)).isTrue();
    }

    // ------------------------------------------------------------------------------ the catalog entry

    @Test
    void theCatalogEntryIsASecurityKeyWhoseStricterSideIsGate() {
        SettingDefinition definition = GuardrailSettingDefinitions.all(platform(true)).stream()
            .filter(d -> d.key().equals(KEY)).findFirst().orElseThrow();

        assertThat(definition.risk()).isEqualTo(SettingRisk.SECURITY);
        assertThat(definition.strictness()).isEqualTo(Strictness.ENUM_ORDER);
        assertThat(definition.enumValues()).containsExactly("auto-allow", "gate");
        assertThat(definition.allows(SettingScope.TENANT)).isTrue();
        assertThat(definition.allows(SettingScope.AGENT)).isTrue();
        assertThat(definition.platformProperty()).isEqualTo("app.guardrails.supervised.gate-read-only");
        assertThat(definition.isAtLeastAsStrict("gate", "auto-allow")).isTrue();
        assertThat(definition.isAtLeastAsStrict("auto-allow", "gate")).as("loosening is refused on write").isFalse();
    }

    @Test
    void theCatalogPlatformValueFollowsTheTypedProperty() {
        assertThat(platformValue(platform(true))).isEqualTo("gate");
        assertThat(platformValue(platform(false))).isEqualTo("auto-allow");
    }

    private static Object platformValue(GuardrailTuningProperties tuning) {
        return GuardrailSettingDefinitions.all(tuning).stream()
            .filter(d -> d.key().equals(KEY)).findFirst().orElseThrow().platformValue();
    }

    // ------------------------------------------------------------------------------ resolvers

    private static final class FixedResolver implements SettingsResolver {
        private final Object value;

        FixedResolver(Object value) {
            this.value = value;
        }

        @Override
        public SettingValue get(String key, SettingContext ctx) {
            return new SettingValue(value, com.agenticai.core.settings.SettingSource.TENANT, false, null);
        }

        @Override
        public Map<String, SettingValue> resolveAll(SettingContext ctx) {
            return Map.of();
        }

        @Override
        public SettingsRegistry registry() {
            return new SettingsRegistry(List.of());
        }
    }

    private static final class ThrowingResolver implements SettingsResolver {
        @Override
        public SettingValue get(String key, SettingContext ctx) {
            throw new IllegalStateException("settings unavailable");
        }

        @Override
        public Map<String, SettingValue> resolveAll(SettingContext ctx) {
            throw new IllegalStateException("settings unavailable");
        }

        @Override
        public SettingsRegistry registry() {
            return new SettingsRegistry(List.of());
        }
    }
}
