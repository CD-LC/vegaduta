package com.agenticai.core.crypto.kms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.crypto.EnvelopeEncryptionService;
import com.agenticai.core.crypto.KeyManagementProvider;
import com.agenticai.core.crypto.KmsProviderSelectionCheck;
import com.agenticai.core.crypto.LocalKeyManagementProvider;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Property-driven selection with the real Spring wiring (no cloud call is made). */
class KmsProviderSelectionTest {

    private static final String LOCAL_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            LocalKeyManagementProvider.class,
                            AwsKmsKeyManagementProvider.class,
                            AzureKeyVaultKeyManagementProvider.class,
                            GcpCloudKmsKeyManagementProvider.class,
                            EnvelopeEncryptionService.class,
                            KmsProviderSelectionCheck.class)
                    .withPropertyValues("app.encryption.key=" + LOCAL_KEY);

    @Test
    void defaultIsLocalAndNoCloudBeanExists() {
        runner.run(
                ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertEquals("local", ctx.getBean(KeyManagementProvider.class).providerId());
                    assertEquals(0, ctx.getBeansOfType(AwsKmsKeyManagementProvider.class).size());
                    assertEquals(
                            0, ctx.getBeansOfType(AzureKeyVaultKeyManagementProvider.class).size());
                    assertEquals(
                            0, ctx.getBeansOfType(GcpCloudKmsKeyManagementProvider.class).size());
                });
    }

    @Test
    void explicitLocalIsTheSameAsDefault() {
        runner.withPropertyValues("app.crypto.kms.provider=local")
                .run(
                        ctx ->
                                assertEquals(
                                        "local",
                                        ctx.getBean(KeyManagementProvider.class).providerId()));
    }

    @Test
    void awsBecomesPrimaryAndLocalStaysRegisteredForOldEnvelopes() {
        runner.withPropertyValues(
                        "app.crypto.kms.provider=aws",
                        "app.crypto.kms.aws.key-id=arn:aws:kms:eu-west-1:111122223333:key/k",
                        "app.crypto.kms.aws.region=eu-west-1")
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            assertTrue(
                                    ctx.getBean(KeyManagementProvider.class)
                                            .providerId()
                                            .startsWith("aws:"));
                            assertEquals(2, ctx.getBeansOfType(KeyManagementProvider.class).size());
                            assertNotNull(ctx.getBean(EnvelopeEncryptionService.class));
                            assertEquals(
                                    0,
                                    ctx.getBeansOfType(GcpCloudKmsKeyManagementProvider.class)
                                            .size());
                        });
    }

    @Test
    void cloudProviderWithoutKeyIdFailsStartup() {
        runner.withPropertyValues(
                        "app.crypto.kms.provider=aws", "app.crypto.kms.aws.region=eu-west-1")
                .run(
                        ctx -> {
                            assertNotNull(ctx.getStartupFailure());
                            assertTrue(
                                    rootMessage(ctx.getStartupFailure())
                                            .contains("app.crypto.kms.aws.key-id must be set"),
                                    rootMessage(ctx.getStartupFailure()));
                        });
    }

    @Test
    void providerValueIsCaseInsensitiveLikeTheBeanConditions() {
        runner.withPropertyValues(
                        "app.crypto.kms.provider=AWS",
                        "app.crypto.kms.aws.key-id=arn:aws:kms:eu-west-1:111122223333:key/k",
                        "app.crypto.kms.aws.region=eu-west-1")
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            assertTrue(
                                    ctx.getBean(KeyManagementProvider.class)
                                            .providerId()
                                            .startsWith("aws:"));
                        });
    }

    @Test
    void emptyProviderValueMeansLocal() {
        runner.withPropertyValues("app.crypto.kms.provider=")
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            assertEquals(
                                    "local", ctx.getBean(KeyManagementProvider.class).providerId());
                        });
    }

    @Test
    void unknownProviderValueFailsStartup() {
        runner.withPropertyValues("app.crypto.kms.provider=gpc")
                .run(
                        ctx -> {
                            assertNotNull(ctx.getStartupFailure());
                            assertTrue(
                                    rootMessage(ctx.getStartupFailure()).contains("is not one of"));
                        });
    }

    @Test
    void selectingACloudProviderWithoutItsModuleFailsStartup() {
        // Only the crypto module's beans: nothing implements "azure".
        new ApplicationContextRunner()
                .withUserConfiguration(
                        LocalKeyManagementProvider.class,
                        EnvelopeEncryptionService.class,
                        KmsProviderSelectionCheck.class)
                .withPropertyValues(
                        "app.encryption.key=" + LOCAL_KEY, "app.crypto.kms.provider=azure")
                .run(
                        ctx -> {
                            assertNotNull(ctx.getStartupFailure());
                            assertTrue(
                                    rootMessage(ctx.getStartupFailure())
                                            .contains("crypto-kms module"));
                        });
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }
}
