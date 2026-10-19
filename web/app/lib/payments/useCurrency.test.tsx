import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const region = { isIndia: false };
vi.mock("../useRegion", () => ({ useRegion: () => ({ region: region.isIndia ? "IN" : "INTL", isIndia: region.isIndia, ready: true }) }));

const options = { current: null as any };
vi.mock("./upgradeOptions", () => ({ loadUpgradeOptions: () => Promise.resolve(options.current) }));

import { useCurrency } from "./useCurrency";

// An in-memory localStorage: the test environment's own is not reliable, and the hook must not depend on it anyway.
const store = new Map<string, string>();
const memoryStorage = {
  getItem: (k: string) => (store.has(k) ? (store.get(k) as string) : null),
  setItem: (k: string, v: string): void => {
    store.set(k, String(v));
  },
  removeItem: (k: string): void => {
    store.delete(k);
  },
  clear: (): void => store.clear(),
};

describe("useCurrency", () => {
  beforeEach(() => {
    store.clear();
    Object.defineProperty(window, "localStorage", { value: memoryStorage, configurable: true });
    options.current = null;
    region.isIndia = false;
  });
  afterEach(() => store.clear());

  it("detects rupees for India and dollars elsewhere", async () => {
    region.isIndia = true;
    const india = renderHook(() => useCurrency());
    await waitFor(() => expect(india.result.current.currency).toBe("INR"));
    region.isIndia = false;
    const abroad = renderHook(() => useCurrency());
    await waitFor(() => expect(abroad.result.current.currency).toBe("USD"));
  });

  it("lets the customer override a wrong detection and remembers it", async () => {
    region.isIndia = true;
    const { result, unmount } = renderHook(() => useCurrency());
    act(() => result.current.choose("USD"));
    expect(result.current.currency).toBe("USD");
    expect(result.current.overridden).toBe(true);
    expect(window.localStorage.getItem("vd.currency")).toBe("USD");
    unmount();
    const again = renderHook(() => useCurrency());
    await waitFor(() => expect(again.result.current.currency).toBe("USD"));
  });

  it("can go back to the detected currency", async () => {
    region.isIndia = true;
    const { result } = renderHook(() => useCurrency());
    act(() => result.current.choose("USD"));
    act(() => result.current.reset());
    expect(result.current.currency).toBe("INR");
    expect(window.localStorage.getItem("vd.currency")).toBeNull();
  });

  it("only offers and selects currencies the deployment can bill", async () => {
    options.current = { billingEnabled: true, gateway: "razorpay", currencies: ["INR"], annualCurrencies: ["INR"] };
    const { result } = renderHook(() => useCurrency());
    await waitFor(() => expect(result.current.choices).toEqual(["INR"]));
    expect(result.current.currency).toBe("INR"); // an international visitor, but no USD plans yet
    expect(result.current.annualAvailable).toBe(true);
  });

  it("reports yearly as unavailable when no dedicated yearly plan exists", async () => {
    options.current = { billingEnabled: true, gateway: "razorpay", currencies: ["INR", "USD"], annualCurrencies: ["INR"] };
    const { result } = renderHook(() => useCurrency());
    act(() => result.current.choose("USD"));
    await waitFor(() => expect(result.current.annualAvailable).toBe(false));
  });

  it("offers only INR when the options endpoint is unavailable (a USD pick has no plan behind it)", async () => {
    const { result } = renderHook(() => useCurrency());
    await waitFor(() => expect(result.current.choices).toEqual(["INR"]));
    expect(result.current.currency).toBe("INR");
  });
});
