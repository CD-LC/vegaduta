package com.agenticai.core.payments.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link CommerceMandate}'s lifecycle: an INTENT is born ACTIVE, a CART waits
 * at PENDING_AUTHORIZATION until {@code applyBuyerAuthorization} moves it to
 * AUTHORIZED with the signed sub-document attached, and the terminal marks
 * (capture/fail/refund/revoke) stamp {@code updatedAt} and, for revoke, the
 * who/when/why. {@link CommerceMandateException}'s HTTP mapping is part of
 * the API contract and pinned here too.
 */
class CommerceMandateEntityTest {

    @Test
    void kindAndStatusCarryTheDocumentedLifecycle() {
        assertEquals(2, CommerceMandateKind.values().length);
        assertEquals(CommerceMandateKind.INTENT, CommerceMandateKind.valueOf("INTENT"));
        assertEquals(CommerceMandateKind.CART, CommerceMandateKind.valueOf("CART"));
        assertEquals(8, CommerceMandateStatus.values().length);
    }

    @Test
    void fieldsRoundTripThroughAccessors() {
        CommerceMandate m = new CommerceMandate();
        UUID id = UUID.randomUUID();
        UUID intent = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        Instant expiry = Instant.parse("2026-10-20T00:00:00Z");
        m.setId(id);
        m.setKind(CommerceMandateKind.CART);
        m.setIntentMandateId(intent);
        m.setAgentId(agent);
        m.setBuyerUserId("buyer-1");
        m.setCurrency("INR");
        m.setCartTotalMinor(25_000L);
        m.setItemsJson("[{\"sku\":\"KB-1\"}]");
        m.setPayee("merchant-1");
        m.setCategories("[\"books\"]");
        m.setIntentText("buy a keyboard");
        m.setStatus(CommerceMandateStatus.PENDING_AUTHORIZATION);
        m.setCanonicalJson("{}");
        m.setSignature(new byte[] {1});
        m.setKeyId("k1");
        m.setExpiresAt(expiry);

        assertEquals(id, m.getId());
        assertEquals(CommerceMandateKind.CART, m.getKind());
        assertEquals(intent, m.getIntentMandateId());
        assertEquals(agent, m.getAgentId());
        assertEquals("buyer-1", m.getBuyerUserId());
        assertEquals("INR", m.getCurrency());
        assertEquals(25_000L, m.getCartTotalMinor());
        assertEquals("[{\"sku\":\"KB-1\"}]", m.getItemsJson());
        assertEquals("merchant-1", m.getPayee());
        assertEquals("[\"books\"]", m.getCategories());
        assertEquals("buy a keyboard", m.getIntentText());
        assertEquals(CommerceMandateStatus.PENDING_AUTHORIZATION, m.getStatus());
        assertEquals("{}", m.getCanonicalJson());
        assertEquals(1, m.getSignature().length);
        assertEquals("k1", m.getKeyId());
        assertEquals(expiry, m.getExpiresAt());
        assertNull(m.getBuyerAuthJson());
        assertNull(m.getBuyerSignature());
        assertNull(m.getAuthorizedAt());
        assertNull(m.getWalletDebitRef());
    }

    @Test
    void applyBuyerAuthorizationAttachesTheSignedDocumentAndMovesToAuthorized() {
        CommerceMandate m = new CommerceMandate();
        Instant at = Instant.parse("2026-10-05T12:00:00Z");
        byte[] sig = new byte[] {7, 7};
        m.applyBuyerAuthorization("{\"cart_hash\":\"ab\"}", sig, at);
        assertEquals(CommerceMandateStatus.AUTHORIZED, m.getStatus());
        assertEquals("{\"cart_hash\":\"ab\"}", m.getBuyerAuthJson());
        assertEquals(sig, m.getBuyerSignature());
        assertEquals(at, m.getAuthorizedAt());
        assertEquals(at, m.getUpdatedAt());
    }

    @Test
    void theTerminalMarksStampTheirFields() {
        CommerceMandate m = new CommerceMandate();
        Instant at = Instant.parse("2026-10-05T12:00:00Z");
        m.markCaptured(42L, at);
        assertEquals(CommerceMandateStatus.CAPTURED, m.getStatus());
        assertEquals(42L, m.getWalletDebitRef());
        assertEquals(at, m.getUpdatedAt());

        m.markRefunded(at);
        assertEquals(CommerceMandateStatus.REFUNDED, m.getStatus());

        CommerceMandate failed = new CommerceMandate();
        failed.markFailed(at);
        assertEquals(CommerceMandateStatus.FAILED, failed.getStatus());

        CommerceMandate revoked = new CommerceMandate();
        revoked.markRevoked("ops-1", "fraud review", at);
        assertEquals(CommerceMandateStatus.REVOKED, revoked.getStatus());
        assertEquals(at, revoked.getRevokedAt());
        assertEquals("ops-1", revoked.getRevokedBy());
        assertEquals("fraud review", revoked.getRevokeReason());
    }

    @Test
    void theExceptionMapsItsStableCodesToHttpStatuses() {
        assertEquals(404, new CommerceMandateException("NOT_FOUND", "x").httpStatus());
        assertEquals(409, new CommerceMandateException("WRONG_STATE", "x").httpStatus());
        assertEquals(409, new CommerceMandateException("OVER_CEILING", "x").httpStatus());
        assertEquals(409, new CommerceMandateException("INTENT_INACTIVE", "x").httpStatus());
        assertEquals(409, new CommerceMandateException("INTENT_EXPIRED", "x").httpStatus());
        assertEquals(409, new CommerceMandateException("CART_EXPIRED", "x").httpStatus());
        assertEquals(409, new CommerceMandateException("CAPTURE_FAILED", "x").httpStatus());
        assertEquals(400, new CommerceMandateException("SIGNATURE_INVALID", "x").httpStatus());
        assertEquals(400, new CommerceMandateException("SOME_FUTURE_CODE", "x").httpStatus(),
            "an unknown code degrades to 400");
        CommerceMandateException e = new CommerceMandateException("OVER_CEILING", "cart over ceiling");
        assertEquals("OVER_CEILING", e.getCode());
        assertEquals("OVER_CEILING", e.errorCode());
        assertTrue(e.getMessage().contains("cart over ceiling"));
    }
}
