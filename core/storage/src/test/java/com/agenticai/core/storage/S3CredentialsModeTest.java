package com.agenticai.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/** {@code app.storage.credentials}: static key pair (default) or the AWS SDK default chain. */
class S3CredentialsModeTest {

    private static StorageProperties props(String mode, String access, String secret) {
        StorageProperties p = new StorageProperties();
        p.setCredentials(mode);
        p.setAccessKey(access);
        p.setSecretKey(secret);
        return p;
    }

    @Test
    void staticIsTheDefaultAndUsesTheConfiguredKeys() {
        StorageProperties p = new StorageProperties();
        p.setAccessKey("AKIAEXAMPLE");
        p.setSecretKey("secret-example");
        AwsCredentialsProvider provider = S3FileStorageService.credentialsProvider(p);
        assertThat(provider).isInstanceOf(StaticCredentialsProvider.class);
        assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo("AKIAEXAMPLE");
    }

    @Test
    void defaultModeUsesTheSdkChainAndIgnoresTheKeys() {
        AwsCredentialsProvider provider =
                S3FileStorageService.credentialsProvider(props("default", "ignored", "ignored"));
        assertThat(provider).isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    void modeIsCaseAndWhitespaceInsensitive() {
        assertThat(S3FileStorageService.credentialsProvider(props(" Default ", "", "")))
                .isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    void aNullModeFallsBackToStatic() {
        assertThat(props(null, "a", "b").getCredentials()).isEqualTo("static");
    }

    @Test
    void anUnknownModeIsRejectedNotSilentlyTreatedAsKeys() {
        assertThatThrownBy(() -> S3FileStorageService.credentialsProvider(props("iam", "a", "b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'static' or 'default'");
    }

    @Test
    void theStsModuleIsOnTheClasspathSoIrsaWebIdentityCanResolve() {
        // DefaultCredentialsProvider loads this factory by reflection for EKS IRSA; without the sts
        // module the
        // chain answers "the 'sts' service module must be on the class path" and falls through to
        // credentials
        // the pod does not have.
        assertThat(
                        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                                () ->
                                        Class.forName(
                                                "software.amazon.awssdk.services.sts.internal.StsWebIdentityCredentialsProviderFactory")))
                .isNotNull();
    }
}
