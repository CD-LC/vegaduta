import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../../../messages/en.json";
vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
  },
}));
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const record = vi.fn();
const revoke = vi.fn();
const list = vi.fn();
vi.mock("./optInApi", () => ({ listWhatsAppOptIns: (...a: unknown[]) => list(...a) }));
vi.mock("../../lib/api", () => ({
  api: {
    recordWhatsAppOptIn: (...a: unknown[]) => record(...a),
    revokeWhatsAppOptIn: (...a: unknown[]) => revoke(...a),
  },
}));

import { WhatsAppOptInCard } from "./WhatsAppOptInCard";

beforeEach(() => {
  list.mockRejectedValue(new Error("404"));
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("WhatsAppOptInCard", () => {
  it("records an opt-in, lists it and revokes it", async () => {
    record.mockResolvedValue({ phoneDigits: "919876543210", source: "website_form", active: true });
    revoke.mockResolvedValue(true);
    render(<WhatsAppOptInCard />);
    fireEvent.change(screen.getByLabelText(/Phone number/), { target: { value: "+919876543210" } });
    fireEvent.click(screen.getByRole("button", { name: "Record opt-in" }));
    await waitFor(() => expect(record).toHaveBeenCalledWith("+919876543210", "website_form", undefined));
    expect(await screen.findByText("+919876543210")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Revoke opt-in for 919876543210/ }));
    await waitFor(() => expect(revoke).toHaveBeenCalledWith("919876543210"));
    expect(await screen.findByText("Revoked")).toBeTruthy();
  });

  it("shows an error when revoking a number with no record", async () => {
    revoke.mockResolvedValue(false);
    render(<WhatsAppOptInCard />);
    fireEvent.change(screen.getByLabelText("Revoke by number"), { target: { value: "+1555" } });
    fireEvent.click(screen.getAllByRole("button", { name: "Revoke" })[0]);
    expect(await screen.findByTestId("wa-optin-error")).toBeTruthy();
  });

  it("lists the server records page by page and re-reads them after a revoke", async () => {
    list.mockResolvedValue({
      rows: [{ phoneDigits: "911111111111", source: "checkout", active: true, consentedAt: "2026-10-01T00:00:00Z" }],
      total: 12,
      hasMore: true,
    });
    revoke.mockResolvedValue(true);
    render(<WhatsAppOptInCard />);
    expect(await screen.findByText("+911111111111")).toBeTruthy();
    expect(list).toHaveBeenCalledWith(10, 0);
    expect(screen.getByTestId("wa-optin-list").getAttribute("data-mode")).toBe("server");
    fireEvent.click(screen.getByRole("button", { name: /Revoke opt-in for 911111111111/ }));
    await waitFor(() => expect(revoke).toHaveBeenCalledWith("911111111111"));
    await waitFor(() => expect(list.mock.calls.length).toBeGreaterThan(1));
  });

  it("falls back to session records when the list endpoint fails", async () => {
    render(<WhatsAppOptInCard />);
    await waitFor(() => expect(screen.getByTestId("wa-optin-list").getAttribute("data-mode")).toBe("session"));
    expect(screen.getByText("No opt-ins recorded in this session yet.")).toBeTruthy();
  });
});
