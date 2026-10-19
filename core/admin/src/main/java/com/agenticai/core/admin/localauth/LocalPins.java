package com.agenticai.core.admin.localauth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Policy and hashing of device PINs.
 *
 * <p>A PIN is short, so it is never a standalone credential: it is only valid TOGETHER with a device
 * secret (32 random bytes) that the browser keeps and the server returns once at enrolment. The
 * stored hash covers {@code pin + ":" + secret}, so a stolen database or a guessed PIN is useless
 * without the secret, and without a secret there is nothing to guess against. The combined string is
 * at most 12 + 1 + 43 = 56 bytes, under bcrypt's 72-byte limit, so nothing is silently truncated
 * whichever encoder {@link LocalPasswords} is using.
 *
 * <p>Policy: 6 to 12 digits and nothing else; refused are one repeated digit, a straight run
 * (ascending or descending, wrapping past 9), a short block repeated to fill the PIN (121212,
 * 123123), runs of doubled digits (112233) and a small list of the commonest choices.
 */
@Component
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class LocalPins {

    public static final int MIN_LENGTH = 6;
    public static final int MAX_LENGTH = 12;
    /** Consecutive wrong PINs after which a device is revoked. */
    public static final int MAX_FAILURES = 10;

    private static final Set<String> COMMON =
            Set.of(
                    "123321", "159753", "147258", "258369", "123654", "654123", "321321", "100200", "123000",
                    "520520", "131313", "202020", "696969", "789789", "112211", "121314", "135790", "246810",
                    "135246", "112358");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final LocalPasswords passwords;

    public LocalPins(LocalPasswords passwords) {
        this.passwords = passwords;
    }

    /** The first policy violation, or {@code null} when the PIN is acceptable. */
    public static String violation(String pin) {
        if (pin == null || pin.length() < MIN_LENGTH || pin.length() > MAX_LENGTH) {
            return "The PIN must be " + MIN_LENGTH + " to " + MAX_LENGTH + " digits.";
        }
        for (int i = 0; i < pin.length(); i++) {
            if (pin.charAt(i) < '0' || pin.charAt(i) > '9') {
                return "The PIN can only contain digits.";
            }
        }
        if (isRun(pin, 1) || isRun(pin, 9) || allSame(pin) || repeatsABlock(pin) || isDoubledRun(pin) || COMMON.contains(pin)) {
            return "That PIN is too easy to guess. Avoid repeated or sequential digits.";
        }
        return null;
    }

    /** 32 random bytes, base64url without padding (43 characters). */
    public String newDeviceSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String pin, String deviceSecret) {
        return passwords.hash(pin + ":" + deviceSecret);
    }

    /** Constant-effort check: a missing hash, PIN or secret costs one dummy verification. */
    public boolean matches(String pin, String deviceSecret, String storedHash) {
        if (pin == null || deviceSecret == null) {
            passwords.matches("", null);
            return false;
        }
        return passwords.matches(pin + ":" + deviceSecret, storedHash);
    }

    private static boolean allSame(String pin) {
        return pin.chars().allMatch(c -> c == pin.charAt(0));
    }

    /** Every digit is the previous one plus {@code step} modulo 10 (1 = ascending, 9 = descending). */
    private static boolean isRun(String pin, int step) {
        for (int i = 1; i < pin.length(); i++) {
            if ((pin.charAt(i) - pin.charAt(i - 1) + 10) % 10 != step) {
                return false;
            }
        }
        return true;
    }

    /** A block of 2 or 3 digits repeated to fill the whole PIN: 121212, 123123, 696969. */
    private static boolean repeatsABlock(String pin) {
        for (int block = 1; block <= 3; block++) {
            if (pin.length() % block != 0 || pin.length() / block < 2) {
                continue;
            }
            String unit = pin.substring(0, block);
            if (unit.repeat(pin.length() / block).equals(pin)) {
                return true;
            }
        }
        return false;
    }

    /** Pairs of one digit each, stepping by one: 112233, 998877. */
    private static boolean isDoubledRun(String pin) {
        if (pin.length() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < pin.length(); i += 2) {
            if (pin.charAt(i) != pin.charAt(i + 1)) {
                return false;
            }
        }
        StringBuilder singles = new StringBuilder();
        for (int i = 0; i < pin.length(); i += 2) {
            singles.append(pin.charAt(i));
        }
        String s = singles.toString();
        return s.length() >= 3 && (isRun(s, 1) || isRun(s, 9));
    }
}
