package com.agenticai.core.common.studioapp;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure rules for studio-app custom domains: hostname validation, the DNS
 * challenge shape and the verification decision. No I/O, so it is unit-tested
 * directly; the DNS lookup itself is injected via {@link DnsTxtResolver}.
 */
public final class StudioAppDomainRules {

    /** Label prefix the owner publishes the TXT challenge under. */
    public static final String VERIFY_LABEL = "_vegaduta-verify";
    public static final int MAX_HOSTNAME_LENGTH = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern ALL_DIGITS = Pattern.compile("[0-9]+");

    /** Non-public suffixes that can never be claimed. (A hosted deployment also reserves its own platform domains.) */
    private static final List<String> RESERVED_SUFFIXES = List.of(
        "localhost", "local", "internal", "test", "example", "invalid", "onion");

    private StudioAppDomainRules() {
    }

    /** Domain-level validation failure; the message is a stable machine code. */
    public static final class InvalidHostnameException extends RuntimeException {
        public InvalidHostnameException(String code) {
            super(code);
        }
    }

    public enum Decision { VERIFIED, NOT_FOUND, MISMATCH, LOOKUP_ERROR }

    /**
     * Normalises and strictly validates a hostname: lowercase ASCII FQDN, at
     * least two labels, no wildcard, no IP literal, no platform-owned suffix.
     * Non-ASCII (IDN) must be supplied as punycode.
     */
    public static String normalizeHostname(String raw) {
        if (raw == null) {
            throw new InvalidHostnameException("hostname_required");
        }
        String h = raw.trim().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.isEmpty()) {
            throw new InvalidHostnameException("hostname_required");
        }
        if (h.contains("*")) {
            throw new InvalidHostnameException("wildcard_not_allowed");
        }
        if (h.length() > MAX_HOSTNAME_LENGTH) {
            throw new InvalidHostnameException("hostname_too_long");
        }
        if (h.contains(":") || h.contains("/") || h.contains("@") || h.contains(" ")) {
            throw new InvalidHostnameException("invalid_hostname");
        }
        String[] labels = h.split("\\.", -1);
        if (labels.length < 2) {
            throw new InvalidHostnameException("invalid_hostname");
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw new InvalidHostnameException("invalid_hostname");
            }
        }
        if (ALL_DIGITS.matcher(labels[labels.length - 1]).matches()) {
            // 1.2.3.4 style: the TLD is numeric, so this is an IP, not a name.
            throw new InvalidHostnameException("ip_not_allowed");
        }
        for (String reserved : RESERVED_SUFFIXES) {
            if (h.equals(reserved) || h.endsWith("." + reserved)) {
                throw new InvalidHostnameException("reserved_hostname");
            }
        }
        return h;
    }

    /** Fresh public challenge value for the TXT record. */
    public static String newVerificationToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "vd-verify-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String txtRecordName(String hostname) {
        return VERIFY_LABEL + "." + hostname;
    }

    /** Strips the surrounding quotes DNS TXT rdata carries and joins multi-string records. */
    public static String cleanTxtValue(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.indexOf('"') >= 0) {
            StringBuilder sb = new StringBuilder();
            boolean inQuote = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') {
                    inQuote = !inQuote;
                } else if (inQuote) {
                    sb.append(c);
                }
            }
            return sb.toString().trim();
        }
        return s;
    }

    /**
     * Decides from the TXT values found at the challenge name. {@code null}
     * means the lookup itself failed (timeout, SERVFAIL) and must not flip
     * the status; an empty list means the name has no TXT records yet.
     */
    public static Decision decide(String expectedToken, List<String> txtValues) {
        if (txtValues == null) {
            return Decision.LOOKUP_ERROR;
        }
        if (txtValues.isEmpty()) {
            return Decision.NOT_FOUND;
        }
        for (String v : txtValues) {
            if (expectedToken != null && expectedToken.equals(cleanTxtValue(v))) {
                return Decision.VERIFIED;
            }
        }
        return Decision.MISMATCH;
    }
}
