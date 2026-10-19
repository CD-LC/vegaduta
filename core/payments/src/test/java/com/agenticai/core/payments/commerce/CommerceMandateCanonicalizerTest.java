package com.agenticai.core.payments.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Determinism of the signed canonical documents - same inputs must produce
 * byte-identical output (the stored canonical_json is verified verbatim),
 * and the cart's canonical form must bind its items hash so a signature
 * cannot be transplanted between carts.
 */
class CommerceMandateCanonicalizerTest {

    private final CommerceMandateCanonicalizer c = new CommerceMandateCanonicalizer();

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant EXP = Instant.parse("2026-10-03T00:00:00Z");

    @Test
    void intentCanonicalIsDeterministic() {
        String a = c.canonicalizeMandate(ID, CommerceMandateKind.INTENT, null, "acme",
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "user-1", "INR",
            50_000L, null, null, null, List.of("books", "electronics"), "buy a keyboard", EXP);
        String b = c.canonicalizeMandate(ID, CommerceMandateKind.INTENT, null, "acme",
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "user-1", "INR",
            50_000L, null, null, null, List.of("electronics", "books"), "buy a keyboard", EXP);
        assertEquals(a, b); // categories are sorted - order-insensitive
        assertTrue(a.contains("\"version\":\"vegaduta-commerce-mandate/1\""));
        assertTrue(a.contains("\"kind\":\"INTENT\""));
        assertTrue(a.contains("\"max_total_minor\":50000"));
    }

    @Test
    void cartCanonicalBindsItemsHash() {
        String itemsA = "[{\"sku\":\"KB-1\",\"name\":\"Keyboard\",\"qty\":1,\"unit_price_minor\":250000}]";
        String itemsB = "[{\"sku\":\"KB-2\",\"name\":\"Other\",\"qty\":1,\"unit_price_minor\":250000}]";
        String a = c.canonicalizeMandate(ID, CommerceMandateKind.CART,
            UUID.fromString("33333333-3333-3333-3333-333333333333"), "acme",
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "user-1", "INR",
            null, 250_000L, itemsA, "merchant-1", null, null, EXP);
        String b = c.canonicalizeMandate(ID, CommerceMandateKind.CART,
            UUID.fromString("33333333-3333-3333-3333-333333333333"), "acme",
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "user-1", "INR",
            null, 250_000L, itemsB, "merchant-1", null, null, EXP);
        // Same total, different items -> different canonical form (items_hash differs).
        assertNotEquals(a, b);
        assertTrue(a.contains("\"items_hash\":\""));
    }

    @Test
    void buyerAuthBindsCartHash() {
        String auth = c.canonicalizeBuyerAuth(ID,
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            "deadbeef", "user-1", 250_000L, "INR", Instant.parse("2026-10-02T12:00:00Z"));
        assertTrue(auth.contains("\"version\":\"vegaduta-buyer-auth/1\""));
        assertTrue(auth.contains("\"cart_hash\":\"deadbeef\""));
        assertTrue(auth.contains("\"buyer\":\"user-1\""));
    }

    @Test
    void canonicalHashIsSha256Hex() {
        assertEquals(64, c.canonicalHash("x").length());
        assertEquals(c.canonicalHash("x"), c.canonicalHash("x"));
        assertNotEquals(c.canonicalHash("x"), c.canonicalHash("y"));
    }
}
