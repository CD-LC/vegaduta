import { api } from "../api";
import { auth } from "../auth";
import type { Currency } from "./pricing";
import { isCurrency } from "./pricing";

/** What core says is billable here (GET /api/tenant/upgrade/options). */
export interface UpgradeOptions {
  billingEnabled: boolean;
  gateway: string;
  currencies: Currency[];
  annualCurrencies: Currency[];
}

// One lookup per page load, shared by every dialog/page that shows prices. null = could not be loaded (older core
// without the endpoint, signed out ...): callers then fall back to showing everything, exactly as before.
let optionsPromise: Promise<UpgradeOptions | null> | null = null;

export function loadUpgradeOptions(): Promise<UpgradeOptions | null> {
  // The endpoint is authenticated. Calling it signed-out (the public /pricing page) only produced a 401 plus a failed
  // forced token refresh in the console on every visit (prod e2e, 2026-10-09). Skip it, and do NOT cache the skip, so a
  // later signed-in caller on the same page load still asks core.
  if (optionsPromise === null && !auth.isAuthenticated()) {
    return Promise.resolve(null);
  }
  if (optionsPromise === null) {
    optionsPromise = api.getUpgradeOptions().then(
      (raw: any): UpgradeOptions => ({
        billingEnabled: raw?.billingEnabled !== false,
        gateway: String(raw?.gateway ?? ""),
        currencies: Array.isArray(raw?.currencies) ? raw.currencies.filter(isCurrency) : [],
        annualCurrencies: Array.isArray(raw?.annualCurrencies) ? raw.annualCurrencies.filter(isCurrency) : [],
      }),
      (): UpgradeOptions | null => null,
    );
  }
  return optionsPromise;
}
