// /connect against a mocked API. The translator reads the real messages/en.json, so a missing key fails as its raw path.
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import en from "../../messages/en.json";

const api = vi.hoisted(() => ({ getChannelsStatus: vi.fn(), getGoogleConnection: vi.fn() }));
vi.mock("../lib/api", () => ({ api }));
vi.mock("../components/AppShell", () => ({
  default: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
}));
vi.mock("../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string) => {
    const value = key
      .split(".")
      .reduce<unknown>(
        (o, k) => (o as Record<string, unknown> | undefined)?.[k],
        (en as unknown as Record<string, unknown>)[namespace]
      );
    return typeof value === "string" ? value : `${namespace}.${key}`;
  },
}));

import ConnectPage from "./page";

beforeEach(() => {
  api.getChannelsStatus.mockResolvedValue({
    channels: [
      { key: "telegram", state: "CONNECTED" },
      { key: "whatsappqr", state: "DISCONNECTED" },
    ],
  });
  api.getGoogleConnection.mockResolvedValue(null);
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("/connect", () => {
  it("lists every way to connect with real copy and the right deep links", async () => {
    render(<ConnectPage />);
    for (const id of ["telegram", "whatsapp", "email", "google", "slack", "sms", "knowledge"]) {
      expect(screen.getByTestId(`connect-${id}`)).toBeTruthy();
    }
    expect(screen.queryByText(/connectHub\./)).toBeNull(); // no raw missing-key paths
    const whatsapp = within(screen.getByTestId("connect-whatsapp")).getByRole("link");
    expect(whatsapp.getAttribute("href")).toBe("/admin/channels?channel=whatsappqr");
    expect(within(screen.getByTestId("connect-google")).getByRole("link").getAttribute("href")).toBe(
      "/admin/integrations"
    );
  });

  it("has no Slack card on a personal install and keeps the rest", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    try {
      render(<ConnectPage />);
      expect(screen.queryByTestId("connect-slack")).toBeNull();
      for (const id of ["telegram", "whatsapp", "email", "google", "sms", "knowledge"]) {
        expect(screen.getByTestId(`connect-${id}`)).toBeTruthy();
      }
    } finally {
      vi.unstubAllEnvs();
    }
  });

  it("marks a connected channel and offers Manage instead of Connect", async () => {
    render(<ConnectPage />);
    await waitFor(() => expect(within(screen.getByTestId("connect-telegram")).getByText("Connected")).toBeTruthy());
    expect(within(screen.getByTestId("connect-telegram")).getByRole("link").textContent).toBe("Manage");
    expect(within(screen.getByTestId("connect-whatsapp")).queryByText("Connected")).toBeNull();
    expect(within(screen.getByTestId("connect-whatsapp")).getByRole("link").textContent).toBe("Link WhatsApp");
  });

  it("still renders when status calls fail", async () => {
    api.getChannelsStatus.mockRejectedValue(new Error("down"));
    api.getGoogleConnection.mockRejectedValue(new Error("down"));
    render(<ConnectPage />);
    await waitFor(() => expect(api.getChannelsStatus).toHaveBeenCalled());
    expect(screen.getByTestId("connect-telegram")).toBeTruthy();
    expect(screen.queryByText("Connected")).toBeNull();
  });
});
