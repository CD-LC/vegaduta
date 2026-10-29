package com.agenticai.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link SettingsRegistry}: declaration-order lookup, {@code require}'s not-found exception, and
 * the two startup-failure guards - a key declared twice and a key that is a prefix of another
 * (their i18n subtrees would clash).
 */
class SettingsRegistryTest {

    private static SettingDefinition definition(String key) {
        return SettingDefinition.builder(key, "g", SettingType.INT)
                .scopes(SettingScope.TENANT)
                .platform(() -> 1)
                .build();
    }

    @Test
    void ofFindRequireAndAllWorkInDeclarationOrder() {
        SettingsRegistry registry = SettingsRegistry.of(definition("b.two"), definition("a.one"));

        assertThat(registry.find("a.one")).isPresent();
        assertThat(registry.find("missing.key")).isEmpty();
        assertThat(registry.require("b.two").key()).isEqualTo("b.two");
        assertThat(registry.all())
                .extracting(SettingDefinition::key)
                .containsExactly("b.two", "a.one");
    }

    @Test
    void requireThrowsSettingNotFoundForAnUnknownKey() {
        SettingsRegistry registry = SettingsRegistry.of(definition("a.one"));

        assertThatThrownBy(() -> registry.require("no.such"))
                .isInstanceOfSatisfying(
                        SettingNotFoundException.class,
                        e -> assertThat(e.key()).isEqualTo("no.such"));
    }

    @Test
    void aKeyDeclaredTwiceFailsConstruction() {
        SettingsContributor contributor = () -> List.of(definition("a.one"), definition("a.one"));

        assertThatThrownBy(() -> new SettingsRegistry(List.of(contributor)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declared twice");
    }

    @Test
    void aContributorMayDeclareZeroKeys() {
        SettingsContributor empty = () -> List.of();

        SettingsRegistry registry = new SettingsRegistry(List.of(empty));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void aKeyThatIsAPrefixOfAnotherFailsConstruction() {
        assertThatThrownBy(
                        () ->
                                SettingsRegistry.of(
                                        definition("chat.retry"),
                                        definition("chat.retry.maxAttempts")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prefix");
    }
}
