package com.agenticai.core.tenancy;

/**
 * Disposable-email blocklist at signup (PRICING-MONETIZATION-AUDIT-2026-07-31.md
 * §7 item 1 / approval item E1) - thrown by {@code TenantProvisioningService}
 * when the signup email's domain is on {@link DisposableEmailDomainService}'s
 * blocklist.
 *
 * <p>Extends {@link IllegalArgumentException} DELIBERATELY: {@code
 * GlobalExceptionHandler}'s existing {@code IllegalArgumentException} handler
 * maps it to a clean 400 with this message as the body's {@code error} field -
 * no new handler needed in core/app (outside this module's scope, and a
 * cross-agent collision point this session). Same mapping route {@code
 * CompGrantService}'s FREE-plan rejection already relies on. The named subclass
 * exists so callers/tests can distinguish this rejection and so a dedicated
 * handler can be added later without touching call sites.
 */
public class DisposableEmailException extends IllegalArgumentException {

    public DisposableEmailException(String message) {
        super(message);
    }
}
