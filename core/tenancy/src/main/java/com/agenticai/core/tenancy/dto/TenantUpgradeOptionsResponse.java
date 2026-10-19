package com.agenticai.core.tenancy.dto;

import java.util.List;

/**
 * What the upgrade dialog may offer on THIS deployment, so it never lets a customer pick a currency or period that
 * cannot be billed (e.g. USD before the gateway account has international plans).
 *
 * @param billingEnabled false while free mode is on (upgrades are intentionally closed)
 * @param gateway the gateway these options describe (the default when none was asked for)
 * @param currencies currencies in which both paid tiers can be billed monthly ("INR", "USD"); empty = nothing wired
 * @param annualCurrencies currencies that also have a dedicated yearly plan for both tiers
 */
public record TenantUpgradeOptionsResponse(
    boolean billingEnabled,
    String gateway,
    List<String> currencies,
    List<String> annualCurrencies
) {
}
