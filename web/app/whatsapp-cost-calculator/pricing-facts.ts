// What was verified against Meta's own pages, and when (docs/channels/whatsapp-policy.md section 3).
// Update this file, not the page copy, when pricing is re-checked.
//
// RATES_VERIFIED is false because Meta's pricing page text publishes no numbers: the per-message
// rates live in an interactive widget and in per-currency rate-card files that could not be
// retrieved. Until someone reads them off Meta's rate card, the calculator shows no default rates.
export const PRICING_CHECKED_ON = "2026-10-14";
export const RATES_VERIFIED = false;

export const META_RATE_CARD_URL = "https://whatsappbusiness.com/products/platform-pricing/";
export const META_PRICING_DOCS_URL = "https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing";

/** Parses a rate field; null when empty, not a number, or not positive. */
export function parseRate(value: string): number | null {
  const n = Number(value);
  return value.trim() !== "" && Number.isFinite(n) && n > 0 ? n : null;
}
