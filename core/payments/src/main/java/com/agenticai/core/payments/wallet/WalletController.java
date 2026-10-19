package com.agenticai.core.payments.wallet;

import com.agenticai.core.payments.wallet.WalletDtos.CreditRequest;
import com.agenticai.core.payments.wallet.WalletDtos.LedgerEntryResponse;
import com.agenticai.core.payments.wallet.WalletDtos.OpenWalletRequest;
import com.agenticai.core.payments.wallet.WalletDtos.WalletResponse;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-facing REST for agent wallets. Exposes wallet creation, top-up
 * credits, balance and ledger reads - the operator's side of funding an
 * agent.
 *
 * <p>There is deliberately <b>no debit endpoint</b>: spending is what a
 * delegation token (x402 settle) or a captured commerce mandate is FOR, and
 * those paths carry their own authorization. A tenant-authorized HTTP caller
 * reaching straight into debit would bypass the mandate/delegation layer's
 * caps and signatures entirely.
 */
@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final AgentWalletService walletService;

    public WalletController(AgentWalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> open(@Valid @RequestBody OpenWalletRequest body) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(WalletDtos.toResponse(walletService.getOrCreate(body.agentId(), body.currency())));
        } catch (WalletException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getCode() + ": " + e.getMessage());
        }
    }

    @GetMapping
    public List<WalletResponse> list(@RequestParam UUID agentId) {
        return walletService.listForAgent(agentId).stream()
            .map(WalletDtos::toResponse).toList();
    }

    @GetMapping("/{walletId}")
    public WalletResponse get(@PathVariable UUID walletId) {
        return WalletDtos.toResponse(walletService.findById(walletId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such wallet")));
    }

    /** Operator top-up - credits are how an agent wallet gets funded. */
    @PostMapping("/{walletId}/credit")
    public ResponseEntity<LedgerEntryResponse> credit(
        @PathVariable UUID walletId, @Valid @RequestBody CreditRequest body
    ) {
        try {
            WalletLedgerEntry entry = walletService.credit(
                walletId, body.amountMinor(), body.idempotencyKey(), "operator_topup", null, body.note());
            return ResponseEntity.status(HttpStatus.CREATED).body(WalletDtos.toResponse(entry));
        } catch (WalletException e) {
            HttpStatus status = "WALLET_NOT_FOUND".equals(e.getCode())
                ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
            throw new ResponseStatusException(status, e.getCode() + ": " + e.getMessage());
        }
    }

    /**
     * Newest first. {@code limit} 1..200 (default 50, the old fixed cap); {@code offset} skips the
     * newest rows. Carries {@code X-Total-Count} / {@code X-Has-More}; the body stays a plain array.
     */
    @GetMapping("/{walletId}/ledger")
    public ResponseEntity<List<LedgerEntryResponse>> ledger(
        @PathVariable UUID walletId,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(required = false) Long offset
    ) {
        walletService.findById(walletId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such wallet"));
        int capped = Math.max(1, Math.min(limit, 200));
        long skip = ListPaging.offset(offset);
        List<LedgerEntryResponse> rows = walletService.ledger(walletId, capped, skip).stream()
            .map(WalletDtos::toResponse).toList();
        return ListPaging.respond(rows, capped, skip, () -> walletService.ledgerCount(walletId));
    }
}
