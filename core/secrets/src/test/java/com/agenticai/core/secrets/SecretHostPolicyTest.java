package com.agenticai.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SEC-006: the per-secret destination allow-list cannot be tricked. */
class SecretHostPolicyTest {

    private static final List<String> POLICY =
        SecretHostPolicy.normalisePatterns(List.of("api.example.com", "*.stripe.com"));

    @ParameterizedTest
    @ValueSource(strings = {"api.example.com", "API.EXAMPLE.COM", "api.example.com.", "Api.Example.Com..",
        "a.stripe.com", "deep.a.stripe.com", "A.STRIPE.COM."})
    void allowedHostsMatch(String host) {
        assertThat(SecretHostPolicy.matches(POLICY, host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "evil-example.com", "example.com", "xapi.example.com", "api.example.com.attacker.tld",
        "api-example.com", "stripe.com", "evilstripe.com", "stripe.com.evil.io", "a.stripe.com.evil.io",
        "attacker.tld", "", " ", "api.example.com:443", "user@api.example.com", "[::1]",
        "аpi.example.com" /* Cyrillic a - IDN homograph, punycodes to xn--... */})
    void lookalikesDoNotMatch(String host) {
        assertThat(SecretHostPolicy.matches(POLICY, host)).isFalse();
    }

    @Test
    void nullHostOrEmptyPolicyNeverMatches() {
        assertThat(SecretHostPolicy.matches(POLICY, null)).isFalse();
        assertThat(SecretHostPolicy.matches(List.of(), "api.example.com")).isFalse();
        assertThat(SecretHostPolicy.matches(null, "api.example.com")).isFalse();
    }

    @Test
    void idnPatternsAreComparedInPunycode() {
        List<String> idn = SecretHostPolicy.normalisePatterns(List.of("bücher.example"));
        assertThat(idn).containsExactly("xn--bcher-kva.example");
        assertThat(SecretHostPolicy.matches(idn, "BÜCHER.example.")).isTrue();
        assertThat(SecretHostPolicy.matches(idn, "xn--bcher-kva.example")).isTrue();
        assertThat(SecretHostPolicy.matches(idn, "bucher.example")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "*.", "*.com", "*.*.example.com", "a.*.example.com", "api*.example.com",
        "https://api.example.com", "api.example.com/path", "api.example.com:443", "user@api.example.com",
        "api.example.com,evil.com", "*.10.0.0.1", "exa mple.com", "-bad.example.com", "[::1]"})
    void invalidPatternsAreRejected(String pattern) {
        assertThatThrownBy(() -> SecretHostPolicy.normalisePattern(pattern))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void patternsAreNormalisedAndDeduplicated() {
        assertThat(SecretHostPolicy.normalisePatterns(List.of(" API.Example.com. ", "api.example.com", "*.Stripe.COM")))
            .containsExactly("api.example.com", "*.stripe.com");
        assertThat(SecretHostPolicy.normalisePatterns(null)).isEmpty();
    }

    @Test
    void tooManyPatternsAreRejected() {
        String[] many = new String[SecretHostPolicy.MAX_PATTERNS + 1];
        for (int i = 0; i < many.length; i++) {
            many[i] = "h" + i + ".example.com";
        }
        assertThatThrownBy(() -> SecretHostPolicy.normalisePatterns(Arrays.asList(many)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void columnRoundTrip() {
        assertThat(SecretHostPolicy.fromColumn(SecretHostPolicy.toColumn(POLICY))).isEqualTo(POLICY);
        assertThat(SecretHostPolicy.toColumn(List.of())).isNull();
        assertThat(SecretHostPolicy.fromColumn(null)).isEmpty();
    }
}
