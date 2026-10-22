package com.agenticai.core.security.localauth;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The RS256 signing keys of local auth mode. One key signs; recently retired keys keep verifying
 * until the tokens they signed have expired, so a rotation never logs anybody out.
 *
 * <p><b>Storage.</b> A single JSON file {@code signing-keys.json} under the data directory ({@code
 * app.auth.local.data-dir}). The directory is created {@code 0700} and the file {@code 0600} (on a
 * POSIX file system; elsewhere the platform default applies and a warning is logged). Writes go to
 * a fresh {@code 0600} temp file first and are moved into place atomically, so a crash mid-write
 * can never leave a half-written or world-readable key file. A file that exists but cannot be
 * parsed stops the boot instead of being replaced: silently minting a new key would invalidate
 * every session with no trace of why.
 *
 * <p><b>Rotation.</b> The current key is replaced once it is older than {@code
 * key-rotation-interval}; the old one is stamped retired and dropped after {@code key-retention}.
 * The check is done lazily whenever the signing key is asked for, so no scheduler is needed and an
 * installation that was powered off for a year rotates on its first request. {@link #rotate()}
 * forces it (key compromise).
 *
 * <p>Single-writer: one core process owns a data directory. Two processes pointed at the same
 * directory would each rotate and overwrite the other's key file.
 */
public final class LocalSigningKeyStore {

    private static final Logger log = LoggerFactory.getLogger(LocalSigningKeyStore.class);
    static final String FILE_NAME = "signing-keys.json";
    private static final int RSA_BITS = 2048;
    private static final ObjectMapper JSON = JsonMappers.plain();

    /** One persisted key. The private key is PKCS#8, the public key X.509, both base64. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredKey(
            String kid, String createdAt, String retiredAt, String privateKey, String publicKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredKeySet(int version, List<StoredKey> keys) {}

    /** A key held in memory. */
    private record Key(
            String kid,
            Instant createdAt,
            Instant retiredAt,
            RSAPrivateKey privateKey,
            RSAPublicKey publicKey) {
        Key retire(Instant at) {
            return new Key(kid, createdAt, at, privateKey, publicKey);
        }
    }

    /** What a caller needs to sign one token. */
    public record SigningKey(String kid, RSAPrivateKey privateKey) {}

    private final Path file;
    private final Duration rotationInterval;
    private final Duration retention;
    private final Clock clock;
    private List<Key> keys = new ArrayList<>();

    public LocalSigningKeyStore(
            Path dataDir, Duration rotationInterval, Duration retention, Clock clock) {
        this.file = dataDir.resolve(FILE_NAME);
        this.rotationInterval = rotationInterval;
        this.retention = retention;
        this.clock = clock;
        ensureDirectory(dataDir);
        load();
        rotateIfDue();
    }

    /** The key to sign with now; rotates first when the current key is due. */
    public synchronized SigningKey signingKey() {
        rotateIfDue();
        Key current = current();
        return new SigningKey(current.kid(), current.privateKey());
    }

    /** Public JWKS of every key that may still have signed a live token. */
    public synchronized JWKSet publicJwkSet() {
        pruneRetired();
        List<JWK> jwks = new ArrayList<>();
        for (Key key : keys) {
            jwks.add(
                    new RSAKey.Builder(key.publicKey())
                            .keyID(key.kid())
                            .keyUse(KeyUse.SIGNATURE)
                            .algorithm(JWSAlgorithm.RS256)
                            .build());
        }
        return new JWKSet(jwks);
    }

    /** Forces a rotation now (suspected key compromise). Retired keys still verify until pruned. */
    public synchronized void rotate() {
        Instant now = clock.instant();
        List<Key> next = new ArrayList<>();
        for (Key key : keys) {
            next.add(key.retiredAt() == null ? key.retire(now) : key);
        }
        next.add(generate(now));
        keys = next;
        pruneRetired();
        persist();
        log.info("Local auth signing key rotated; {} key(s) now verify tokens", keys.size());
    }

    /** Number of keys currently held (current plus retired-but-retained). For tests and metrics. */
    public synchronized int keyCount() {
        return keys.size();
    }

    /** Kid of the key {@link #signingKey()} would use, without rotating. For tests. */
    public synchronized String currentKid() {
        return current().kid();
    }

    private Key current() {
        for (int i = keys.size() - 1; i >= 0; i--) {
            if (keys.get(i).retiredAt() == null) {
                return keys.get(i);
            }
        }
        throw new IllegalStateException("local auth key store has no current key");
    }

    private void rotateIfDue() {
        Instant now = clock.instant();
        if (keys.isEmpty() || current().createdAt().plus(rotationInterval).isBefore(now)) {
            if (keys.isEmpty()) {
                keys = new ArrayList<>(List.of(generate(now)));
                persist();
                log.info("Generated the local auth signing key under {}", file.getParent());
            } else {
                rotate();
            }
            return;
        }
        if (pruneRetired()) {
            persist();
        }
    }

    /** Drops retired keys older than the retention; true when something was dropped. */
    private boolean pruneRetired() {
        Instant cutoff = clock.instant().minus(retention);
        boolean removed =
                keys.removeIf(k -> k.retiredAt() != null && k.retiredAt().isBefore(cutoff));
        return removed;
    }

    private Key generate(Instant now) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_BITS);
            KeyPair pair = generator.generateKeyPair();
            return new Key(
                    UUID.randomUUID().toString(),
                    now,
                    null,
                    (RSAPrivateKey) pair.getPrivate(),
                    (RSAPublicKey) pair.getPublic());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not generate the local auth signing key", e);
        }
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        tightenPermissions(file);
        try {
            StoredKeySet stored =
                    JSON.readValue(
                            Files.readString(file, StandardCharsets.UTF_8), StoredKeySet.class);
            if (stored == null || stored.keys() == null || stored.keys().isEmpty()) {
                throw new IllegalStateException("no keys in " + file);
            }
            KeyFactory factory = KeyFactory.getInstance("RSA");
            List<Key> loaded = new ArrayList<>();
            for (StoredKey s : stored.keys()) {
                loaded.add(
                        new Key(
                                s.kid(),
                                Instant.parse(s.createdAt()),
                                s.retiredAt() == null ? null : Instant.parse(s.retiredAt()),
                                (RSAPrivateKey)
                                        factory.generatePrivate(
                                                new PKCS8EncodedKeySpec(
                                                        Base64.getDecoder()
                                                                .decode(s.privateKey()))),
                                (RSAPublicKey)
                                        factory.generatePublic(
                                                new X509EncodedKeySpec(
                                                        Base64.getDecoder()
                                                                .decode(s.publicKey())))));
            }
            keys = loaded;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException(
                    "Cannot read the local auth signing keys at "
                            + file
                            + ". Refusing to replace them silently (that would sign every user out). "
                            + "Fix or delete the file to start fresh.",
                    e);
        }
    }

    private void persist() {
        List<StoredKey> out = new ArrayList<>();
        for (Key key : keys) {
            out.add(
                    new StoredKey(
                            key.kid(),
                            key.createdAt().toString(),
                            key.retiredAt() == null ? null : key.retiredAt().toString(),
                            Base64.getEncoder().encodeToString(key.privateKey().getEncoded()),
                            Base64.getEncoder().encodeToString(key.publicKey().getEncoded())));
        }
        Path tmp = null;
        try {
            tmp = createOwnerOnlyTempFile(file.getParent());
            Files.writeString(
                    tmp, JSON.writeValueAsString(new StoredKeySet(1, out)), StandardCharsets.UTF_8);
            try {
                Files.move(
                        tmp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort; the temp file is already owner-only
                }
            }
            throw new IllegalStateException(
                    "could not write the local auth signing keys to " + file, e);
        }
    }

    // ------------------------------------------------------------------ file permission helpers

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static void ensureDirectory(Path dir) {
        try {
            if (posix(dir.toAbsolutePath())) {
                Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rwx------");
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(ownerOnly));
                // A directory that already existed (an operator-made volume mount) keeps whatever
                // mode it had; the key file inside is 0600 regardless, but close the directory too.
                if (!ownerOnly.equals(Files.getPosixFilePermissions(dir))) {
                    try {
                        Files.setPosixFilePermissions(dir, ownerOnly);
                    } catch (IOException | UnsupportedOperationException e) {
                        log.warn(
                                "Local auth data directory {} is not owner-only and could not be"
                                        + " restricted ({}); the key file inside is still 0600",
                                dir,
                                e.toString());
                    }
                }
            } else {
                Files.createDirectories(dir);
                log.warn(
                        "Local auth data directory {} is on a file system without POSIX permissions;"
                                + " restrict access to it yourself",
                        dir);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "could not create the local auth data directory " + dir, e);
        }
    }

    /** Creates an empty file only the owner can read or write, in {@code dir}. */
    static Path createOwnerOnlyTempFile(Path dir) throws IOException {
        if (posix(dir)) {
            return Files.createTempFile(
                    dir,
                    "keys-",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(dir, "keys-", ".tmp");
    }

    private static void tightenPermissions(Path path) {
        if (!posix(path)) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            if (!perms.equals(ownerOnly)) {
                Files.setPosixFilePermissions(path, ownerOnly);
                log.warn(
                        "Local auth key file {} was readable by others; restricted to its owner",
                        path);
            }
        } catch (IOException e) {
            log.warn("Could not verify permissions of {}: {}", path, e.toString());
        }
    }
}
