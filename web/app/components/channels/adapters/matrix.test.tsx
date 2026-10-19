import { describe, expect, it, vi } from "vitest";
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

const api = vi.hoisted(() => ({
  getMatrixCredential: vi.fn(),
  activateMatrixCredential: vi.fn(),
  disconnectMatrixCredential: vi.fn(),
  setMatrixDefaultAgent: vi.fn(),
  updateMatrixPolicy: vi.fn(),
  sendMatrixMessage: vi.fn(),
}));
vi.mock("../../../lib/api", () => ({ api }));

import { MatrixInspector } from "./matrix";

function ctx(overrides: Record<string, unknown> = {}) {
  return {
    t: (k: string) => k,
    agents: [] as unknown[],
    cardsByKey: new Map(),
    statusLoaded: true,
    busy: false,
    setError: vi.fn(),
    setSuccess: vi.fn(),
    setBusy: vi.fn(),
    refreshStatus: vi.fn(),
    ...overrides,
  };
}

describe("MatrixInspector", () => {
  it("asks for the homeserver and token and refuses an empty submit", async () => {
    api.getMatrixCredential.mockResolvedValue(null);
    const c = ctx();
    render(<MatrixInspector ctx={c} />);

    expect(await screen.findByLabelText(/Homeserver URL/)).toBeTruthy();
    expect(screen.getByLabelText(/Bot access token/, { selector: "input" })).toBeTruthy();
    // End-to-end encryption is not supported and the panel says so.
    expect(screen.getByText(/End-to-end encrypted rooms are not supported/)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "connectButton" }));
    expect(c.setError).toHaveBeenCalledWith(en.channelsMatrix.requiredError);
    expect(api.activateMatrixCredential).not.toHaveBeenCalled();
  });

  it("connects with the closed defaults: pairing for senders, no join on invite", async () => {
    api.getMatrixCredential.mockResolvedValue(null);
    api.activateMatrixCredential.mockResolvedValue({});
    render(<MatrixInspector ctx={ctx()} />);

    fireEvent.change(await screen.findByLabelText(/Homeserver URL/), { target: { value: " https://hs.example " } });
    fireEvent.change(screen.getByLabelText(/Bot access token/, { selector: "input" }), { target: { value: "tok" } });
    fireEvent.click(screen.getByRole("button", { name: "connectButton" }));

    await waitFor(() => expect(api.activateMatrixCredential).toHaveBeenCalled());
    expect(api.activateMatrixCredential).toHaveBeenCalledWith({
      homeserverUrl: "https://hs.example",
      accessToken: "tok",
      joinPolicy: "OFF",
      senderMode: "PAIRING",
      allowedSenders: "",
    });
  });

  it("warns when the receive loop is not running and shows the connected bot", async () => {
    api.getMatrixCredential.mockResolvedValue({
      id: "1",
      botUserId: "@bot:hs.example",
      homeserverUrl: "https://hs.example",
      status: "ACTIVE",
      joinPolicy: "OFF",
      senderMode: "PAIRING",
      allowedSenders: "",
      inboundActive: false,
    });
    render(<MatrixInspector ctx={ctx()} />);

    expect(await screen.findByText("@bot:hs.example")).toBeTruthy();
    expect(screen.getByText(/not running the Matrix receive loop/)).toBeTruthy();
  });
});
