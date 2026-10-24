package com.agenticai.core.billing.gateway;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The class every multi-gateway-aware caller ({@link
 * com.agenticai.core.tenancy.TenantUpgradeService}, {@link BillingWebhookController}) depends on
 * instead of {@link PaymentGatewayClient} directly, precisely BECAUSE that interface stopped being
 * safely injectable the moment a second implementation ({@link PhonePeGatewayClient}) existed
 * alongside {@link RazorpayGatewayClient} - Spring can no longer autowire "the" client.
 *
 * <p>Auto-discovers every {@link PaymentGatewayClient} bean present in the context (razorpay
 * always; phonepe only under {@code @Profile("security")}) and indexes them by {@link
 * PaymentGatewayClient#gatewayName()}.
 *
 * <p>{@code null}/blank resolves to {@code app.billing.default-gateway} ({@code razorpay}) - this
 * preserves 100% of existing behavior for every caller that predates multi-gateway support, e.g.
 * {@code TenantUpgradeService#initiateUpgrade(TenantPlan)}'s single-arg overload. An explicitly
 * named but unregistered gateway throws rather than silently falling back to the default - a bug
 * here (wrong default, a name collision silently picking the wrong gateway) would misroute real
 * money.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class PaymentGatewayRegistry {

    private final Map<String, PaymentGatewayClient> clientsByName;
    private final String defaultGatewayName;

    public PaymentGatewayRegistry(
            List<PaymentGatewayClient> clients,
            @Value("${app.billing.default-gateway:razorpay}") String defaultGatewayName) {
        Map<String, PaymentGatewayClient> byName = new LinkedHashMap<>();
        for (PaymentGatewayClient client : clients) {
            byName.put(client.gatewayName(), client);
        }
        this.clientsByName = byName;
        this.defaultGatewayName = defaultGatewayName;
    }

    /**
     * Resolve a client by gateway name. {@code null}/blank resolves to the configured default
     * ({@code app.billing.default-gateway}). Throws {@link IllegalArgumentException} for an
     * unregistered name - never silently falls back, since that could misroute a real charge.
     */
    public PaymentGatewayClient resolve(String gatewayName) {
        String name =
                (gatewayName == null || gatewayName.isBlank()) ? defaultGatewayName : gatewayName;
        PaymentGatewayClient client = clientsByName.get(name);
        if (client == null) {
            throw new IllegalArgumentException("No payment gateway client registered for: " + name);
        }
        return client;
    }

    /** The configured default gateway name ({@code app.billing.default-gateway}). */
    public String defaultGatewayName() {
        return defaultGatewayName;
    }

    /**
     * A browser-safe description of every registered gateway - name, whether it is configured here,
     * whether it is the default, its public key, its checkout mode and what it can do. Contains no
     * secret. Served by {@code GET /api/billing/gateways} so the console renders the right checkout
     * from data, not from a gateway name.
     */
    public List<GatewayInfo> describe() {
        List<GatewayInfo> out = new java.util.ArrayList<>();
        for (PaymentGatewayClient client : clientsByName.values()) {
            String key = client.publicKeyId();
            out.add(
                    new GatewayInfo(
                            client.gatewayName(),
                            client.isConfigured(),
                            client.gatewayName().equals(defaultGatewayName),
                            key == null ? "" : key,
                            client.checkoutMode(),
                            java.util.Set.copyOf(client.capabilities())));
        }
        return out;
    }

    /** Every registered gateway name - used by admin/diagnostic surfaces. */
    public Set<String> availableGatewayNames() {
        return clientsByName.keySet();
    }
}
