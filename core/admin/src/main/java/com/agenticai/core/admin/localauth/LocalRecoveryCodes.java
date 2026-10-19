package com.agenticai.core.admin.localauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One-time recovery codes: how a person who lost every device gets back in. A set is 8 codes of 16
 * characters from an alphabet without 0, O, 1 and I (about 80 bits each, so guessing is not a
 * concern; the sign-in throttle is only there to bound the work), shown as {@code XXXX-XXXX-XXXX-XXXX}.
 * Only the SHA-256 of the normalised code is stored. A code works once; generating a new set makes
 * every older code useless.
 */
@Component
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class LocalRecoveryCodes {

    public static final int COUNT = 8;
    static final int LENGTH = 16;
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final LocalCredentialStore store;
    private final Clock clock;

    @Autowired
    public LocalRecoveryCodes(LocalCredentialStore store) {
        this(store, Clock.systemUTC());
    }

    LocalRecoveryCodes(LocalCredentialStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Replaces the account's codes with a new set and returns the new codes, formatted for display. */
    public List<String> regenerate(String accountId) {
        List<String> display = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            StringBuilder raw = new StringBuilder(LENGTH);
            for (int c = 0; c < LENGTH; c++) {
                raw.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            hashes.add(hash(raw.toString()));
            display.add(format(raw.toString()));
        }
        store.replaceRecoveryCodes(accountId, hashes, clock.instant());
        return display;
    }

    /** Spends a code typed by a person; returns the account it belonged to. */
    public Optional<String> consume(String typed) {
        String normalised = normalise(typed);
        if (normalised.length() != LENGTH) {
            return Optional.empty();
        }
        return store.consumeRecoveryCode(hash(normalised), clock.instant());
    }

    public int remaining(String accountId) {
        return store.countUnusedRecoveryCodes(accountId);
    }

    /** Upper-cases and drops everything that is not a letter or digit (dashes, spaces). */
    static String normalise(String typed) {
        if (typed == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : typed.toUpperCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                out.append(c);
            }
        }
        return out.toString();
    }

    static String format(String raw) {
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12);
    }

    static String hash(String normalised) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
