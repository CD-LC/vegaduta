package com.agenticai.core.admin.localauth;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Password hashing and policy for local mode.
 *
 * <p><b>Hashing.</b> A {@link DelegatingPasswordEncoder}: stored values carry their algorithm id
 * ({@code {bcrypt}...}, {@code {argon2@SpringSecurity_v5_8}...}), so the algorithm can be changed
 * later without a migration - old hashes keep verifying and {@link #needsUpgrade} tells the login
 * path to re-hash on the next successful sign-in. New hashes use Argon2id when BouncyCastle is on
 * the classpath (Spring's Argon2 encoder needs it) and bcrypt (cost 12) otherwise, so the choice
 * never depends on a dependency this project does not ship.
 *
 * <p><b>Policy.</b> Matches the signup form: at least 12 characters. The upper bound is 72 bytes
 * because bcrypt silently ignores everything past byte 72, and a limit that is enforced beats a
 * password that is quietly truncated. The password must not simply be the account's e-mail address.
 */
@Component
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public final class LocalPasswords {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private static final String BCRYPT = "bcrypt";
    private static final String ARGON2 = "argon2@SpringSecurity_v5_8";

    private final DelegatingPasswordEncoder encoder;
    private final String dummyHash;

    public LocalPasswords() {
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put(BCRYPT, new BCryptPasswordEncoder(12));
        String current = BCRYPT;
        if (argon2Available()) {
            encoders.put(ARGON2, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
            current = ARGON2;
        }
        this.encoder = new DelegatingPasswordEncoder(current, encoders);
        // Verified against when the account does not exist, so "no such user" and "wrong password"
        // cost the same time and cannot be told apart by timing.
        this.dummyHash = encoder.encode("dummy-password-for-constant-time-checks");
    }

    private static boolean argon2Available() {
        try {
            Class.forName("org.bouncycastle.crypto.generators.Argon2BytesGenerator");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** Constant-effort check; {@code storedHash} may be null (no password set / unknown account). */
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null) {
            return false;
        }
        if (storedHash == null || storedHash.isBlank()) {
            encoder.matches(rawPassword, dummyHash);
            return false;
        }
        try {
            return encoder.matches(rawPassword, storedHash);
        } catch (IllegalArgumentException e) {
            // A hash with an unknown algorithm id: treat as not matching rather than failing the request.
            return false;
        }
    }

    /** True when {@code storedHash} was made with an older algorithm than the current one. */
    public boolean needsUpgrade(String storedHash) {
        return storedHash != null && encoder.upgradeEncoding(storedHash);
    }

    /** The first policy violation, or {@code null} when the password is acceptable. */
    public static String violation(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "The password must be at least " + MIN_LENGTH + " characters long.";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return "The password is too long (at most " + MAX_BYTES + " bytes).";
        }
        if (password.isBlank()) {
            return "The password cannot be only whitespace.";
        }
        if (email != null && password.toLowerCase(Locale.ROOT).equals(email.trim().toLowerCase(Locale.ROOT))) {
            return "The password cannot be your e-mail address.";
        }
        return null;
    }
}
