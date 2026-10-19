// The ONE price table the console shows (upgrade dialog, billing page). Figures are deliberate regional price
// points (India PPP), not FX conversions, and annual = 10x monthly ("2 months free"). They must match the plans
// created in the gateway account - scripts/billing/create-razorpay-plans.sh carries the same numbers in minor units.

export type Currency = "INR" | "USD";
export type Period = "MONTHLY" | "ANNUAL";
export type PaidTier = "PREMIUM" | "UNLIMITED";

export const CURRENCIES: readonly Currency[] = ["INR", "USD"];

/** Major units (rupees / dollars). */
export const PRICE_TABLE: Record<PaidTier, Record<Currency, Record<Period, number>>> = {
  PREMIUM: {
    INR: { MONTHLY: 399, ANNUAL: 3999 },
    USD: { MONTHLY: 19, ANNUAL: 190 },
  },
  UNLIMITED: {
    INR: { MONTHLY: 1599, ANNUAL: 15999 },
    USD: { MONTHLY: 79, ANNUAL: 790 },
  },
};

export function formatMoney(amount: number, currency: Currency): string {
  // en-IN gives rupee digit grouping (₹15,999 either way, but ₹1,00,000 at scale); en-US for dollars.
  return new Intl.NumberFormat(currency === "INR" ? "en-IN" : "en-US", {
    style: "currency",
    currency,
    maximumFractionDigits: 0,
  }).format(amount);
}

/** "₹399/mo", "$190/yr". */
export function planPrice(tier: PaidTier, currency: Currency, period: Period): string {
  return `${formatMoney(PRICE_TABLE[tier][currency][period], currency)}${period === "ANNUAL" ? "/yr" : "/mo"}`;
}

export function isCurrency(value: unknown): value is Currency {
  return value === "INR" || value === "USD";
}

/**
 * Which currency to show. A customer's explicit choice wins; otherwise what we detected from their region; and
 * whichever is picked must be BILLABLE on this deployment (`available` = the currencies core says have real plans),
 * else the next candidate is used. `available` null = unknown (older core / not loaded yet): trust the first pick.
 */
export function resolveCurrency(
  detected: Currency,
  override: Currency | null,
  available: readonly Currency[] | null,
): Currency {
  const candidates: Currency[] = [];
  if (override) candidates.push(override);
  candidates.push(detected);
  for (const c of CURRENCIES) if (!candidates.includes(c)) candidates.push(c);
  if (available === null) return candidates[0];
  if (available.length === 0) return override ?? detected;
  return candidates.find((c) => available.includes(c)) ?? candidates[0];
}
