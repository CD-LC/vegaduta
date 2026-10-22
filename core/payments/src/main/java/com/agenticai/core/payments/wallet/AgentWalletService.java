package com.agenticai.core.payments.wallet;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.payments.wallet.AgentWallet.Status;
import com.agenticai.core.payments.wallet.WalletLedgerEntry.EntryKind;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The agent wallet: a spendable, ledger-backed balance per (agent, currency)
 * - the abstraction agentic commerce builds on. Cart-mandate capture and
 * x402 settlement are both just {@code debit}/{@code credit} pairs with a
 * caller-supplied idempotency key.
 *
 * <p><b>Correctness contract:</b>
 * <ul>
 *   <li>Every movement runs inside one transaction that holds {@code SELECT
 *       ... FOR UPDATE} on the wallet row - concurrent debits serialize, and
 *       {@code balance_after} on each ledger row is the balance observed
 *       under that lock.</li>
 *   <li>{@code idempotency_key} is UNIQUE and checked BEFORE the lock is
 *       taken: a retry folds onto the original movement and returns its
 *       recorded result rather than double-spending.</li>
 *   <li>Debit never drives the balance negative - {@code INSUFFICIENT_FUNDS}
 *       inside the lock, before any write.</li>
 *   <li>Only ACTIVE wallets move money; FROZEN/CLOSED reject movements but
 *       keep their readable history.</li>
 * </ul>
 *
 * <p>Funding is operator-driven ({@link #credit} from the tenant console);
 * spending is agent-driven via a delegation token (x402) or a captured cart
 * mandate. There is deliberately no free-standing debit endpoint - see
 * {@code WalletController}'s javadoc.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class AgentWalletService {

    private static final Logger log = LoggerFactory.getLogger(AgentWalletService.class);

    private final AgentWalletRepository walletRepository;
    private final WalletLedgerRepository ledgerRepository;
    private final ObjectProvider<AuditLogService> auditLogService;

    public AgentWalletService(
        AgentWalletRepository walletRepository,
        WalletLedgerRepository ledgerRepository,
        ObjectProvider<AuditLogService> auditLogService
    ) {
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * The wallet for (agent, currency), creating it on first use.
     * Concurrent creation collapses onto {@code uq_agent_wallet_agent_currency}
     * - the loser of the race re-reads and returns the winner's row.
     */
    @Transactional
    public AgentWallet getOrCreate(UUID agentId, String currency) {
        String normalized = normalizeCurrency(currency);
        return walletRepository.findByAgentIdAndCurrency(agentId, normalized)
            .orElseGet(() -> {
                AgentWallet wallet = new AgentWallet();
                wallet.setId(UUID.randomUUID());
                wallet.setAgentId(agentId);
                wallet.setCurrency(normalized);
                wallet.setCreatedBy(currentSubject());
                try {
                    return walletRepository.save(wallet);
                } catch (DataIntegrityViolationException race) {
                    return walletRepository.findByAgentIdAndCurrency(agentId, normalized)
                        .orElseThrow(() -> race);
                }
            });
    }

    /**
     * Credit funds in (operator top-up, incoming settlement). Idempotent on
     * {@code idempotencyKey}.
     */
    @Transactional
    public WalletLedgerEntry credit(
        UUID walletId, long amountMinor, String idempotencyKey,
        String referenceType, String referenceId, String note
    ) {
        return move(walletId, EntryKind.CREDIT, amountMinor, idempotencyKey,
            referenceType, referenceId, note);
    }

    /**
     * Spend funds. Idempotent on {@code idempotencyKey}; throws {@link
     * WalletException} {@code INSUFFICIENT_FUNDS} (inside the lock) when the
     * balance cannot cover the amount.
     */
    @Transactional
    public WalletLedgerEntry debit(
        UUID walletId, long amountMinor, String idempotencyKey,
        String referenceType, String referenceId, String note
    ) {
        return move(walletId, EntryKind.DEBIT, amountMinor, idempotencyKey,
            referenceType, referenceId, note);
    }

    private WalletLedgerEntry move(
        UUID walletId, EntryKind kind, long amountMinor, String idempotencyKey,
        String referenceType, String referenceId, String note
    ) {
        if (amountMinor <= 0) {
            throw new WalletException("AMOUNT_INVALID", "amount must be a positive minor-unit count");
        }
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > 160) {
            throw new WalletException("IDEMPOTENCY_KEY_INVALID",
                "a caller-supplied idempotency key (<=160 chars) is required");
        }
        Optional<WalletLedgerEntry> replay = ledgerRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        AgentWallet wallet = walletRepository.findByIdForUpdate(walletId)
            .orElseThrow(() -> new WalletException("WALLET_NOT_FOUND", "no such wallet"));
        if (wallet.getStatus() != Status.ACTIVE) {
            throw new WalletException("WALLET_NOT_ACTIVE", "wallet is " + wallet.getStatus());
        }
        long balanceAfter = kind == EntryKind.CREDIT
            ? Math.addExact(wallet.getBalanceMinor(), amountMinor)
            : Math.subtractExact(wallet.getBalanceMinor(), amountMinor);
        if (balanceAfter < 0) {
            throw new WalletException("INSUFFICIENT_FUNDS",
                "wallet balance " + wallet.getBalanceMinor() + " cannot cover " + amountMinor);
        }
        wallet.applyMovement(kind == EntryKind.CREDIT ? amountMinor : -amountMinor, Instant.now());
        WalletLedgerEntry entry = WalletLedgerEntry.of(
            walletId, kind, amountMinor, balanceAfter, idempotencyKey,
            referenceType, referenceId, note, currentSubject());
        WalletLedgerEntry saved = ledgerRepository.save(entry);
        audit("wallet." + kind.name().toLowerCase(Locale.ROOT), walletId.toString(),
            AuditOutcome.APPROVED,
            "amount=" + amountMinor + " balance_after=" + balanceAfter
                + " ref=" + referenceType + ":" + referenceId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<AgentWallet> findById(UUID walletId) {
        return walletRepository.findById(walletId);
    }

    @Transactional(readOnly = true)
    public List<AgentWallet> listForAgent(UUID agentId) {
        return walletRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
    }

    @Transactional(readOnly = true)
    public List<WalletLedgerEntry> ledger(UUID walletId, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return ledgerRepository.findByWalletIdOrderByIdDesc(walletId, PageRequest.of(0, capped));
    }

    /** Offset variant of {@link #ledger(UUID, int)}: newest first, skipping the newest {@code offset} rows. */
    @Transactional(readOnly = true)
    public List<WalletLedgerEntry> ledger(UUID walletId, int limit, long offset) {
        int capped = Math.max(1, Math.min(limit, 200));
        return ledgerRepository.findByWalletIdOrderByIdDesc(
            walletId, new com.agenticai.core.tenancy.OffsetLimitRequest(offset, capped));
    }

    @Transactional(readOnly = true)
    public long ledgerCount(UUID walletId) {
        return ledgerRepository.countByWalletId(walletId);
    }

    private String currentSubject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("system");
    }

    private static String normalizeCurrency(String currency) {
        if (!StringUtils.hasText(currency) || currency.trim().length() != 3) {
            throw new WalletException("CURRENCY_INVALID", "currency must be a 3-letter code");
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private void audit(String action, String targetId, AuditOutcome outcome, String detail) {
        try {
            AuditLogService audit = auditLogService.getIfAvailable();
            if (audit != null) {
                audit.record(currentSubject(), action, "agent_wallet", targetId, outcome, detail);
            }
        } catch (RuntimeException e) {
            log.warn("Audit write failed for {}: {}", action, e.toString());
        }
    }
}
