package com.agenticai.core.agents.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link VersionHashing} is the value-identity behind publish-time
 * deduplication AND the cross-check against V342's SQL backfill
 * ({@code encode(sha256(config_json::bytea),'hex')}). These tests pin the
 * contract that makes both halves interchangeable: canonical SHA-256,
 * lowercase hex, UTF-8 input - stable across JVM restarts and identical to
 * what Postgres computes over the same bytes.
 */
class VersionHashingTest {

    @Test
    void matchesThePublishedSha256Vectors() {
        // NIST/RFC vectors - if these ever fail, every dedupe comparison and
        // every SQL-backfilled hash in the table is wrong with them.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            VersionHashing.sha256Hex(""));
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            VersionHashing.sha256Hex("abc"));
    }

    @Test
    void isDeterministicForIdenticalDocuments() {
        String document = "{\"provider\":\"ANTHROPIC\",\"model\":\"claude-sonnet-4\",\"tools\":[\"a\",\"b\"]}";
        assertEquals(VersionHashing.sha256Hex(document), VersionHashing.sha256Hex(document));
    }

    @Test
    void isByteSensitive() {
        // Dedupe must distinguish documents that differ in ANY byte -
        // including ones a human would call "the same config".
        assertNotEquals(
            VersionHashing.sha256Hex("{\"a\":1}"),
            VersionHashing.sha256Hex("{\"a\":2}"));
        assertNotEquals(
            VersionHashing.sha256Hex("{\"a\":1}"),
            VersionHashing.sha256Hex("{\"a\":1 }"));
    }

    @Test
    void produces64LowercaseHexChars() {
        String hash = VersionHashing.sha256Hex("anything");
        assertEquals(64, hash.length());
        assertEquals(hash, hash.toLowerCase());
    }
}
