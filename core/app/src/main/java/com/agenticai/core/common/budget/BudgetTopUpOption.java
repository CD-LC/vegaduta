package com.agenticai.core.common.budget;

import java.math.BigDecimal;

/**
 * One purchasable top-up amount (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4
 * P2). {@code amountInSubunits} is what the gateway is actually charged (minor
 * unit - cents/paise); {@code usdAmount} is the credited dollar figure; {@code
 * label} is a display-ready string.
 */
public record BudgetTopUpOption(BigDecimal usdAmount, long amountInSubunits, String label) {
}
