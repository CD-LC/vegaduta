package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.AzureBlobFileStorageService;
import com.agenticai.core.storage.FileStorageException;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.storage.StorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link AzureBlobFileStorageService} against the real Azure Blob API as served by the Azurite
 * emulator: the same storage behaviours the S3 backend has, including the tenant boundary, over the
 * wire.
 *
 * <p>Image override: {@code vegaduta.test.azurite.image}. The connection string is Azurite's
 * documented development account (a published emulator credential, not a secret), assembled in
 * pieces.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class AzureBlobStorageIT {

    private static GenericContainer<?> azurite;
    private static AzureBlobFileStorageService storage;

    @BeforeAll
    static void start() {
        azurite =
                new GenericContainer<>(
                                DockerImageName.parse(
                                        System.getProperty(
                                                "vegaduta.test.azurite.image",
                                                "mcr.microsoft.com/azure-storage/azurite:3.35.0")))
                        .withExposedPorts(10000)
                        .withCommand(
                                "azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck");
        azurite.start();
        String account = "devstoreaccount1";
        String key =
                "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50u"
                        + "SRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
        String connection =
                "DefaultEndpointsProtocol=http;AccountName="
                        + account
                        + ";AccountKey="
                        + key
                        + ";BlobEndpoint=http://"
                        + azurite.getHost()
                        + ":"
                        + azurite.getMappedPort(10000)
                        + "/"
                        + account
                        + ";";
        StorageProperties properties = new StorageProperties();
        properties.setBucket("vegaduta-it");
        properties.setCredentials("static");
        properties.getAzure().setConnectionString(connection);
        storage = new AzureBlobFileStorageService(properties);
        ensureContainer();
    }

    private static void ensureContainer() {
        // @PostConstruct is not run outside Spring; call the same package-private hook through a
        // probe write.
        try {
            var m = AzureBlobFileStorageService.class.getDeclaredMethod("ensureContainerExistsNow");
            m.setAccessible(true);
            m.invoke(storage);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        if (azurite != null) {
            azurite.stop();
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static void asTenant(String id) {
        TenantContext.set(TenantContext.of(id, "user", Set.of()));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void putGetSizeExistsAndDeleteRoundTrip() throws IOException {
        asTenant("t_roundtrip");
        storage.put("avatars/a.txt", bytes("hello azure"), "text/plain");

        assertThat(storage.exists("avatars/a.txt")).isTrue();
        assertThat(storage.size("avatars/a.txt")).isEqualTo(11);
        assertThat(read(storage.get("avatars/a.txt"))).isEqualTo("hello azure");

        storage.delete("avatars/a.txt");
        assertThat(storage.exists("avatars/a.txt")).isFalse();
        storage.delete("avatars/a.txt"); // deleting a missing object is not an error
    }

    @Test
    void anOverwriteReplacesTheObject() throws IOException {
        asTenant("t_overwrite");
        storage.put("avatars/o.txt", bytes("one"), "text/plain");
        storage.put("avatars/o.txt", bytes("two-two"), "text/plain");

        assertThat(read(storage.get("avatars/o.txt"))).isEqualTo("two-two");
    }

    @Test
    void aRangeReadReturnsExactlyTheInclusiveWindow() throws IOException {
        asTenant("t_range");
        storage.put("generated-videos/v.bin", bytes("0123456789"), "application/octet-stream");

        assertThat(read(storage.getRange("generated-videos/v.bin", 2, 5))).isEqualTo("2345");
        assertThat(read(storage.getRange("generated-videos/v.bin", 0, 0))).isEqualTo("0");
    }

    @Test
    void aMissingObjectIsAFileStorageException() {
        asTenant("t_missing");
        assertThatThrownBy(() -> storage.get("avatars/none"))
                .isInstanceOf(FileStorageException.class);
        assertThatThrownBy(() -> storage.size("avatars/none"))
                .isInstanceOf(FileStorageException.class);
        assertThat(storage.exists("avatars/none")).isFalse();
    }

    @Test
    void tenantsAreIsolatedOverTheWire() {
        asTenant("t_alice");
        storage.put("avatars/secret.txt", bytes("alice only"), "text/plain");

        asTenant("t_bob");
        assertThat(storage.exists("avatars/secret.txt")).isFalse();
        assertThatThrownBy(() -> storage.get("avatars/secret.txt"))
                .isInstanceOf(FileStorageException.class);
        // A physical key of another tenant is refused outright, even though the blob exists.
        assertThatThrownBy(() -> storage.get("tenant/t_alice/avatars/secret.txt"))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("does not belong to the current tenant");
        assertThatThrownBy(() -> storage.delete("tenant/t_alice/avatars/secret.txt"))
                .isInstanceOf(FileStorageException.class);

        asTenant("t_alice");
        assertThat(storage.exists("avatars/secret.txt")).isTrue();
    }

    @Test
    void listAcrossTenantsReportsOwnerAndLogicalKey() {
        asTenant("t_list_a");
        storage.put("elevenlabs-audio/1.mp3", bytes("a"), "audio/mpeg");
        asTenant("t_list_b");
        storage.put("elevenlabs-audio/2.mp3", bytes("b"), "audio/mpeg");
        TenantContext.clear();

        List<FileStorageService.StoredObject> all = storage.listAcrossTenants("elevenlabs-audio/");

        assertThat(all)
                .extracting(
                        FileStorageService.StoredObject::tenantId,
                        FileStorageService.StoredObject::key)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("t_list_a", "elevenlabs-audio/1.mp3"),
                        org.assertj.core.groups.Tuple.tuple("t_list_b", "elevenlabs-audio/2.mp3"));
        assertThat(all).allMatch(o -> o.lastModifiedMillis() > 0);
    }
}
