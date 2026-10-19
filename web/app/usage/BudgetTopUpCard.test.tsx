import { afterEach, describe, expect, it, vi } from "vitest";
import en from "../../messages/en.json";
const translators: Record<string, (key: string, values?: Record<string, unknown>) => string> = {};
vi.mock("../lib/i18n", () => ({
  // Stable per namespace, like the real hook (callers put t in effect dependencies).
  useTranslations: (namespace: string) =>
    (translators[namespace] ??= (key, values) => {
      const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
      if (typeof template !== "string") return `${namespace}.${key}`;
      return template.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
    }),
}));
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const options = vi.fn();
const purchase = vi.fn();
const confirmTopUp = vi.fn();
const openCheckout = vi.fn();
vi.mock("../lib/api/budgetTopUp", () => ({
  budgetTopUpApi: {
    getBudgetTopUpOptions: (...a: unknown[]) => options(...a),
    purchaseBudgetTopUp: (...a: unknown[]) => purchase(...a),
    confirmBudgetTopUp: (...a: unknown[]) => confirmTopUp(...a),
  },
}));
vi.mock("../lib/payments/checkout", () => ({ openOrderCheckout: (...a: unknown[]) => openCheckout(...a) }));

import BudgetTopUpCard from "./BudgetTopUpCard";

const OPTS = {
  enabled: true,
  currency: "INR",
  minUsd: 5,
  maxUsd: 100,
  gatewayName: "Razorpay",
  publicKeyId: "rzp_test_x",
  options: [
    { usdAmount: 10, amountInSubunits: 83000, label: "$10" },
    { usdAmount: 25, amountInSubunits: 207500, label: "$25" },
  ],
};
const ORDER = {
  gatewayName: "Razorpay",
  publicKeyId: "rzp_test_x",
  orderId: "order_1",
  amountInSubunits: 83000,
  currency: "INR",
  modality: "text",
  amountUsd: 10,
  status: "created",
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("BudgetTopUpCard", () => {
  it("renders nothing when top-ups are disabled", async () => {
    options.mockResolvedValue({ ...OPTS, enabled: false });
    const { container } = render(<BudgetTopUpCard />);
    await waitFor(() => expect(options).toHaveBeenCalled());
    await waitFor(() => expect(container.querySelector("[data-testid=topup-skeleton]")).toBeNull());
    expect(container.querySelector("[data-testid=budget-topup]")).toBeNull();
  });

  it("creates an order, opens checkout and confirms the payment", async () => {
    options.mockResolvedValue(OPTS);
    purchase.mockResolvedValue(ORDER);
    confirmTopUp.mockResolvedValue({ credited: true, alreadyProcessed: false, modality: "text", amountUsd: 10 });
    openCheckout.mockImplementation(async (_order, o) => {
      o.onPaid({ gateway: "razorpay", orderId: "order_1", paymentId: "pay_1", signature: "sig" });
      return "opened";
    });
    render(<BudgetTopUpCard />);
    fireEvent.click(await screen.findByRole("button", { name: "Top up" }));
    await waitFor(() => expect(purchase).toHaveBeenCalledWith(10));
    await waitFor(() => expect(confirmTopUp).toHaveBeenCalledWith("order_1", "pay_1", "sig"));
    expect((await screen.findByTestId("topup-success")).textContent).toMatch(/Added \$10/);
  });

  it("rejects an out-of-range amount and surfaces a confirm failure with retry", async () => {
    options.mockResolvedValue(OPTS);
    purchase.mockResolvedValue(ORDER);
    confirmTopUp.mockRejectedValueOnce(new Error("boom"));
    openCheckout.mockImplementation(async (_o, o) => {
      o.onPaid({ gateway: "razorpay", orderId: "order_1", paymentId: "pay_1", signature: "sig" });
      return "opened";
    });
    render(<BudgetTopUpCard />);
    const input = await screen.findByLabelText("Amount (USD)");
    fireEvent.change(input, { target: { value: "1" } });
    fireEvent.click(screen.getByRole("button", { name: "Top up" }));
    expect((await screen.findByTestId("topup-error")).textContent).toMatch(/between \$5 and \$100/);
    expect(purchase).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: "10" } });
    fireEvent.click(screen.getByRole("button", { name: "Top up" }));
    expect(await screen.findByRole("button", { name: "Retry confirmation" })).toBeTruthy();
  });
});
