package com.agenticai.core.payments.commerce;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Canonical JSON for {@link CommerceMandate} signatures - the same contract as {@code
 * MandateCanonicalizer}: deterministic by construction (fixed insertion order into Jackson {@link
 * ObjectNode}s, compact serialization), and the stored {@code canonical_json} is verified verbatim
 * at execution time rather than re-derived.
 *
 * <p>Two document shapes, both versioned:
 *
 * <ul>
 *   <li>{@code vegaduta-commerce-mandate/1} - the mandate itself (kind, agent, buyer, amounts,
 *       items/payee or ceiling/categories, validity).
 *   <li>{@code vegaduta-buyer-auth/1} - the buyer's authorization of one cart, bound to the cart's
 *       canonical hash so the signature cannot be transplanted onto a different cart.
 * </ul>
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class CommerceMandateCanonicalizer {

    public static final String MANDATE_VERSION = "vegaduta-commerce-mandate/1";
    public static final String BUYER_AUTH_VERSION = "vegaduta-buyer-auth/1";

    private final ObjectMapper objectMapper = JsonMappers.plain();

    /** Canonical mandate document (both kinds). */
    public String canonicalizeMandate(
            UUID mandateId,
            CommerceMandateKind kind,
            UUID intentMandateId,
            String tenantId,
            UUID agentId,
            String buyerUserId,
            String currency,
            Long maxTotalMinor,
            Long cartTotalMinor,
            String itemsJson,
            String payee,
            List<String> categories,
            String intentText,
            Instant expiresAt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", MANDATE_VERSION);
        ObjectNode m = root.putObject("mandate");
        m.put("mandate_id", mandateId.toString());
        m.put("kind", kind.name());
        if (intentMandateId != null) {
            m.put("intent_mandate_id", intentMandateId.toString());
        }
        m.put("tenant_id", tenantId);
        m.put("agent_id", agentId.toString());
        m.put("buyer", buyerUserId);
        m.put("currency", currency);
        if (kind == CommerceMandateKind.INTENT) {
            m.put("max_total_minor", maxTotalMinor);
            ArrayNode cats = m.putArray("categories");
            if (categories != null) {
                categories.stream().sorted().forEach(cats::add);
            }
            if (intentText != null) {
                m.put("intent_text", intentText);
            }
        } else {
            m.put("cart_total_minor", cartTotalMinor);
            m.put("items_hash", canonicalHash(itemsJson));
            if (payee != null) {
                m.put("payee", payee);
            }
        }
        m.put("expires_at", expiresAt.toString());
        return root.toString();
    }

    /**
     * The buyer-authorization sub-document a CART mandate carries: proof the buyer signed THIS cart
     * (its canonical hash) at THIS instant.
     */
    public String canonicalizeBuyerAuth(
            UUID cartMandateId,
            UUID intentMandateId,
            String cartCanonicalHash,
            String buyerUserId,
            long cartTotalMinor,
            String currency,
            Instant authorizedAt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", BUYER_AUTH_VERSION);
        ObjectNode auth = root.putObject("authorization");
        auth.put("cart_mandate_id", cartMandateId.toString());
        auth.put("intent_mandate_id", intentMandateId.toString());
        auth.put("cart_hash", cartCanonicalHash);
        auth.put("buyer", buyerUserId);
        auth.put("total_minor", cartTotalMinor);
        auth.put("currency", currency);
        auth.put("authorized_at", authorizedAt.toString());
        return root.toString();
    }

    /** SHA-256 hex - cart items hash inside the mandate, and the review hash shown to signers. */
    public String canonicalHash(String json) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
