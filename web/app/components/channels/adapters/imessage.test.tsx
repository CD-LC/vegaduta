import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
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
vi.mock("../../../lib/api", () => ({
  api: {
    getImessageCredential: vi.fn().mockResolvedValue(null),
    activateImessageCredential: vi.fn(),
    updateImessageSettings: vi.fn(),
    disconnectImessageCredential: vi.fn(),
    sendImessage: vi.fn(),
    setImessageDefaultAgent: vi.fn(),
  },
}));

import { ImessageInspector } from "./imessage";

function ctx() {
  const panel = (en as unknown as Record<string, Record<string, string>>).channelsPanel;
  return {
    t: (key: string) => panel[key] ?? key,
    busy: false,
    setBusy: vi.fn(),
    agents: [] as { id: string; name: string }[],
    cardsByKey: new Map(),
    statusLoaded: true,
    setError: vi.fn(),
    setSuccess: vi.fn(),
    refreshStatus: vi.fn(),
  };
}

describe("ImessageInspector", () => {
  it("tells the operator up front that a Mac is required and masks the password field", async () => {
    render(<ImessageInspector ctx={ctx()} />);
    expect(await screen.findByRole("note")).toHaveTextContent(/Mac/);
    expect(screen.getByLabelText("BlueBubbles server password")).toHaveAttribute("type", "password");
  });

  it("shows no access settings or webhook until a server is connected", async () => {
    render(<ImessageInspector ctx={ctx()} />);
    await screen.findByRole("note");
    expect(screen.queryByTestId("imessage-access")).toBeNull();
    expect(screen.queryByTestId("imessage-webhook")).toBeNull();
  });
});
