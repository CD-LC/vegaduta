package com.agenticai.core.dbauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link DbAuthMode}'s wire spellings ({@code app.datasource.auth} / {@code DB_AUTH}), the
 * case-insensitive parse, the fail-closed rejection of unknown values and which modes need a token
 * supplier wired up.
 */
class DbAuthModeTest {

    @Test
    void everyModeHasAStableWireValue() {
        assertThat(DbAuthMode.PASSWORD.value()).isEqualTo("password");
        assertThat(DbAuthMode.AWS_IAM.value()).isEqualTo("aws-iam");
        assertThat(DbAuthMode.AZURE_ENTRA.value()).isEqualTo("azure-entra");
        assertThat(DbAuthMode.GCP_IAM.value()).isEqualTo("gcp-iam");
    }

    @Test
    void onlyPasswordModeAvoidsTheTokenPath() {
        assertThat(DbAuthMode.PASSWORD.usesToken()).isFalse();
        assertThat(DbAuthMode.AWS_IAM.usesToken()).isTrue();
        assertThat(DbAuthMode.AZURE_ENTRA.usesToken()).isTrue();
        assertThat(DbAuthMode.GCP_IAM.usesToken()).isTrue();
    }

    @Test
    void parseIsCaseInsensitiveAndTolerantOfWhitespace() {
        assertThat(DbAuthMode.parse(" AWS-IAM ")).isEqualTo(DbAuthMode.AWS_IAM);
        assertThat(DbAuthMode.parse("Azure-Entra")).isEqualTo(DbAuthMode.AZURE_ENTRA);
        assertThat(DbAuthMode.parse("PASSWORD")).isEqualTo(DbAuthMode.PASSWORD);
    }

    @Test
    void blankAndNullParseAsThePasswordDefault() {
        assertThat(DbAuthMode.parse(null)).isEqualTo(DbAuthMode.PASSWORD);
        assertThat(DbAuthMode.parse("")).isEqualTo(DbAuthMode.PASSWORD);
        assertThat(DbAuthMode.parse("   ")).isEqualTo(DbAuthMode.PASSWORD);
    }

    @Test
    void unknownValueFailsStartupAndListsTheAllowedSet() {
        assertThatThrownBy(() -> DbAuthMode.parse("kerberos"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kerberos")
                .hasMessageContaining("password")
                .hasMessageContaining("gcp-iam");
    }
}
