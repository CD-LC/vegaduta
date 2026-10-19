"use client";

import type { Currency } from "../../lib/payments/pricing";

const LABEL: Record<Currency, string> = { INR: "₹ INR", USD: "$ USD" };

/**
 * "Prices in ₹ / $" switch. Locale detection is only a guess (VPNs, travellers, a browser set to another country),
 * so the customer can always pick the other currency; the choice is remembered. Renders nothing when there is only
 * one billable currency - there is nothing to switch.
 */
export default function CurrencySwitch({
  currency,
  detected,
  choices,
  onChange,
  disabled = false,
}: {
  currency: Currency;
  detected: Currency;
  choices: readonly Currency[];
  onChange: (next: Currency) => void;
  disabled?: boolean;
}) {
  if (choices.length < 2) return null;
  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="currency-switch">
      <div className="inline-flex items-center rounded-full border border-border bg-secondary/40 p-0.5" role="group" aria-label="Currency">
        {choices.map((c) => (
          <button
            key={c}
            type="button"
            aria-pressed={currency === c}
            onClick={() => onChange(c)}
            disabled={disabled}
            className={`rounded-full border-0 px-3 py-1 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-50 ${
              currency === c ? "bg-primary text-primary-foreground" : "bg-transparent text-muted-foreground hover:text-foreground"
            }`}
          >
            {LABEL[c]}
          </button>
        ))}
      </div>
      <span className="text-xs text-muted-foreground">
        {currency === detected ? "Shown for your region — switch if this isn't right." : "You chose this currency."}
      </span>
    </div>
  );
}
