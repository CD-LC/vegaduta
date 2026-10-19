package com.agenticai.core.agents.tools.browseruse;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords per RFC 6238 (HOTP, RFC 4226, over a time counter) with no
 * third-party dependency: HmacSHA1 (the default every authenticator app uses), HmacSHA256 and
 * HmacSHA512, 6 to 8 digits, any period. Pure functions - no clock, no I/O - so the RFC 6238
 * Appendix B test vectors pin them directly (see BrowserTotpTest).
 *
 * <p>Backs the {@code browser_totp} tool: the base32 seed lives in the secrets vault, is resolved
 * server-side and never leaves this JVM; only the short-lived derived code is returned.
 *
 * <p>Seeds are accepted as a bare base32 string (what a site shows under "can't scan the QR code?";
 * spaces, hyphens, padding and lower case are tolerated) or as a full {@code
 * otpauth://totp/...?secret=...&algorithm=SHA256&digits=8&period=60} URI, whose parameters then
 * override the defaults. Nothing in an exception message here ever contains the seed.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6238">RFC 6238</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4648#section-6">RFC 4648 base32</a>
 */
final class BrowserTotp {

    static final int DEFAULT_DIGITS = 6;
    static final int DEFAULT_PERIOD_SECONDS = 30;
    static final String DEFAULT_ALGORITHM = "SHA1";

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** 10^0 .. 10^8, indexed by digit count. */
    private static final int[] POWERS_OF_TEN = {
        1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000
    };

    private BrowserTotp() {}

    /**
     * A parsed seed and the parameters it was provisioned with. {@code key} is secret: never log or
     * return it.
     */
    record Config(byte[] key, String algorithm, int digits, int periodSeconds) {

        @Override
        public String toString() {
            // Records print every component; the key must never end up in a log line by accident.
            return "Config[algorithm="
                    + algorithm
                    + ", digits="
                    + digits
                    + ", periodSeconds="
                    + periodSeconds
                    + "]";
        }
    }

    /** One generated code and how long it stays valid. */
    record Code(String value, int validForSeconds) {}

    /**
     * RFC 6238 section 4: {@code T = floor(unixSeconds / period)}, HOTP(K, T) truncated to {@code
     * digits}.
     *
     * @param algorithm {@code SHA1}, {@code SHA256} or {@code SHA512} (case-insensitive, with or
     *     without a {@code Hmac} prefix)
     */
    static String generate(
            byte[] key, long unixSeconds, int periodSeconds, int digits, String algorithm) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("TOTP key is empty");
        }
        if (periodSeconds <= 0) {
            throw new IllegalArgumentException("TOTP period must be positive");
        }
        if (digits < 6 || digits > 8) {
            throw new IllegalArgumentException("TOTP digits must be 6, 7 or 8");
        }
        if (unixSeconds < 0) {
            throw new IllegalArgumentException("TOTP time must not be negative");
        }
        long counter = unixSeconds / periodSeconds;
        byte[] message = new byte[8];
        for (int i = 7; i >= 0; i--) {
            message[i] = (byte) (counter & 0xFF);
            counter >>>= 8;
        }
        byte[] hash = hmac(hmacName(algorithm), key, message);
        // RFC 4226 section 5.3 dynamic truncation.
        int offset = hash[hash.length - 1] & 0x0F;
        int binary =
                ((hash[offset] & 0x7F) << 24)
                        | ((hash[offset + 1] & 0xFF) << 16)
                        | ((hash[offset + 2] & 0xFF) << 8)
                        | (hash[offset + 3] & 0xFF);
        int otp = binary % POWERS_OF_TEN[digits];
        String text = Integer.toString(otp);
        StringBuilder padded = new StringBuilder(digits);
        for (int i = text.length(); i < digits; i++) {
            padded.append('0');
        }
        return padded.append(text).toString();
    }

    /**
     * The code for {@code config} at {@code unixSeconds}, plus the seconds left in its time step
     * (1..period).
     */
    static Code codeAt(Config config, long unixSeconds) {
        String value =
                generate(
                        config.key(),
                        unixSeconds,
                        config.periodSeconds(),
                        config.digits(),
                        config.algorithm());
        int remaining = config.periodSeconds() - (int) (unixSeconds % config.periodSeconds());
        return new Code(value, remaining);
    }

    /**
     * Parses a vault value into a {@link Config}: a bare base32 seed, or an {@code otpauth://totp/}
     * URI.
     *
     * @throws IllegalArgumentException (message never contains the seed) when it is neither
     */
    static Config parseSecret(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("the TOTP secret is empty");
        }
        String trimmed = raw.strip();
        String seed = trimmed;
        String algorithm = DEFAULT_ALGORITHM;
        int digits = DEFAULT_DIGITS;
        int period = DEFAULT_PERIOD_SECONDS;
        if (trimmed.regionMatches(true, 0, "otpauth://", 0, "otpauth://".length())) {
            String query;
            try {
                query = URI.create(trimmed).getRawQuery();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("the stored otpauth:// URI is not valid");
            }
            if (!trimmed.regionMatches(true, 0, "otpauth://totp/", 0, "otpauth://totp/".length())) {
                throw new IllegalArgumentException("the stored otpauth:// URI is not a TOTP URI");
            }
            seed = null;
            if (query != null) {
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String name = pair.substring(0, eq).toLowerCase(Locale.ROOT);
                    String value =
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    switch (name) {
                        case "secret" -> seed = value;
                        case "algorithm" -> algorithm = normalizeAlgorithm(value);
                        case "digits" -> digits = parseIntParam(value, "digits");
                        case "period" -> period = parseIntParam(value, "period");
                        default -> {
                            // issuer, label hints, counter, ... are irrelevant to code generation
                        }
                    }
                }
            }
            if (seed == null || seed.isBlank()) {
                throw new IllegalArgumentException("the stored otpauth:// URI has no secret");
            }
        }
        if (digits < 6 || digits > 8) {
            throw new IllegalArgumentException("TOTP digits must be 6, 7 or 8");
        }
        if (period < 1 || period > 300) {
            throw new IllegalArgumentException("TOTP period must be between 1 and 300 seconds");
        }
        return new Config(decodeBase32(seed), normalizeAlgorithm(algorithm), digits, period);
    }

    /**
     * RFC 4648 base32 (alphabet A-Z2-7). Tolerates lower case, spaces, hyphens and trailing {@code
     * =} padding.
     */
    static byte[] decodeBase32(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("the TOTP secret is empty");
        }
        StringBuilder clean = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == ' ' || c == '-' || c == '=' || c == '\t' || c == '\r' || c == '\n') {
                continue;
            }
            clean.append(Character.toUpperCase(c));
        }
        if (clean.length() == 0) {
            throw new IllegalArgumentException("the TOTP secret is empty");
        }
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < clean.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(clean.charAt(i));
            if (value < 0) {
                // Deliberately does not echo the offending character or any of the input.
                throw new IllegalArgumentException("the TOTP secret is not valid base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                if (index < out.length) {
                    out[index++] = (byte) ((buffer >> bits) & 0xFF);
                }
            }
        }
        if (index == 0) {
            throw new IllegalArgumentException("the TOTP secret is too short");
        }
        return index == out.length ? out : java.util.Arrays.copyOf(out, index);
    }

    private static int parseIntParam(String value, String name) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("the stored otpauth:// URI has an invalid " + name);
        }
    }

    private static String normalizeAlgorithm(String algorithm) {
        String upper = algorithm == null ? "" : algorithm.strip().toUpperCase(Locale.ROOT);
        if (upper.startsWith("HMAC")) {
            upper = upper.substring(4);
        }
        upper = upper.replace("-", "");
        return switch (upper) {
            case "", "SHA1" -> "SHA1";
            case "SHA256" -> "SHA256";
            case "SHA512" -> "SHA512";
            default ->
                    throw new IllegalArgumentException(
                            "unsupported TOTP algorithm (SHA1, SHA256 and SHA512 are supported)");
        };
    }

    private static String hmacName(String algorithm) {
        return "Hmac" + normalizeAlgorithm(algorithm);
    }

    private static byte[] hmac(String jcaName, byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(jcaName);
            mac.init(new SecretKeySpec(key, jcaName));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC is unavailable on this JVM", e);
        }
    }
}
