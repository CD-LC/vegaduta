package com.agenticai.core.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link McpApiKeyHasher} is the whole "the platform never stores a
 * plaintext inbound API key" guarantee in one class - the control_plane
 * link, the tenant-schema grant row, and every lookup on the hot auth path
 * all key off {@code sha256Hex}'s output. If the hash ever stopped being
 * stable across JVMs/restarts, every previously issued key would silently
 * stop resolving; if {@code displayPrefix} ever grew past its
 * identification-only remit, it would start leaking recoverable key
 * material into a column that is deliberately safe to show in a UI. Both
 * contracts are asserted here rather than left to the javadoc.
 */
class McpApiKeyHasherTest {

    @Test
    void sha256HexMatchesTheKnownTestVector() {
        // FIPS 180-2's own "abc" vector - if this fails, the digest is not
        // plain SHA-256 over UTF-8 bytes and every stored api_key_hash from
        // before the change is unresolvable (a full-platform key outage,
        // not a cosmetic bug).
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            McpApiKeyHasher.sha256Hex("abc"));
    }

    @Test
    void sha256HexIsDeterministicAcrossCalls() {
        String rawKey = McpApiKeyHasher.generateRawKey();
        assertEquals(McpApiKeyHasher.sha256Hex(rawKey), McpApiKeyHasher.sha256Hex(rawKey));
    }

    @Test
    void sha256HexIsSixtyFourLowercaseHexChars() {
        // The grant column is VARCHAR(64) and the control_plane link stores
        // the same string - a formatting drift (uppercase, base64, truncated)
        // would break lookups even with the digest itself intact.
        String hash = McpApiKeyHasher.sha256Hex(McpApiKeyHasher.generateRawKey());
        assertTrue(hash.matches("[0-9a-f]{64}"), "expected 64 lowercase hex chars, got: " + hash);
    }

    @Test
    void differentKeysProduceDifferentHashes() {
        assertNotEquals(
            McpApiKeyHasher.sha256Hex(McpApiKeyHasher.generateRawKey()),
            McpApiKeyHasher.sha256Hex(McpApiKeyHasher.generateRawKey()));
    }

    @Test
    void hashNeverContainsTheRawKey() {
        // The one-way property this module's whole "hash-only, never
        // reversible" storage decision rests on, in its cheapest observable
        // form: the stored value must not embed the plaintext.
        String rawKey = McpApiKeyHasher.generateRawKey();
        String hash = McpApiKeyHasher.sha256Hex(rawKey);
        assertFalse(hash.contains(rawKey));
        assertNotEquals(rawKey, hash);
    }

    @Test
    void generatedKeysCarryThePrefixAndFullEntropyLength() {
        String rawKey = McpApiKeyHasher.generateRawKey();
        assertTrue(rawKey.startsWith("vmcp_"));
        // 32 random bytes base64url-encoded without padding is 43 chars -
        // a shorter key means lost entropy, not a formatting nit.
        assertEquals("vmcp_".length() + 43, rawKey.length());
    }

    @Test
    void generatedKeysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertTrue(keys.add(McpApiKeyHasher.generateRawKey()), "duplicate raw key generated");
        }
    }

    @Test
    void displayPrefixIsAtMostEightCharsAfterThePrefix() {
        String rawKey = McpApiKeyHasher.generateRawKey();
        String displayPrefix = McpApiKeyHasher.displayPrefix(rawKey);
        assertEquals(8, displayPrefix.length());
        assertEquals(rawKey.substring(5, 13), displayPrefix);
        // Identification-only: 8 of 43 base64url chars must never be enough
        // to reconstruct the key - so it must not be the whole key either.
        assertFalse(rawKey.endsWith(displayPrefix));
    }

    @Test
    void displayPrefixOfAShortKeyTruncatesInsteadOfThrowing() {
        // Defensive edge: a hand-crafted/legacy short key must degrade to a
        // shorter prefix, not a StringIndexOutOfBoundsException on the
        // admin list screen.
        assertEquals("ab", McpApiKeyHasher.displayPrefix("vmcp_ab"));
    }
}
