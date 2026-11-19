package com.agenticai.core.payments;

import com.agenticai.core.payments.PaymentDtos.MandateResponse;
import com.agenticai.core.payments.PaymentDtos.TransactionResponse;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.OffsetLimitRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service facade for {@link PaymentController}: wraps {@link PaymentMandateService} and the
 * transaction repository and maps entities to response DTOs, so the controller never touches a
 * repository or a JPA entity. Owns the transaction boundary for the API reads and writes.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class PaymentApiService {

    private final PaymentMandateService mandateService;
    private final PaymentTransactionRepository transactionRepository;
    private final MandateCanonicalizer canonicalizer;

    public PaymentApiService(
            PaymentMandateService mandateService,
            PaymentTransactionRepository transactionRepository,
            MandateCanonicalizer canonicalizer) {
        this.mandateService = mandateService;
        this.transactionRepository = transactionRepository;
        this.canonicalizer = canonicalizer;
    }

    @Transactional
    public MandateResponse grant(PaymentMandateService.MandateDraft draft) {
        return PaymentDtos.toMandateResponse(mandateService.grant(draft), canonicalizer);
    }

    @Transactional(readOnly = true)
    public List<MandateResponse> listForAgent(UUID agentId) {
        return mandateService.listForAgent(agentId).stream()
                .map(m -> PaymentDtos.toMandateResponse(m, canonicalizer))
                .toList();
    }

    /** Empty when absent (or in another tenant schema). */
    @Transactional(readOnly = true)
    public Optional<MandateResponse> findMandate(UUID id) {
        return Optional.ofNullable(mandateService.findById(id))
                .map(m -> PaymentDtos.toMandateResponse(m, canonicalizer));
    }

    /** Empty when no such mandate. */
    @Transactional
    public Optional<MandateResponse> revoke(UUID id, String reason) {
        return Optional.ofNullable(mandateService.revoke(id, reason))
                .map(m -> PaymentDtos.toMandateResponse(m, canonicalizer));
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> transactions(
            UUID agentId, UUID mandateId, OffsetLimitRequest page) {
        List<PaymentTransaction> rows =
                mandateId != null
                        ? transactionRepository.findByMandateIdOrderByCreatedAtDescIdDesc(
                                mandateId, page)
                        : transactionRepository.findByAgentIdOrderByCreatedAtDescIdDesc(
                                agentId, page);
        return rows.stream().map(PaymentDtos::toTransactionResponse).toList();
    }

    @Transactional(readOnly = true)
    public long countTransactions(UUID agentId, UUID mandateId) {
        return mandateId != null
                ? transactionRepository.countByMandateId(mandateId)
                : transactionRepository.countByAgentId(agentId);
    }

    @Transactional(readOnly = true)
    public Optional<TransactionResponse> findTransaction(UUID id) {
        return transactionRepository.findById(id).map(PaymentDtos::toTransactionResponse);
    }
}
