import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import WhatsAppCostCalculator from "./calculator";
import { PRICING_CHECKED_ON, RATES_VERIFIED, parseRate } from "./pricing-facts";

describe("WhatsApp cost calculator without verified rates", () => {
  it("ships no verified numeric rates, so it must not pre-fill any", () => {
    expect(RATES_VERIFIED).toBe(false);
    const { container } = render(<WhatsAppCostCalculator />);
    const rateInputs = container.querySelectorAll<HTMLInputElement>("#wa-rate-marketing, #wa-rate-utility");
    expect(rateInputs).toHaveLength(2);
    rateInputs.forEach((i) => expect(i.value).toBe(""));
  });

  it("says to check Meta's current rates and shows no amounts", () => {
    const { container } = render(<WhatsAppCostCalculator />);
    expect(screen.getByTestId("wa-no-rates").textContent).toContain("Check Meta's current rates");
    expect(container.textContent).not.toContain("₹0.8631");
    expect(container.querySelector('[data-testid="wa-calc-delta"]')).toBeNull();
  });

  it("shows the date the pricing model was checked", () => {
    render(<WhatsAppCostCalculator />);
    expect(screen.getByTestId("wa-rates-note").textContent).toContain(PRICING_CHECKED_ON);
  });

  it("computes from rates the user types, and only then", () => {
    const { container } = render(<WhatsAppCostCalculator />);
    fireEvent.change(container.querySelector("#wa-rate-marketing")!, { target: { value: "1" } });
    expect(screen.queryByTestId("wa-calc-delta")).toBeNull();
    fireEvent.change(container.querySelector("#wa-rate-utility")!, { target: { value: "1" } });
    // 1000 conversations x (10 - 4) msgs x 1 per message
    expect(screen.getByTestId("wa-calc-delta").textContent).toContain("6,000");
    expect(screen.queryByTestId("wa-no-rates")).toBeNull();
  });
});

describe("parseRate", () => {
  it("accepts positive numbers only", () => {
    expect(parseRate("0.5")).toBe(0.5);
    for (const bad of ["", "  ", "abc", "0", "-1"]) expect(parseRate(bad)).toBeNull();
  });
});
