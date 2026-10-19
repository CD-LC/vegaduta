"use client";

import { useCallback, useEffect, useState } from "react";
import { useRegion } from "../useRegion";
import { isCurrency, resolveCurrency, type Currency } from "./pricing";
import { loadUpgradeOptions, type UpgradeOptions } from "./upgradeOptions";

const STORAGE_KEY = "vd.currency";

function readOverride(): Currency | null {
  try {
    const v = window.localStorage.getItem(STORAGE_KEY);
    return isCurrency(v) ? v : null;
  } catch {
    return null; // private mode / blocked storage: just no remembered choice
  }
}

/**
 * The currency prices are shown (and charged) in. Detected from the visitor's region (server-resolved country,
 * with a browser heuristic fallback - see useRegion), but locale detection is a guess, so the customer can always
 * override it with the currency switch; the choice is remembered in this browser. Only currencies core reports as
 * billable are ever selected.
 */
export function useCurrency() {
  const { isIndia } = useRegion();
  const detected: Currency = isIndia ? "INR" : "USD";
  const [override, setOverride] = useState<Currency | null>(null);
  const [options, setOptions] = useState<UpgradeOptions | null>(null);
  const [optionsLoaded, setOptionsLoaded] = useState(false);

  useEffect(() => {
    setOverride(readOverride());
    let alive = true;
    void loadUpgradeOptions().then((o) => {
      if (!alive) return;
      setOptions(o);
      setOptionsLoaded(true);
    });
    return () => {
      alive = false;
    };
  }, []);

  // Core answered: trust it. Core did not answer (an older core without the options endpoint): only INR is safe to
  // offer, because a USD pick that has no plan behind it fails at checkout with a generic server error.
  const available: Currency[] | null =
    options && options.currencies.length > 0 ? options.currencies : optionsLoaded && !options ? ["INR"] : null;
  const currency = resolveCurrency(detected, override, available);

  const choose = useCallback((next: Currency) => {
    setOverride(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* the choice still applies for this session */
    }
  }, []);

  const reset = useCallback(() => {
    setOverride(null);
    try {
      window.localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* ignore */
    }
  }, []);

  return {
    currency,
    detected,
    overridden: override !== null && override !== detected,
    /** Currencies the customer may pick between. Still loading = both; no answer from core = INR only. */
    choices: (available ?? (["INR", "USD"] as Currency[])) as Currency[],
    /** Whether a yearly price card is real for this currency (a dedicated yearly plan exists). Unknown = yes. */
    annualAvailable: options && optionsLoaded ? options.annualCurrencies.includes(currency) || options.currencies.length === 0 : !optionsLoaded,
    billingEnabled: options?.billingEnabled ?? true,
    choose,
    reset,
  };
}
