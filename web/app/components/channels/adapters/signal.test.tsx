import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
  getSignalCredential: vi.fn(),
  activateSignalCredential: vi.fn(),
  updateSignalPolicy: vi.fn(),
  disconnectSignalCredential: vi.fn(),
  sendSignalMessage: vi.fn(),
  setSignalDefaultAgent: vi.fn(),
  testChannelConnection: vi.fn(),
}));
vi.mock("../../../lib/api", () => ({ api: apiMock }));

import { adapter } from "./signal";
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

const active = {
  id: "1",
  baseUrl: "http://signal-cli:8080",
  number: "+4915112345678",
  hasAuthToken: false,
  unknownSenders: "PAIR",
  groupMode: "IGNORE",
  status: "ACTIVE",
};

describe("Signal channel inspector", () => {
  beforeEach(() => {
    Object.values(apiMock).forEach((fn) => fn.mockReset());
    apiMock.getSignalCredential.mockResolvedValue(null);
    apiMock.activateSignalCredential.mockResolvedValue(active);
  });

  it("is registered for the signal key", () => {
    expect(adapterFor("signal")).toBe(adapter);
  });

  it("asks for the URL and number before calling the API", async () => {
    const c = ctx();
    const Inspector = adapter.Inspector;
    render(<Inspector ctx={c} />);
    await waitFor(() => expect(apiMock.getSignalCredential).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: "Connect" }));

    expect(c.setError).toHaveBeenCalledWith("Enter the REST API URL and the registered number.");
    expect(apiMock.activateSignalCredential).not.toHaveBeenCalled();
  });

  it("connects with trimmed values, the safe default policy, and no token unless one is typed", async () => {
    const Inspector = adapter.Inspector;
    render(<Inspector ctx={ctx()} />);
    await waitFor(() => expect(apiMock.getSignalCredential).toHaveBeenCalled());

    fireEvent.change(screen.getByLabelText("signal-cli REST API URL"), { target: { value: " http://signal-cli:8080 " } });
    fireEvent.change(screen.getByLabelText("Registered number"), { target: { value: " +4915112345678" } });
    fireEvent.click(screen.getByRole("button", { name: "Connect" }));

    await waitFor(() => expect(apiMock.activateSignalCredential).toHaveBeenCalledTimes(1));
    expect(apiMock.activateSignalCredential).toHaveBeenCalledWith({
      baseUrl: "http://signal-cli:8080",
      number: "+4915112345678",
      authToken: undefined,
      unknownSenders: "PAIR",
      groupMode: "IGNORE",
    });
  });

  it("shows the who-is-answered settings only once connected, and saves them", async () => {
    apiMock.getSignalCredential.mockResolvedValue(active);
    apiMock.updateSignalPolicy.mockResolvedValue({ ...active, groupMode: "IGNORE" });
    const c = ctx();
    const Inspector = adapter.Inspector;
    render(<Inspector ctx={c} />);

    const heading = await screen.findByText("Who the agent answers");
    const form = heading.closest("form") as HTMLElement;
    fireEvent.click(within(form).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(apiMock.updateSignalPolicy).toHaveBeenCalledWith({ unknownSenders: "PAIR", groupMode: "IGNORE" }));
    await waitFor(() => expect(c.setSuccess).toHaveBeenCalledWith("Signal settings saved."));
  });

  it("does not offer the settings while nothing is connected", async () => {
    const Inspector = adapter.Inspector;
    render(<Inspector ctx={ctx()} />);
    await waitFor(() => expect(apiMock.getSignalCredential).toHaveBeenCalled());

    expect(screen.queryByText("Who the agent answers")).toBeNull();
  });
});
