package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Decision logic of {@link DefaultStorageCredentialsWarning} (the class lives in app-config, which
 * has no test dependencies; tested here in app, which depends on it).
 */
class DefaultStorageCredentialsWarningTest {

    @Test
    void warnsOnDefaultSecretUnderProductionStyleProfiles() {
        assertThat(
                        DefaultStorageCredentialsWarning.shouldWarn(
                                "minioadmin",
                                new String[] {"security", "knowledge", "workflow", "graph-memory"}))
                .isTrue();
    }

    @Test
    void warnsOnDefaultSecretWithNoActiveProfiles() {
        assertThat(DefaultStorageCredentialsWarning.shouldWarn("minioadmin", new String[0]))
                .isTrue();
        assertThat(DefaultStorageCredentialsWarning.shouldWarn(" minioadmin ", null)).isTrue();
    }

    @Test
    void silentWhenAnyLocalProfileIsActive() {
        assertThat(
                        DefaultStorageCredentialsWarning.shouldWarn(
                                "minioadmin", new String[] {"security", "dev"}))
                .isFalse();
        assertThat(
                        DefaultStorageCredentialsWarning.shouldWarn(
                                "minioadmin", new String[] {"LOCAL"}))
                .isFalse();
        assertThat(DefaultStorageCredentialsWarning.shouldWarn("minioadmin", new String[] {"test"}))
                .isFalse();
    }

    @Test
    void silentWhenSecretIsRealOrUnset() {
        assertThat(
                        DefaultStorageCredentialsWarning.shouldWarn(
                                "s3cr3t-from-vault", new String[] {"security"}))
                .isFalse();
        assertThat(DefaultStorageCredentialsWarning.shouldWarn(null, new String[] {"security"}))
                .isFalse();
    }

    @Test
    void theKeyPairOnlyMattersForS3WithStaticCredentials() {
        assertThat(DefaultStorageCredentialsWarning.keyPairIsUsed("s3", "static")).isTrue();
        assertThat(DefaultStorageCredentialsWarning.keyPairIsUsed("S3", " static ")).isTrue();
        assertThat(DefaultStorageCredentialsWarning.keyPairIsUsed("s3", "default")).isFalse();
        // azure-blob + static is a connection string; the MinIO default secret is never read.
        assertThat(DefaultStorageCredentialsWarning.keyPairIsUsed("azure-blob", "static"))
                .isFalse();
        assertThat(DefaultStorageCredentialsWarning.keyPairIsUsed("azure-blob", "default"))
                .isFalse();
        assertThat(DefaultStorageCredentialsWarning.keyPairIsUsed("local", "static")).isFalse();
    }
}
