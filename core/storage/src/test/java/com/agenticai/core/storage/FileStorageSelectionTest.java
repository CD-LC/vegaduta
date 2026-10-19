package com.agenticai.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Non-breaking-contract guard: with no {@code app.storage.type} set, the active {@link
 * FileStorageService} MUST remain {@link S3FileStorageService} (exactly the pre-existing
 * behaviour). {@code type=local} is opt-in only.
 */
class FileStorageSelectionTest {

    // Endpoint on a closed port: S3FileStorageService's @PostConstruct bucket
    // check fails fast and only logs a warning (never fails startup).
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            StorageConfiguration.class,
                            S3FileStorageService.class,
                            LocalFileStorageService.class,
                            AzureBlobFileStorageService.class)
                    .withPropertyValues(
                            "app.storage.endpoint=http://127.0.0.1:1",
                            "app.storage.access-key=test",
                            "app.storage.secret-key=test");

    @TempDir Path tempDir;

    @Test
    void defaultIsS3WhenTypeUnset() {
        runner.run(
                ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(FileStorageService.class);
                    assertThat(ctx.getBean(FileStorageService.class))
                            .isInstanceOf(S3FileStorageService.class);
                    assertThat(ctx).doesNotHaveBean(LocalFileStorageService.class);
                    assertThat(ctx).hasSingleBean(StorageProperties.class);
                });
    }

    @Test
    void explicitS3SelectsS3() {
        runner.withPropertyValues("app.storage.type=s3")
                .run(
                        ctx ->
                                assertThat(ctx.getBean(FileStorageService.class))
                                        .isInstanceOf(S3FileStorageService.class));
    }

    @Test
    void localIsOptIn() {
        runner.withPropertyValues(
                        "app.storage.type=local", "app.storage.local.path=" + tempDir.resolve("s"))
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx.getBean(FileStorageService.class))
                                    .isInstanceOf(LocalFileStorageService.class);
                            assertThat(ctx).doesNotHaveBean(S3FileStorageService.class);
                            // Still registered for callers that read S3 settings directly
                            // (LiveKitEgressService).
                            assertThat(ctx).hasSingleBean(StorageProperties.class);
                        });
    }

    @Test
    void azureBlobIsOptInAndNeverLoadedByDefault() {
        runner.withPropertyValues(
                        "app.storage.type=azure-blob",
                        "app.storage.credentials=static",
                        // Parsing only: the client is built lazily and the container check just
                        // logs on failure.
                        "app.storage.azure.connection-string=DefaultEndpointsProtocol=http;AccountName=a;"
                            + "AccountKey=Yg==;BlobEndpoint=http://127.0.0.1:1/a;")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx.getBean(FileStorageService.class))
                                    .isInstanceOf(AzureBlobFileStorageService.class);
                            assertThat(ctx).doesNotHaveBean(S3FileStorageService.class);
                            assertThat(ctx).doesNotHaveBean(LocalFileStorageService.class);
                        });
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(AzureBlobFileStorageService.class));
    }
}
