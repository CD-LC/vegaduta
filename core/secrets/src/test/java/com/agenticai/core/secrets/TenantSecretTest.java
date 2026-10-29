package com.agenticai.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.secrets.dto.SecretResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link TenantSecret}'s row lifecycle without a database: the write-only-value posture
 * (preview out, ciphertext package-private), rotation semantics, revocation, the {@code
 * isResolvable} expiry rule, the destination allow-list column encoding and {@link
 * SecretResponse#from}'s mapping - including the computed {@code expired} flag and the {@code
 * {{secret.NAME}}} reference the UI copies.
 */
class TenantSecretTest {

    private static TenantSecret secret(String name, Instant expiresAt) {
        return new TenantSecret(
                name,
                SecretScope.TENANT,
                null,
                SecretKind.API_KEY,
                "a description",
                "ciphertext-blob",
                "...wxyz",
                expiresAt,
                false,
                "admin@acme");
    }

    @Test
    void constructionSetsIdentityStatusAndAuditFields() {
        TenantSecret s = secret("STRIPE_KEY", null);

        assertThat(s.getId()).isNotNull();
        assertThat(s.getName()).isEqualTo("STRIPE_KEY");
        assertThat(s.getScope()).isEqualTo(SecretScope.TENANT);
        assertThat(s.getOwnerUserId()).isNull();
        assertThat(s.getKind()).isEqualTo(SecretKind.API_KEY);
        assertThat(s.getDescription()).isEqualTo("a description");
        assertThat(s.getValuePreview()).isEqualTo("...wxyz");
        assertThat(s.getStatus()).isEqualTo(SecretStatus.ACTIVE);
        assertThat(s.isCodeToolAccess()).isFalse();
        assertThat(s.getCreatedBy()).isEqualTo("admin@acme");
        assertThat(s.getCreatedAt()).isNotNull();
        assertThat(s.getUpdatedAt()).isEqualTo(s.getCreatedAt());
        assertThat(s.getRotatedAt()).isNull();
        assertThat(s.getLastUsedAt()).isNull();
        assertThat(s.getExpiresAt()).isNull();
    }

    @Test
    void rotateReplacesValuePreviewAndExpiryInPlace() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        TenantSecret s = secret("OLD_KEY", past);
        s.revoke();

        s.rotate("new-ciphertext", "...abcd", null, "rotated description");

        assertThat(s.encryptedValue()).isEqualTo("new-ciphertext");
        assertThat(s.getValuePreview()).isEqualTo("...abcd");
        assertThat(s.getExpiresAt()).isNull();
        assertThat(s.getDescription()).isEqualTo("rotated description");
        // Rotation revives a revoked row - the credential works again.
        assertThat(s.getStatus()).isEqualTo(SecretStatus.ACTIVE);
        assertThat(s.getRotatedAt()).isNotNull();
        assertThat(s.getUpdatedAt()).isEqualTo(s.getRotatedAt());
    }

    @Test
    void rotateKeepsTheStoredDescriptionWhenNullIsPassed() {
        TenantSecret s = secret("KEEP_DESC", null);

        s.rotate("cipher2", "...1234", null, null);

        assertThat(s.getDescription()).isEqualTo("a description");
    }

    @Test
    void updateMetadataChangesDescriptionAndExpiryOnly() {
        Instant expiry = Instant.now().plus(1, ChronoUnit.DAYS);
        TenantSecret s = secret("META", null);

        s.updateMetadata("new desc", expiry);

        assertThat(s.getDescription()).isEqualTo("new desc");
        assertThat(s.getExpiresAt()).isEqualTo(expiry);
        assertThat(s.encryptedValue()).isEqualTo("ciphertext-blob");
        assertThat(s.getRotatedAt()).isNull();
    }

    @Test
    void revokeAndMarkUsedUpdateTheBookkeeping() {
        TenantSecret s = secret("USED", null);

        s.markUsed();
        s.revoke();

        assertThat(s.getLastUsedAt()).isNotNull();
        assertThat(s.getStatus()).isEqualTo(SecretStatus.REVOKED);
        assertThat(s.getUpdatedAt()).isNotNull();
    }

    @Test
    void setCodeToolAccessFlipsTheOptIn() {
        TenantSecret s = secret("CODE_TOOL", null);

        s.setCodeToolAccess(true);

        assertThat(s.isCodeToolAccess()).isTrue();
    }

    @Test
    void replaceCiphertextRewrapsWithoutTouchingRotationStamps() {
        TenantSecret s = secret("REWRAP", null);

        s.replaceCiphertext("rewrapped");

        assertThat(s.encryptedValue()).isEqualTo("rewrapped");
        // A key rotation is not a credential rotation - the UI stamps stay untouched.
        assertThat(s.getRotatedAt()).isNull();
        assertThat(s.getValuePreview()).isEqualTo("...wxyz");
    }

    @Test
    void isResolvableFollowsStatusAndExpiry() {
        assertThat(secret("OK", null).isResolvable()).isTrue();
        assertThat(secret("FUTURE", Instant.now().plus(1, ChronoUnit.DAYS)).isResolvable())
                .isTrue();
        assertThat(secret("PAST", Instant.now().minus(1, ChronoUnit.DAYS)).isResolvable())
                .isFalse();

        TenantSecret revoked = secret("REVOKED", null);
        revoked.revoke();
        assertThat(revoked.isResolvable()).isFalse();
    }

    @Test
    void allowedHostsRoundTripsThroughTheColumnForm() {
        TenantSecret s = secret("HOSTS", null);
        assertThat(s.getAllowedHosts()).isEmpty();

        s.setAllowedHosts(List.of("api.stripe.com", "*.example.com"));

        assertThat(s.getAllowedHosts()).containsExactly("api.stripe.com", "*.example.com");

        s.setAllowedHosts(List.of());
        assertThat(s.getAllowedHosts()).isEmpty();
    }

    @Test
    void toStringPrintsMetadataNeverTheCiphertext() {
        TenantSecret s = secret("STRIPE_KEY", null);

        assertThat(s.toString())
                .contains("STRIPE_KEY")
                .contains("TENANT")
                .contains("ACTIVE")
                .doesNotContain("ciphertext-blob");
    }

    @Test
    void secretResponseFromMapsEveryFieldAndBuildsTheReference() {
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        TenantSecret s = secret("MY_KEY", future);

        SecretResponse r = SecretResponse.from(s);

        assertThat(r.id()).isEqualTo(s.getId());
        assertThat(r.name()).isEqualTo("MY_KEY");
        assertThat(r.reference()).isEqualTo("{{secret.MY_KEY}}");
        assertThat(r.scope()).isEqualTo(SecretScope.TENANT);
        assertThat(r.kind()).isEqualTo(SecretKind.API_KEY);
        assertThat(r.description()).isEqualTo("a description");
        assertThat(r.preview()).isEqualTo("...wxyz");
        assertThat(r.status()).isEqualTo(SecretStatus.ACTIVE);
        assertThat(r.codeToolAccess()).isFalse();
        assertThat(r.allowedHosts()).isEmpty();
        assertThat(r.expiresAt()).isEqualTo(future);
        assertThat(r.expired()).isFalse();
        assertThat(r.createdBy()).isEqualTo("admin@acme");
        // And the one thing it can never carry: the value itself.
        assertThat(r.getClass().getDeclaredFields())
                .noneMatch(f -> f.getName().equals("value") || f.getName().equals("encryptedValue"));
    }

    @Test
    void secretResponseMarksAPastExpiryAsExpired() {
        TenantSecret s = secret("DEAD_KEY", Instant.now().minus(1, ChronoUnit.DAYS));

        assertThat(SecretResponse.from(s).expired()).isTrue();
    }
}
