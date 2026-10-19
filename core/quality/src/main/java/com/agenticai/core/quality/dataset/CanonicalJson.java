package com.agenticai.core.quality.dataset;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Canonical JSON (object keys sorted at every level, no insignificant
 * whitespace, UTF-8) plus SHA-256, so a hash over a document means the same
 * thing to whoever re-computes it later. Used for the agent-config snapshot
 * hash and the exportable run bundle. Verification recipe for an outside
 * reader: parse the bundle's {@code content}, re-serialise with sorted keys
 * and compact separators, SHA-256 the UTF-8 bytes.
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = JsonMappers.plain()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .configure(SerializationFeature.INDENT_OUTPUT, false);

    private CanonicalJson() {
    }

    /** @throws IllegalStateException when the value cannot be serialised (a programming error) */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not canonicalise JSON: " + e.getMessage(), e);
        }
    }

    public static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is mandatory on every JVM
        }
    }
}
