package com.agenticai.core.agents.tools.web;

import com.agenticai.core.agents.tools.x402.X402PaymentService;
import com.agenticai.core.secrets.SecretReferenceResolver;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * One x402 payment attempt against a 402 response, isolated from the {@link HttpRequestExecutor}
 * pipeline. Reads the body for the {@code accepts} offer, or falls back to the base64 {@code
 * PAYMENT-REQUIRED} header. The payer key is ONLY the tenant's own vault secret {@code
 * X402_WALLET_PRIVATE_KEY} - there is no platform-wallet fallback (SEC-029). The resolved key is
 * added to the caller's redaction set so it can never be echoed back to the model. Callers must not
 * invoke this for untrusted (share / webhook / inbound) subjects.
 */
final class X402PaymentAttempt {

    private static final Logger log = LoggerFactory.getLogger(X402PaymentAttempt.class);

    private final ObjectProvider<X402PaymentService> x402Payments;
    private final ObjectProvider<SecretReferenceResolver> secretReferenceResolver;

    X402PaymentAttempt(
            ObjectProvider<X402PaymentService> x402Payments,
            ObjectProvider<SecretReferenceResolver> secretReferenceResolver) {
        this.x402Payments = x402Payments;
        this.secretReferenceResolver = secretReferenceResolver;
    }

    /**
     * @return the {@code X-PAYMENT} header value, or null when payment is
     *     disabled/unaffordable/unsupported/refused
     */
    String tryBuildPaymentHeader(
            HttpRequestExecutor.RawResponse response,
            String userId,
            String tenantId,
            Set<String> resolvedValues) {
        X402PaymentService x402 = x402Payments.getIfAvailable();
        if (x402 == null || !x402.isEnabled() || tenantId == null || tenantId.isBlank()) {
            return null;
        }
        try {
            String body = new String(response.body(), StandardCharsets.UTF_8);
            var accepts =
                    x402.parseAccepts(
                            body.isBlank() ? response.header("PAYMENT-REQUIRED").orElse("") : body);
            if (accepts.isEmpty()) {
                return null;
            }
            String tenantWallet = resolveTenantWallet(userId, resolvedValues);
            if (tenantWallet == null) {
                log.debug(
                        "x402: tenant has no X402_WALLET_PRIVATE_KEY - 402 left unpaid (no platform fallback)");
                return null;
            }
            return x402.buildPaymentHeader(accepts.get(), tenantWallet, tenantId).orElse(null);
        } catch (RuntimeException e) {
            log.debug("x402 payment attempt failed - serving the 402 as-is", e);
            return null;
        }
    }

    /** The tenant's own payer key from the vault - null when absent/unresolvable. */
    private String resolveTenantWallet(String userId, Set<String> resolvedValues) {
        SecretReferenceResolver resolver = secretReferenceResolver.getIfAvailable();
        if (resolver == null) {
            return null;
        }
        try {
            SecretReferenceResolver.Resolution resolution =
                    resolver.resolve("{{secret.X402_WALLET_PRIVATE_KEY}}", userId);
            resolvedValues.addAll(resolution.resolvedValues());
            return resolution.unresolvedNames().isEmpty()
                            && !resolution.value().isBlank()
                            && !resolution.value().contains("{{")
                    ? resolution.value().trim()
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
