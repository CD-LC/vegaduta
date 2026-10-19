package com.agenticai.core.payments;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the canonical JSON a {@link PaymentMandate}'s Ed25519 signature
 * covers (Build Specs §E1). Deterministic by construction: fields are
 * appended to Jackson {@link ObjectNode}s in a fixed order (ObjectNode
 * preserves insertion order) and serialized compactly - the stored
 * {@code canonical_json} column holds these exact bytes, so verification at
 * execution time never re-canonicalizes.
 *
 * <p>The envelope deliberately mirrors an AP2/UAP mandate shape
 * ({@code version} + {@code mandate} body: subject, scope, limits,
 * payee allow-list, approval rule, validity window) so a future
 * {@code NpciUapAdapter} can carry the same document without a migration -
 * the signing story is identical either way.
 */
@Component
public class MandateCanonicalizer {

    public static final String MANDATE_VERSION = "vegaduta-upi-mandate/1";

    private final ObjectMapper objectMapper = JsonMappers.plain();

    /**
     * @return the canonical JSON string that gets signed verbatim
     */
    public String canonicalize(
        UUID mandateId,
        String tenantId,
        UUID agentId,
        String grantedByUserId,
        MandateScope scope,
        long maxPerTxnPaise,
        long maxDailyPaise,
        long maxMonthlyPaise,
        List<String> allowedVpas,
        List<String> allowedMerchantCategories,
        MandateApprovalPolicy approvalPolicy,
        Long approvalThresholdPaise,
        Instant validFrom,
        Instant validUntil
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", MANDATE_VERSION);
        ObjectNode mandate = root.putObject("mandate");
        mandate.put("mandate_id", mandateId.toString());
        mandate.put("tenant_id", tenantId);
        mandate.put("agent_id", agentId.toString());
        mandate.put("granted_by", grantedByUserId);
        mandate.put("scope", scope.name());
        ObjectNode limits = mandate.putObject("limits");
        limits.put("max_per_txn_paise", maxPerTxnPaise);
        limits.put("max_daily_paise", maxDailyPaise);
        limits.put("max_monthly_paise", maxMonthlyPaise);
        ArrayNode vpas = mandate.putArray("allowed_vpas");
        if (allowedVpas != null) {
            allowedVpas.stream().sorted().forEach(vpas::add);
        }
        ArrayNode categories = mandate.putArray("allowed_merchant_categories");
        if (allowedMerchantCategories != null) {
            allowedMerchantCategories.stream().sorted().forEach(categories::add);
        }
        ObjectNode approval = mandate.putObject("approval");
        approval.put("policy", approvalPolicy.name());
        if (approvalPolicy == MandateApprovalPolicy.ABOVE_THRESHOLD && approvalThresholdPaise != null) {
            approval.put("threshold_paise", approvalThresholdPaise);
        }
        ObjectNode validity = mandate.putObject("validity");
        validity.put("from", validFrom.toString());
        validity.put("until", validUntil.toString());
        return root.toString();
    }

    /** SHA-256 hex of the canonical document - the "review-and-sign" hash the grant wizard displays. */
    public String canonicalHash(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
