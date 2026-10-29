package com.agenticai.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the vault's enum members (stored as strings - a reordered or renamed constant corrupts
 * persisted rows) and the two exception types callers key behaviour on.
 */
class SecretEnumsTest {

    @Test
    void secretKindHasTheFivePersistedValues() {
        assertThat(SecretKind.values())
                .containsExactly(
                        SecretKind.API_KEY,
                        SecretKind.PASSWORD,
                        SecretKind.TOKEN,
                        SecretKind.BASIC_AUTH,
                        SecretKind.CUSTOM);
        assertThat(SecretKind.valueOf("PASSWORD")).isSameAs(SecretKind.PASSWORD);
    }

    @Test
    void secretScopeHasExactlyTenantAndUser() {
        assertThat(SecretScope.values()).containsExactly(SecretScope.TENANT, SecretScope.USER);
    }

    @Test
    void secretStatusIsActiveOrRevokedNothingIsEverDeleted() {
        assertThat(SecretStatus.values())
                .containsExactly(SecretStatus.ACTIVE, SecretStatus.REVOKED);
    }

    @Test
    void writeForbiddenCarriesTheSharedVaultExplanation() {
        SecretWriteForbiddenException e = new SecretWriteForbiddenException();

        assertThat(e.getMessage()).contains("workspace admin").contains("scoped to yourself");
    }

    @Test
    void notFoundIsAnIllegalArgumentWithADeliberatelyVagueMessage() {
        SecretNotFoundException e = new SecretNotFoundException();

        // "No such secret" also covers someone else's USER-scoped row - existence stays hidden.
        assertThat(e).isInstanceOf(IllegalArgumentException.class);
        assertThat(e.getMessage()).isEqualTo("No such secret");
    }
}
