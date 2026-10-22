package com.agenticai.core.payments;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@link ApprovalActionExecutor} for {@code upi.mandate.execute} - picked up by {@code
 * ApprovalActionExecutorRegistry} automatically, so {@code HumanApprovalGateService#approve} runs
 * the payment through the same single executor chokepoint every other gated action uses. The
 * payload (written by {@code PaymentToolService}) carries the transaction id; execution itself
 * re-verifies the mandate inside {@link PaymentExecutionService} - an approval does NOT bypass the
 * mandate check, it only unblocks it (a mandate revoked while pending still fails at execution).
 *
 * <p>{@link #onRejected} settles the parked transaction FAILED and releases its cap reservation -
 * the pending transaction's CHARGE otherwise holds daily/monthly cap forever.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class PaymentMandateApprovalExecutor implements ApprovalActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(PaymentMandateApprovalExecutor.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final PaymentExecutionService executionService;

    public PaymentMandateApprovalExecutor(PaymentExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public String actionType() {
        return PaymentToolService.APPROVAL_ACTION_TYPE;
    }

    @Override
    public void execute(ApprovalRequest request) {
        UUID transactionId = transactionId(request);
        if (transactionId == null) {
            throw new IllegalStateException(
                    "upi.mandate.execute request "
                            + request.getId()
                            + " carries no transactionId payload");
        }
        executionService.execute(transactionId);
    }

    @Override
    public void onRejected(ApprovalRequest request, String decidedBy, String reason) {
        UUID transactionId = transactionId(request);
        if (transactionId == null) {
            log.warn(
                    "Rejected upi.mandate.execute request {} carries no transactionId - nothing to settle",
                    request.getId());
            return;
        }
        executionService.settleRejected(transactionId, decidedBy, reason);
    }

    private static UUID transactionId(ApprovalRequest request) {
        try {
            JsonNode node = MAPPER.readTree(request.getPayloadJson()).path("transactionId");
            if (node.isString()) {
                return UUID.fromString(node.asString());
            }
        } catch (Exception e) {
            log.warn(
                    "Could not parse transactionId from upi.mandate.execute request {}",
                    request.getId(),
                    e);
        }
        return null;
    }
}
