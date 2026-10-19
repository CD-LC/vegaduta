package com.agenticai.core.security.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSigningKeyStoreTest {

    /** A clock the test can move. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final Duration ROTATE = Duration.ofDays(90);
    private static final Duration RETAIN = Duration.ofDays(1);

    @Test
    void firstStartGeneratesAKeyAndPersistsItOwnerOnly(@TempDir Path dir) throws IOException {
        Path data = dir.resolve("auth");
        LocalSigningKeyStore store = new LocalSigningKeyStore(data, ROTATE, RETAIN, Clock.systemUTC());

        assertThat(store.keyCount()).isEqualTo(1);
        Path file = data.resolve(LocalSigningKeyStore.FILE_NAME);
        assertThat(file).exists();
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(data))).isEqualTo("rwx------");
        }
    }

    @Test
    void restartKeepsTheSameKeySoSessionsSurvive(@TempDir Path dir) {
        String kid = new LocalSigningKeyStore(dir, ROTATE, RETAIN, Clock.systemUTC()).currentKid();
        assertThat(new LocalSigningKeyStore(dir, ROTATE, RETAIN, Clock.systemUTC()).currentKid()).isEqualTo(kid);
    }

    @Test
    void noTemporaryFileIsLeftBehind(@TempDir Path dir) throws IOException {
        LocalSigningKeyStore store = new LocalSigningKeyStore(dir, ROTATE, RETAIN, Clock.systemUTC());
        store.rotate();
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly(LocalSigningKeyStore.FILE_NAME);
        }
    }

    @Test
    void rotatesWhenTheCurrentKeyIsOldAndKeepsTheRetiredKeyForVerification(@TempDir Path dir) {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LocalSigningKeyStore store = new LocalSigningKeyStore(dir, ROTATE, RETAIN, clock);
        String first = store.currentKid();

        clock.advance(ROTATE.plusMinutes(1));
        String second = store.signingKey().kid();

        assertThat(second).isNotEqualTo(first);
        assertThat(store.publicJwkSet().getKeyByKeyId(first)).as("retired key still verifies").isNotNull();
        assertThat(store.publicJwkSet().getKeyByKeyId(second)).isNotNull();
    }

    @Test
    void aRetiredKeyIsDroppedAfterTheRetentionWindow(@TempDir Path dir) {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LocalSigningKeyStore store = new LocalSigningKeyStore(dir, ROTATE, RETAIN, clock);
        String first = store.currentKid();
        store.rotate();

        clock.advance(RETAIN.plusMinutes(1));

        assertThat(store.publicJwkSet().getKeyByKeyId(first)).isNull();
        assertThat(store.keyCount()).isEqualTo(1);
    }

    @Test
    void publishedJwksNeverContainsPrivateMaterial(@TempDir Path dir) {
        LocalSigningKeyStore store = new LocalSigningKeyStore(dir, ROTATE, RETAIN, Clock.systemUTC());
        assertThat(store.publicJwkSet().toJSONObject(true).toString()).doesNotContain("\"d\"");
    }

    @Test
    void aCorruptKeyFileStopsTheBootInsteadOfSilentlyReplacingTheKey(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(LocalSigningKeyStore.FILE_NAME), "{not json");

        assertThatThrownBy(() -> new LocalSigningKeyStore(dir, ROTATE, RETAIN, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to replace");
    }

    @Test
    void anExistingLooseDirectoryIsRestrictedToItsOwner(@TempDir Path dir) throws IOException {
        if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));

        new LocalSigningKeyStore(dir, ROTATE, RETAIN, Clock.systemUTC());

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
    }

    @Test
    void aLooseFilePermissionIsTightenedOnLoad(@TempDir Path dir) throws IOException {
        new LocalSigningKeyStore(dir, ROTATE, RETAIN, Clock.systemUTC());
        Path file = dir.resolve(LocalSigningKeyStore.FILE_NAME);
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        new LocalSigningKeyStore(dir, ROTATE, RETAIN, Clock.systemUTC());

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }
}
