import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import CurrencySwitch from "./CurrencySwitch";

describe("CurrencySwitch", () => {
  it("renders nothing when there is only one billable currency", () => {
    const { container } = render(<CurrencySwitch currency="INR" detected="INR" choices={["INR"]} onChange={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("marks the current currency and reports a change to the other one", () => {
    const onChange = vi.fn();
    render(<CurrencySwitch currency="INR" detected="INR" choices={["INR", "USD"]} onChange={onChange} />);
    expect(screen.getByRole("button", { name: "₹ INR" }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: "$ USD" }).getAttribute("aria-pressed")).toBe("false");
    fireEvent.click(screen.getByRole("button", { name: "$ USD" }));
    expect(onChange).toHaveBeenCalledWith("USD");
  });

  it("explains whether the currency came from detection or from the customer", () => {
    const { rerender } = render(<CurrencySwitch currency="INR" detected="INR" choices={["INR", "USD"]} onChange={vi.fn()} />);
    expect(screen.getByText(/Shown for your region/)).toBeTruthy();
    rerender(<CurrencySwitch currency="USD" detected="INR" choices={["INR", "USD"]} onChange={vi.fn()} />);
    expect(screen.getByText(/You chose this currency/)).toBeTruthy();
  });

  it("can be disabled while a payment is opening", () => {
    render(<CurrencySwitch currency="INR" detected="INR" choices={["INR", "USD"]} onChange={vi.fn()} disabled />);
    expect((screen.getByRole("button", { name: "$ USD" }) as HTMLButtonElement).disabled).toBe(true);
  });
});
