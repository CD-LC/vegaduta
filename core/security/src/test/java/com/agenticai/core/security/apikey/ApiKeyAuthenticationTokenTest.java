package com.agenticai.core.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

/**
 * The API-key principal: {@code PERM_} authorities only (never {@code ROLE_*}), a synthetic
 * {@code apikey:<name>:<shortId>} subject that can never be confused with a human user, and no
 * credentials carried once authenticated.
 */
class ApiKeyAuthenticationTokenTest {

    private static final UUID KEY_ID = UUID.fromString("12345678-9abc-def0-1234-56789abcdef0");

    private static ApiKeyAuthenticationToken token(Set<String> permissions) {
        return new ApiKeyAuthenticationToken("tenant_acme", KEY_ID, "ci-runner", permissions);
    }

    @Test
    void permissionsBecomePermPrefixedAuthoritiesAndNothingElse() {
        ApiKeyAuthenticationToken token = token(Set.of("READ_AGENTS", "RUN_WORKFLOWS"));

        Set<String> authorities =
                token.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());

        assertThat(authorities).containsExactlyInAnyOrder("PERM_READ_AGENTS", "PERM_RUN_WORKFLOWS");
        assertThat(authorities.stream().noneMatch(a -> a.startsWith("ROLE_"))).isTrue();
    }

    @Test
    void thePrincipalIsTheSyntheticSubjectNeverAUserId() {
        ApiKeyAuthenticationToken token = token(Set.of("READ_AGENTS"));

        assertThat(token.getPrincipal())
                .isEqualTo(ApiKeyAuthenticationToken.subjectFor("ci-runner", KEY_ID))
                .isEqualTo("apikey:ci-runner:12345678");
        assertThat(token.getName()).isEqualTo("apikey:ci-runner:12345678");
    }

    @Test
    void theTokenCarriesItsIdentityFieldsAndIsPreAuthenticated() {
        ApiKeyAuthenticationToken token = token(Set.of("READ_AGENTS"));

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getTenantId()).isEqualTo("tenant_acme");
        assertThat(token.getKeyId()).isEqualTo(KEY_ID);
        assertThat(token.getKeyName()).isEqualTo("ci-runner");
        assertThat(token.getCredentials()).isEqualTo("");
        assertThat(token.getPermissionNames()).containsExactly("READ_AGENTS");
    }

    @Test
    void nullPermissionsMeanNoAuthoritiesAndAnEmptyNameSet() {
        ApiKeyAuthenticationToken token = token(null);

        assertThat(token.getAuthorities()).isEmpty();
        assertThat(token.getPermissionNames()).isEmpty();
    }

    @Test
    void subjectForUsesTheFirstEightCharsOfTheKeyId() {
        assertThat(ApiKeyAuthenticationToken.subjectFor("deploy-bot", KEY_ID))
                .startsWith(ApiKeyAuthenticationToken.SUBJECT_PREFIX)
                .isEqualTo("apikey:deploy-bot:12345678");
    }
}
