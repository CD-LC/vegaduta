package com.agenticai.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The resolver is the class that decides whether a password ever reaches a
 * third-party model provider, so its edge cases are tested rather than
 * assumed.
 */
class SecretReferenceResolverTest {

    private SecretVaultService vault;
    private SecretReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        vault = mock(SecretVaultService.class);
        resolver = new SecretReferenceResolver(vault);
    }

    @Test
    void substitutesAReferenceAndReportsTheValueForRedaction() {
        when(vault.resolveForRun("STRIPE_KEY", "u1")).thenReturn(Optional.of("sk_live_abc123"));

        SecretReferenceResolver.Resolution result =
            resolver.resolve("Bearer {{secret.STRIPE_KEY}}", "u1");

        assertThat(result.value()).isEqualTo("Bearer sk_live_abc123");
        assertThat(result.resolvedValues()).containsExactly("sk_live_abc123");
        assertThat(result.hasUnresolved()).isFalse();
    }

    @Test
    void leavesAnUnresolvableReferenceIntactAndNamesIt() {
        when(vault.resolveForRun("MISSING", "u1")).thenReturn(Optional.empty());

        SecretReferenceResolver.Resolution result = resolver.resolve("key={{secret.MISSING}}", "u1");

        // Critically NOT "key=" - an empty credential reads as anonymous
        // access to some upstreams, which is a worse failure than a loud one.
        assertThat(result.value()).isEqualTo("key={{secret.MISSING}}");
        assertThat(result.unresolvedNames()).containsExactly("MISSING");
        assertThat(result.resolvedValues()).isEmpty();
    }

    @Test
    void resolvesEachDistinctNameOnlyOnceEvenWhenRepeated() {
        when(vault.resolveForRun("TOKEN", "u1")).thenReturn(Optional.of("t-123456"));

        SecretReferenceResolver.Resolution result =
            resolver.resolve("{{secret.TOKEN}} and {{secret.TOKEN}}", "u1");

        assertThat(result.value()).isEqualTo("t-123456 and t-123456");
        // One audit row per intent, not per loop iteration.
        verify(vault, times(1)).resolveForRun(eq("TOKEN"), eq("u1"));
    }

    @Test
    void aSecretContainingRegexReplacementCharactersSurvivesIntact() {
        // '$' and '\' are group-reference syntax to Matcher.appendReplacement.
        // Without quoteReplacement this corrupts the value or throws.
        when(vault.resolveForRun("PW", "u1")).thenReturn(Optional.of("p$1a\\b$0c"));

        SecretReferenceResolver.Resolution result = resolver.resolve("{{secret.PW}}", "u1");

        assertThat(result.value()).isEqualTo("p$1a\\b$0c");
    }

    @Test
    void toleratesWhitespaceInsideTheReference() {
        when(vault.resolveForRun("API_KEY", "u1")).thenReturn(Optional.of("value-1234"));

        assertThat(resolver.resolve("{{ secret.API_KEY }}", "u1").value()).isEqualTo("value-1234");
    }

    @Test
    void ignoresTextThatIsNotAValidReference() {
        // Lowercase names, other namespaces and bare braces are left alone
        // rather than raising errors - they are ordinary text.
        String text = "{{secret.lowercase}} {{env.PATH}} {not a ref}";

        SecretReferenceResolver.Resolution result = resolver.resolve(text, "u1");

        assertThat(result.value()).isEqualTo(text);
        assertThat(result.hasUnresolved()).isFalse();
    }

    @Test
    void expandsMapValuesButNeverMapKeys() {
        when(vault.resolveForRun("TOKEN", "u1")).thenReturn(Optional.of("t-secret-9"));
        Map<String, String> source = new LinkedHashMap<>();
        source.put("Authorization", "Bearer {{secret.TOKEN}}");
        source.put("{{secret.TOKEN}}", "literal");
        Map<String, String> target = new LinkedHashMap<>();

        SecretReferenceResolver.Resolution result = resolver.resolveAll(source, "u1", target);

        assertThat(target).containsEntry("Authorization", "Bearer t-secret-9");
        // A resolved key would put plaintext into a process listing or a
        // header name; keys stay literal.
        assertThat(target).containsKey("{{secret.TOKEN}}");
        assertThat(result.resolvedValues()).containsExactly("t-secret-9");
    }

    @Test
    void containsReferenceIsNullSafe() {
        assertThat(resolver.containsReference(null)).isFalse();
        assertThat(resolver.containsReference("plain")).isFalse();
        assertThat(resolver.containsReference("x {{secret.A}}")).isTrue();
    }
}
