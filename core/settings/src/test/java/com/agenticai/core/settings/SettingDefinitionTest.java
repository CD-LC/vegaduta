package com.agenticai.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingDefinitionTest {

    private static SettingDefinition.Builder b(String key, SettingType t) {
        return SettingDefinition.builder(key, "g", t).scopes(SettingScope.TENANT).platform(() -> 1);
    }

    @Test
    void durationPlatformValuesAreConvertedToTheUnit() {
        SettingDefinition s = b("a.b", SettingType.DURATION).unit(SettingUnit.S).platform(() -> Duration.ofMinutes(2)).build();
        SettingDefinition ms = b("a.c", SettingType.DURATION).unit(SettingUnit.MS).platform(() -> Duration.ofSeconds(2)).build();
        assertThat(s.platformValue()).isEqualTo(120L);
        assertThat(ms.platformValue()).isEqualTo(2000L);
    }

    @Test
    void percentDefaultsToZeroToHundred() {
        SettingDefinition p = b("a.p", SettingType.PERCENT).platform(() -> 12.5).build();
        assertThat(p.unit()).isEqualTo(SettingUnit.PERCENT);
        assertThat(p.normalize(100)).isEqualTo(100.0);
        assertThatThrownBy(() -> p.normalize(100.5)).isInstanceOf(SettingInvalidException.class);
        assertThatThrownBy(() -> p.normalize(-1)).isInstanceOf(SettingInvalidException.class);
    }

    @Test
    void integralTypesRejectFractionsAndOverflow() {
        SettingDefinition i = b("a.i", SettingType.INT).build();
        assertThat(i.normalize(5.0)).isEqualTo(5L);
        assertThatThrownBy(() -> i.normalize(5.1)).isInstanceOf(SettingInvalidException.class);
        assertThatThrownBy(() -> i.normalize(Long.MAX_VALUE)).isInstanceOf(SettingInvalidException.class);
        assertThatThrownBy(() -> i.normalize(Boolean.TRUE)).isInstanceOf(SettingInvalidException.class);
        assertThat(b("a.l", SettingType.LONG).build().normalize(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void textLengthBoundsApply() {
        SettingDefinition t = b("a.t", SettingType.TEXT).range(3, 10).build();
        assertThat(t.normalize("hello")).isEqualTo("hello");
        assertThatThrownBy(() -> t.normalize("hi")).isInstanceOf(SettingInvalidException.class);
        assertThatThrownBy(() -> t.normalize("12345678901")).isInstanceOf(SettingInvalidException.class);
        assertThat(b("a.s", SettingType.STRING).build().normalize("  padded ")).isEqualTo("padded");
    }

    @Test
    void shapeErrorsFailAtDeclarationTime() {
        assertThatThrownBy(() -> b("noDots", SettingType.INT).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b("a.e", SettingType.ENUM).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b("a.s", SettingType.INT).risk(SettingRisk.SECURITY).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b("a.s", SettingType.INT).range(5, 1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b("a.d", SettingType.DURATION).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingDefinition.builder("a.n", "g", SettingType.INT).build()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void registryRejectsDuplicatesAndPrefixClashes() {
        SettingDefinition a = b("x.y", SettingType.INT).build();
        SettingDefinition a2 = b("x.y", SettingType.INT).build();
        SettingDefinition deeper = b("x.y.z", SettingType.INT).build();
        assertThatThrownBy(() -> new SettingsRegistry(List.of(() -> List.of(a), () -> List.of(a2)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SettingsRegistry(List.of(() -> List.of(a, deeper)))).hasMessageContaining("prefix");
    }

    @Test
    void lockedKeyMatchingIgnoresCaseAndSeparators() {
        SettingsProperties p = new SettingsProperties();
        p.getLocked().put("GUARDRAILS_FAILMODE", true);
        p.getLocked().put("a.off", false);
        assertThat(p.isLocked("guardrails.failMode")).isTrue();
        assertThat(p.isLocked("a.off")).isFalse();
    }
}
