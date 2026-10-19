package com.agenticai.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tenant-isolation rules hold for ANY backend, not just S3: this runs them over a map-backed
 * one, so a new object store (Azure Blob) inherits behaviour that is pinned here rather than
 * reimplementing it.
 */
class TenantScopedFileStorageTest {

    /** Physical key -> bytes; the whole "bucket". */
    private final Map<String, byte[]> bucket = new TreeMap<>();

    private StorageProperties properties;
    private TenantScopedFileStorage storage;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        storage =
                new TenantScopedFileStorage(properties) {
                    @Override
                    protected void writeObject(String key, byte[] content, String contentType) {
                        bucket.put(key, content);
                    }

                    @Override
                    protected InputStream readObject(String key, String rangeSpec) {
                        byte[] bytes = bucket.get(key);
                        if (bytes == null) {
                            throw new ObjectNotFoundException(
                                    new RuntimeException("missing " + key));
                        }
                        return new ByteArrayInputStream(bytes);
                    }

                    @Override
                    protected long objectLength(String key) {
                        byte[] bytes = bucket.get(key);
                        if (bytes == null) {
                            throw new ObjectNotFoundException(
                                    new RuntimeException("missing " + key));
                        }
                        return bytes.length;
                    }

                    @Override
                    protected boolean objectPresent(String key) {
                        return bucket.containsKey(key);
                    }

                    @Override
                    protected void removeObject(String key) {
                        bucket.remove(key);
                    }

                    @Override
                    protected List<String> listTenantIds() {
                        return bucket.keySet().stream()
                                .filter(k -> k.startsWith("tenant/"))
                                .map(k -> k.split("/")[1])
                                .distinct()
                                .toList();
                    }

                    @Override
                    protected void listObjects(String prefix, ObjectListener listener) {
                        new ArrayList<>(bucket.keySet())
                                .stream()
                                        .filter(k -> k.startsWith(prefix))
                                        .forEach(k -> listener.accept(k, 1L));
                    }
                };
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static void asTenant(String tenantId) {
        TenantContext.set(TenantContext.of(tenantId, "user", Set.of()));
    }

    private String read(String key) throws IOException {
        try (InputStream in = storage.get(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aTenantsObjectIsStoredUnderItsOwnPrefixAndReadBackByTheLogicalKey() throws IOException {
        asTenant("t_a");
        storage.put("avatars/x", "A".getBytes(StandardCharsets.UTF_8), "text/plain");

        assertThat(bucket).containsOnlyKeys("tenant/t_a/avatars/x");
        assertThat(read("avatars/x")).isEqualTo("A");
        assertThat(storage.exists("avatars/x")).isTrue();
        assertThat(storage.size("avatars/x")).isEqualTo(1);
    }

    @Test
    void anotherTenantCannotReadOrSeeThatObjectByTheLogicalKey() {
        asTenant("t_a");
        storage.put("avatars/x", "A".getBytes(StandardCharsets.UTF_8), "text/plain");

        asTenant("t_b");
        assertThat(storage.exists("avatars/x")).isFalse();
        assertThatThrownBy(() -> storage.get("avatars/x")).isInstanceOf(FileStorageException.class);
    }

    @Test
    void aPhysicalKeyOfAnotherTenantIsRefusedEvenWhenTheObjectExists() {
        asTenant("t_a");
        storage.put("avatars/x", "A".getBytes(StandardCharsets.UTF_8), "text/plain");

        asTenant("t_b");
        for (var attempt :
                List.<Runnable>of(
                        () -> storage.get("tenant/t_a/avatars/x"),
                        () -> storage.size("tenant/t_a/avatars/x"),
                        () -> storage.getRange("tenant/t_a/avatars/x", 0, 0),
                        () -> storage.exists("tenant/t_a/avatars/x"),
                        () -> storage.delete("tenant/t_a/avatars/x"))) {
            assertThatThrownBy(attempt::run)
                    .isInstanceOf(FileStorageException.class)
                    .hasMessageContaining("does not belong to the current tenant");
        }
        assertThat(bucket).containsKey("tenant/t_a/avatars/x");
    }

    @Test
    void theLegacyBareKeyFallbackServesOnlyAllowlistedNamespaces() throws IOException {
        bucket.put("avatars/old", "legacy".getBytes(StandardCharsets.UTF_8));
        bucket.put("tenant-exports/t_a/1.json", "secret".getBytes(StandardCharsets.UTF_8));
        asTenant("t_b");

        assertThat(read("avatars/old")).isEqualTo("legacy");
        assertThatThrownBy(() -> storage.get("tenant-exports/t_a/1.json"))
                .isInstanceOf(FileStorageException.class);
        assertThat(storage.exists("tenant-exports/t_a/1.json")).isFalse();
    }

    @Test
    void theLegacyFallbackCanBeSwitchedOffEntirely() {
        bucket.put("avatars/old", "legacy".getBytes(StandardCharsets.UTF_8));
        properties.setLegacyUnprefixedFallback(false);
        asTenant("t_b");

        assertThatThrownBy(() -> storage.get("avatars/old"))
                .isInstanceOf(FileStorageException.class);
    }

    @Test
    void deleteRemovesBothTheScopedAndTheLegacyBareObject() {
        bucket.put("avatars/x", "bare".getBytes(StandardCharsets.UTF_8));
        asTenant("t_a");
        storage.put("avatars/x", "scoped".getBytes(StandardCharsets.UTF_8), "text/plain");

        storage.delete("avatars/x");

        assertThat(bucket).isEmpty();
    }

    @Test
    void withNoTenantTheKeyIsUsedAsIs() {
        storage.put("avatars/x", "A".getBytes(StandardCharsets.UTF_8), "text/plain");

        assertThat(bucket).containsOnlyKeys("avatars/x");
    }

    @Test
    void listAcrossTenantsReportsEachObjectWithItsOwnerAndTheLogicalKey() {
        asTenant("t_a");
        storage.put("generated-images/1", new byte[1], "image/png");
        asTenant("t_b");
        storage.put("generated-images/2", new byte[1], "image/png");
        TenantContext.clear();
        bucket.put("generated-images/legacy", new byte[1]);

        List<FileStorageService.StoredObject> all = storage.listAcrossTenants("generated-images/");

        assertThat(all)
                .extracting(
                        FileStorageService.StoredObject::tenantId,
                        FileStorageService.StoredObject::key)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("t_a", "generated-images/1"),
                        org.assertj.core.groups.Tuple.tuple("t_b", "generated-images/2"),
                        org.assertj.core.groups.Tuple.tuple(null, "generated-images/legacy"));
    }

    @Test
    void anInvalidByteRangeIsRejectedBeforeAnyBackendCall() {
        assertThatThrownBy(() -> storage.getRange("avatars/x", 5, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.getRange("avatars/x", -1, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTenantPrefixedKeyWithALineBreakInItIsStillRecognisedAsAnotherTenantsKey() {
        // '.' does not match a line terminator, so without DOTALL such a key skipped the ownership
        // check.
        asTenant("t_b");
        assertThatThrownBy(() -> storage.get("tenant/t_a/avatars/x\ny"))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("does not belong to the current tenant");
    }
}
