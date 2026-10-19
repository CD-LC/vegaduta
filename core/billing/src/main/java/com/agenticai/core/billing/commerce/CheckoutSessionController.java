package com.agenticai.core.billing.commerce;

import com.agenticai.core.security.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Console read surface for agent-raised payment requests:
 * <ul>
 *   <li>{@code GET /api/tenant/checkout-sessions?limit=50} - this tenant's
 *       sessions, newest first</li>
 *   <li>{@code GET /api/tenant/checkout-sessions/{id}} - one session</li>
 * </ul>
 *
 * <p>Read-only on purpose: the only writer is the {@code
 * create_payment_link} agent tool (behind the approval gate), and the only
 * state-changer is the gateway webhook. An ACP-style POST
 * {@code /checkout_sessions} create/complete/cancel surface is roadmap -
 * see this package's {@code package-info}.
 *
 * <p>Tenant scoping mirrors {@code InvoiceController}: identity comes
 * exclusively from {@link TenantContext} (never a path/query param).
 * NOTE for SecurityConfig: no explicit matcher exists for these paths yet,
 * so they fall through to {@code anyRequest().authenticated()} - any
 * tenant member can read them. They carry customer phone numbers and
 * amounts, the same billing-sensitive tier as {@code /api/tenant/invoices},
 * so a {@code GET /api/tenant/checkout-sessions/**  -> ROLE_tenant-admin}
 * rule belongs beside the invoices line in {@code SecurityConfig}.
 */
@RestController
@Profile("security")
public class CheckoutSessionController {

    /** Wire shape - ACP-style lowercase status, amounts in both minor units and a display-only major value. */
    public record CheckoutSessionResponse(
        UUID id,
        UUID agentId,
        String sessionId,
        String channel,
        String customerPhone,
        String customerName,
        String currency,
        long amountPaise,
        BigDecimal amount,
        String description,
        String status,
        String paymentProvider,
        String gatewayRefId,
        String shortUrl,
        Instant paidAt,
        String paymentId,
        String reviewNote,
        Instant createdAt,
        Instant expiresAt
    ) {
        static CheckoutSessionResponse from(CheckoutSession s) {
            return new CheckoutSessionResponse(
                s.id(), s.agentId(), s.sessionId(), s.channel(), s.customerPhone(), s.customerName(),
                s.currency(), s.amountPaise(), s.amountMajor(), s.description(), s.status().wireValue(),
                s.paymentProvider(), s.gatewayRefId(), s.shortUrl(), s.paidAt(), s.paymentId(), s.reviewNote(),
                s.createdAt(), s.expiresAt()
            );
        }
    }

    private final CheckoutSessionService service;

    public CheckoutSessionController(CheckoutSessionService service) {
        this.service = service;
    }

    @GetMapping("/api/tenant/checkout-sessions")
    public List<CheckoutSessionResponse> list(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return service.listForTenant(currentTenantId(), limit).stream()
            .map(CheckoutSessionResponse::from)
            .toList();
    }

    @GetMapping("/api/tenant/checkout-sessions/{id}")
    public CheckoutSessionResponse get(@PathVariable("id") UUID id) {
        return service.getForTenant(id, currentTenantId())
            .map(CheckoutSessionResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such checkout session for this tenant"));
    }

    private static String currentTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "No tenant context - authentication required"));
    }
}
