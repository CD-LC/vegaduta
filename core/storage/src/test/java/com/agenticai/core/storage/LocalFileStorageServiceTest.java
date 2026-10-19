package com.agenticai.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalFileStorageServiceTest {

    @TempDir
    Path tempDir;

    private Path root;
    private LocalFileStorageService service;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("store");
        StorageProperties props = new StorageProperties();
        props.setType("local");
        props.getLocal().setPath(root.toString());
        service = new LocalFileStorageService(props);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
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
    void createsRootDirectoryOnStartup() {
        assertThat(root).isDirectory();
        assertThat(service.root()).isEqualTo(root.toAbsolutePath().normalize());
    }

    @Test
    void roundTripsWithoutTenant() throws IOException {
        service.put("chat-attachments/a.txt", bytes("hello"), "text/plain");
        assertThat(service.exists("chat-attachments/a.txt")).isTrue();
        assertThat(service.size("chat-attachments/a.txt")).isEqualTo(5);
        assertThat(read(service.get("chat-attachments/a.txt"))).isEqualTo("hello");
        assertThat(root.resolve("chat-attachments/a.txt")).isRegularFile();
    }

    @Test
    void overwriteReplacesContentAndLeavesNoTempFiles() throws IOException {
        service.put("x/y.bin", bytes("first"), null);
        service.put("x/y.bin", bytes("second!"), null);
        assertThat(read(service.get("x/y.bin"))).isEqualTo("second!");
        try (Stream<Path> files = Files.list(root.resolve("x"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("y.bin");
        }
    }

    @Test
    void tenantContextScopesKeys() throws IOException {
        TenantContext.set(TenantContext.of("t1", "user", Set.of()));
        service.put("chat-attachments/f.txt", bytes("t1 data"), "text/plain");
        assertThat(root.resolve("tenant/t1/chat-attachments/f.txt")).isRegularFile();
        assertThat(read(service.get("chat-attachments/f.txt"))).isEqualTo("t1 data");

        TenantContext.set(TenantContext.of("t2", "user", Set.of()));
        assertThat(service.exists("chat-attachments/f.txt")).isFalse();
        assertThatThrownBy(() -> service.get("chat-attachments/f.txt")).isInstanceOf(FileStorageException.class);
    }

    @Test
    void refusesKeyEmbeddingAnotherTenantsPrefix() {
        TenantContext.set(TenantContext.of("t2", "user", Set.of()));
        assertThatThrownBy(() -> service.get("tenant/t1/chat-attachments/f.txt"))
            .isInstanceOf(FileStorageException.class)
            .hasMessageContaining("does not belong to the current tenant");
        assertThatThrownBy(() -> service.delete("tenant/t1/x"))
            .isInstanceOf(FileStorageException.class);
    }

    @Test
    void bareKeyFallbackOnlyForAllowlistedPrefixes() throws IOException {
        // Opt-in only since SEC-051 (local default is off - see the test below).
        StorageProperties props = new StorageProperties();
        props.getLocal().setPath(root.toString());
        props.getLocal().setLegacyUnprefixedFallback(true);
        LocalFileStorageService service = new LocalFileStorageService(props);
        service.put("generated-images/old.png", bytes("legacy"), "image/png");
        service.put("secret-exports/dump.json", bytes("nope"), "application/json");

        TenantContext.set(TenantContext.of("t1", "user", Set.of()));
        assertThat(read(service.get("generated-images/old.png"))).isEqualTo("legacy");
        assertThat(service.exists("secret-exports/dump.json")).isFalse();
        assertThatThrownBy(() -> service.get("secret-exports/dump.json")).isInstanceOf(FileStorageException.class);
    }

    /**
     * SEC-051: with default configuration a tenant can NOT read an un-prefixed
     * (legacy/global-namespace) key, even under an allowlisted prefix - the
     * cross-tenant read primitive is closed unless an operator opts in.
     */
    @Test
    void legacyUnprefixedKeyIsNotReadableByATenantByDefault() {
        assertThat(new StorageProperties().getLocal().isLegacyUnprefixedFallback()).isFalse();
        // Written with no TenantContext -> lands at the bare, global key.
        service.put("generated-images/other-tenants-legacy.png", bytes("secret"), "image/png");
        assertThat(root.resolve("generated-images/other-tenants-legacy.png")).isRegularFile();

        TenantContext.set(TenantContext.of("t1", "user", Set.of()));
        assertThat(service.exists("generated-images/other-tenants-legacy.png")).isFalse();
        assertThatThrownBy(() -> service.get("generated-images/other-tenants-legacy.png"))
            .isInstanceOf(FileStorageException.class);
        assertThatThrownBy(() -> service.size("generated-images/other-tenants-legacy.png"))
            .isInstanceOf(FileStorageException.class);
        assertThatThrownBy(() -> service.getRange("generated-images/other-tenants-legacy.png", 0, 2))
            .isInstanceOf(FileStorageException.class);
    }

    @Test
    void bareKeyFallbackCanBeDisabled() {
        StorageProperties props = new StorageProperties();
        props.getLocal().setPath(root.toString());
        props.setLegacyUnprefixedFallback(false);
        LocalFileStorageService strict = new LocalFileStorageService(props);
        strict.put("generated-images/old.png", bytes("legacy"), "image/png");

        TenantContext.set(TenantContext.of("t1", "user", Set.of()));
        assertThat(strict.exists("generated-images/old.png")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "../escape.txt",
        "a/../../escape.txt",
        "a/../b.txt",
        "./a.txt",
        "/etc/passwd",
        "C:/Windows/win.ini",
        "a\\..\\..\\escape.txt",
        "a//b.txt",
        "a/b/",
        "..",
        "a/.upload-123.tmp",
        "nul\0byte",
        " "
    })
    void rejectsTraversalAndMalformedKeys(String key) {
        assertThatThrownBy(() -> service.put(key, bytes("x"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.get(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.exists(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete(key)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tempDir.resolve("escape.txt")).doesNotExist();
    }

    @Test
    void maliciousTenantIdCannotEscapeRoot() {
        TenantContext.set(TenantContext.of("..", "user", Set.of()));
        assertThatThrownBy(() -> service.put("x.txt", bytes("x"), null)).isInstanceOf(IllegalArgumentException.class);
        TenantContext.set(TenantContext.of("../../..", "user", Set.of()));
        assertThatThrownBy(() -> service.put("x.txt", bytes("x"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tempDir.resolve("x.txt")).doesNotExist();
    }

    @Test
    void rangeReadsReturnInclusiveWindowAndClampToEnd() throws IOException {
        service.put("v/clip.bin", bytes("0123456789"), null);
        assertThat(read(service.getRange("v/clip.bin", 2, 5))).isEqualTo("2345");
        assertThat(read(service.getRange("v/clip.bin", 8, 100))).isEqualTo("89");
        assertThatThrownBy(() -> service.getRange("v/clip.bin", 10, 12)).isInstanceOf(FileStorageException.class);
        assertThatThrownBy(() -> service.getRange("v/clip.bin", -1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getRange("v/clip.bin", 5, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingObjectsFailLikeS3() {
        assertThat(service.exists("nope/x")).isFalse();
        assertThatThrownBy(() -> service.get("nope/x")).isInstanceOf(FileStorageException.class);
        assertThatThrownBy(() -> service.size("nope/x")).isInstanceOf(FileStorageException.class);
        assertThatThrownBy(() -> service.getRange("nope/x", 0, 1)).isInstanceOf(FileStorageException.class);
    }

    @Test
    void deleteRemovesScopedAndBareCopiesAndIsIdempotent() {
        service.put("avatars/a.png", bytes("bare"), null);
        TenantContext.set(TenantContext.of("t1", "user", Set.of()));
        service.put("avatars/a.png", bytes("scoped"), null);
        service.delete("avatars/a.png");
        assertThat(root.resolve("tenant/t1/avatars/a.png")).doesNotExist();
        assertThat(root.resolve("avatars/a.png")).doesNotExist();
        service.delete("avatars/a.png");
    }

    @Test
    void blankPathFailsFast() {
        StorageProperties props = new StorageProperties();
        props.getLocal().setPath(" ");
        assertThatThrownBy(() -> new LocalFileStorageService(props))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_STORAGE_LOCAL_PATH");
    }
}
