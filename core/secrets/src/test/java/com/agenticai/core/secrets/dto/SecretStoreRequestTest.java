package com.agenticai.core.secrets.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.secrets.SecretKind;
import com.agenticai.core.secrets.SecretScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SecretStoreRequest}: the record itself is a plain carrier, so the meaningful
 * assertions are that the bean-validation constraints are present on the fields the API relies on
 * (the UPPER_SNAKE_CASE name rule and the 6-character minimum on the value in particular - the
 * latter is load-bearing for redaction).
 */
class SecretStoreRequestTest {

    private static SecretStoreRequest request() {
        return new SecretStoreRequest(
                "STRIPE_KEY",
                SecretScope.TENANT,
                SecretKind.API_KEY,
                "stripe live key",
                "sk_live_123456",
                null,
                Boolean.TRUE,
                List.of("api.stripe.com"));
    }

    @Test
    void componentsRoundTrip() {
        SecretStoreRequest r = request();

        assertThat(r.name()).isEqualTo("STRIPE_KEY");
        assertThat(r.scope()).isEqualTo(SecretScope.TENANT);
        assertThat(r.kind()).isEqualTo(SecretKind.API_KEY);
        assertThat(r.description()).isEqualTo("stripe live key");
        assertThat(r.value()).isEqualTo("sk_live_123456");
        assertThat(r.expiresAt()).isNull();
        assertThat(r.codeToolAccess()).isTrue();
        assertThat(r.allowedHosts()).containsExactly("api.stripe.com");
    }

    @Test
    void optionalFieldsMayBeAbsent() {
        SecretStoreRequest r =
                new SecretStoreRequest(
                        "K", SecretScope.USER, SecretKind.CUSTOM, null, "123456", null, null, null);

        assertThat(r.description()).isNull();
        assertThat(r.expiresAt()).isNull();
        assertThat(r.codeToolAccess()).isNull();
        assertThat(r.allowedHosts()).isNull();
    }

    @Test
    void theNameFieldCarriesTheUpperSnakeCaseConstraint() throws NoSuchFieldException {
        Pattern pattern =
                SecretStoreRequest.class.getDeclaredField("name").getAnnotation(Pattern.class);

        assertThat(pattern).isNotNull();
        assertThat(pattern.regexp()).isEqualTo("^[A-Z][A-Z0-9_]{0,63}$");
        assertThat(SecretStoreRequest.class.getDeclaredField("name").getAnnotation(NotBlank.class))
                .isNotNull();
    }

    @Test
    void theValueFieldEnforcesTheRedactionMinimum() throws NoSuchFieldException {
        java.lang.reflect.Field value = SecretStoreRequest.class.getDeclaredField("value");

        assertThat(value.getAnnotation(NotBlank.class)).isNotNull();
        Size size = value.getAnnotation(Size.class);
        assertThat(size.min()).isEqualTo(6);
        assertThat(size.max()).isEqualTo(8192);
    }

    @Test
    void scopeAndKindAreRequired() throws NoSuchFieldException {
        assertThat(SecretStoreRequest.class.getDeclaredField("scope").getAnnotation(NotNull.class))
                .isNotNull();
        assertThat(SecretStoreRequest.class.getDeclaredField("kind").getAnnotation(NotNull.class))
                .isNotNull();
    }
}
