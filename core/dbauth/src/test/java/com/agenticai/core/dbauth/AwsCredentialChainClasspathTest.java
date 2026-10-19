package com.agenticai.core.dbauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * Canary for the EKS IRSA path of {@code aws-iam}. {@code DefaultCredentialsProvider} resolves IRSA
 * through {@code WebIdentityTokenFileCredentialsProvider}, which loads the STS credentials factory
 * by reflection; without the {@code sts} module on the classpath that provider cannot be built (the
 * SDK says "the 'sts' service module must be on the class path") and the chain silently falls
 * through to credentials the pod does not have. Nothing here talks to AWS.
 */
class AwsCredentialChainClasspathTest {

    /**
     * The exact class WebIdentityCredentialsUtils loads by name; the SDK only warns when it is
     * absent.
     */
    @Test
    void theStsWebIdentityFactoryTheSdkLoadsReflectivelyIsOnTheClasspath() {
        assertThatCode(
                        () ->
                                Class.forName(
                                        "software.amazon.awssdk.services.sts.internal"
                                                + ".StsWebIdentityCredentialsProviderFactory"))
                .doesNotThrowAnyException();
    }

    @Test
    void theWebIdentityProviderUsedByIrsaCanBeBuilt(@TempDir Path dir) throws Exception {
        Path tokenFile = Files.writeString(dir.resolve("token"), "not-a-real-jwt");
        try (WebIdentityTokenFileCredentialsProvider provider =
                WebIdentityTokenFileCredentialsProvider.builder()
                        .webIdentityTokenFile(tokenFile)
                        .roleArn("arn:aws:iam::123456789012:role/not-real")
                        .roleSessionName("classpath-canary")
                        .build()) {
            assertThat(provider).isNotNull();
        }
    }

    @Test
    void anStsClientCanBeBuiltSoAnHttpClientIsOnTheClasspathToo() {
        try (StsClient sts =
                StsClient.builder()
                        .region(Region.EU_WEST_1)
                        .credentialsProvider(AnonymousCredentialsProvider.create())
                        .build()) {
            assertThat(sts).isNotNull();
        }
    }
}
