// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";
import PowerDial from "./PowerDial";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => ((en as unknown as { landing: Record<string, unknown> }).landing[key] as string | undefined) ?? key,
}));

afterEach(cleanup);

describe("PowerDial", () => {
  it("lists the three power sources in order, first one selected, none missing a translation", () => {
    render(<PowerDial />);
    const buttons = screen.getAllByRole("button");
    expect(buttons.map((b) => b.textContent)).toEqual(["BYOK", "Edge models", "Shared pool"]);
    expect(buttons[0].getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByTestId("hero-power-dial").textContent).not.toMatch(/heroPower_/);
  });

  it("redraws the route and the line when a source is picked, and stays put", () => {
    render(<PowerDial />);
    fireEvent.click(screen.getByRole("button", { name: "Edge models" }));
    const root = screen.getByTestId("hero-power-dial");
    expect(screen.getByRole("button", { name: "Edge models" }).getAttribute("aria-pressed")).toBe("true");
    expect(root.textContent).toContain("Free, on your device");
    expect(root.textContent).toContain("Small models that run on your own phone or laptop");
  });
});
