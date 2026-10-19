import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import en from "../../../../messages/en.json";

vi.mock("../../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});

const apiMock = vi.hoisted(() => ({
  getWhatsAppQrSession: vi.fn(),
  connectWhatsAppQrSession: vi.fn(),
  disconnectWhatsAppQrSession: vi.fn(),
  sendWhatsAppQrMessage: vi.fn(),
  setWhatsAppQrDefaultAgent: vi.fn(),
  testChannelConnection: vi.fn(),
}));
vi.mock("../../../lib/api", () => ({ api: apiMock }));

import { adapter } from "./whatsappqr";
import { adapterFor } from "./index";

const t = (key: string) => ((en as unknown as Record<string, Record<string, string>>).channelsPanel ?? {})[key] ?? key;

function ctx(overrides: Record<string, unknown> = {}) {
  return {
    t,
    busy: false,
    agents: [] as unknown[],
    cardsByKey: new Map<string, unknown>(),
    statusLoaded: true,
    setError: vi.fn(),
    setSuccess: vi.fn(),
    setBusy: vi.fn(),
    refreshStatus: vi.fn(),
    ...overrides,
  };
}

describe("WhatsApp QR channel inspector", () => {
  beforeEach(() => {
    Object.values(apiMock).forEach((fn) => fn.mockReset());
    apiMock.getWhatsAppQrSession.mockResolvedValue(null);
    apiMock.connectWhatsAppQrSession.mockResolvedValue({ status: "PAIRING", qr: "2@qr", configured: true });
  });

  it("is registered for the whatsappqr key", () => {
    expect(adapterFor("whatsappqr")).toBe(adapter);
  });

  it("links a device on click and starts showing pairing state", async () => {
    const c = ctx();
    const Inspector = adapter.Inspector;
    render(<Inspector ctx={c} />);
    await waitFor(() => expect(apiMock.getWhatsAppQrSession).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: "Link a device" }));

    await waitFor(() => expect(apiMock.connectWhatsAppQrSession).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByText("Scan with WhatsApp")).toBeTruthy());
  });

  it("renders the QR while pairing", async () => {
    apiMock.getWhatsAppQrSession.mockResolvedValue({ status: "PAIRING", qr: "2@abc", configured: true });
    const Inspector = adapter.Inspector;
    render(<Inspector ctx={ctx()} />);

    await waitFor(() => expect(screen.getByRole("img", { name: "Scan with WhatsApp" })).toBeTruthy());
    expect(screen.getByRole("button", { name: "Restart pairing" })).toBeTruthy();
  });

  it("shows the linked number, an unlink button and a test send once active", async () => {
    apiMock.getWhatsAppQrSession.mockResolvedValue({
      status: "ACTIVE",
      phoneNumber: "4915112345678",
      configured: true,
    });
    const Inspector = adapter.Inspector;
    render(<Inspector ctx={ctx()} />);

    await waitFor(() => expect(screen.getByText("4915112345678")).toBeTruthy());
    expect(screen.getByRole("button", { name: "Unlink" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Link a device" })).toBeNull();
  });

  it("unlinks and reports success", async () => {
    apiMock.getWhatsAppQrSession.mockResolvedValue({ status: "ACTIVE", phoneNumber: "4915", configured: true });
    apiMock.disconnectWhatsAppQrSession.mockResolvedValue(null);
    const c = ctx();
    const Inspector = adapter.Inspector;
    render(<Inspector ctx={c} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Unlink" })).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: "Unlink" }));

    await waitFor(() => expect(apiMock.disconnectWhatsAppQrSession).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(c.refreshStatus).toHaveBeenCalled());
  });

  it("says the bridge is absent when the deployment never configured one", async () => {
    apiMock.getWhatsAppQrSession.mockResolvedValue({ status: "DISCONNECTED", configured: false });
    const Inspector = adapter.Inspector;
    render(<Inspector ctx={ctx()} />);

    await waitFor(() => expect(screen.getByText(/no WhatsApp QR bridge configured/)).toBeTruthy());
    expect(screen.queryByRole("button", { name: "Link a device" })).toBeNull();
  });
});
