import { describe, expect, it } from "vitest";
import { cardCat, cardMeta, cardState, cardTitle } from "./cardPresentation";

const t = (key: string) => key;

describe("cardPresentation", () => {
  it("states: live dot only for dynamic registrations, review overrides connection status", () => {
    const base = { selKey: "x", category: "mcp", kind: "mcp" } as any;
    expect(cardState({ ...base, data: { dynamic: false } })).toBeNull();
    expect(cardState({ ...base, data: { dynamic: true, connectionStatus: "CONNECTED" } })).toBe("var(--state-success)");
    expect(cardState({ ...base, data: { dynamic: true, connectionStatus: "ERROR" } })).toBe("var(--state-error)");
    expect(
      cardState({ ...base, data: { dynamic: true, connectionStatus: "CONNECTED", reviewStatus: "PENDING" } })
    ).toBe("var(--info-text)");
  });

  it("a disabled server drops to the idle category hue", () => {
    const card = { selKey: "x", category: "custom", kind: "mcp", data: { enabled: false } } as any;
    expect(cardCat(card)).toBe("var(--state-idle)");
  });

  it("titles and meta come from the card kind", () => {
    expect(cardTitle({ kind: "google-connect" }, t)).toBe("googleWorkspace");
    expect(cardTitle({ kind: "mcp", data: { name: "Github" } }, t)).toBe("Github");
    expect(cardMeta({ kind: "builtin" }, t)).toBe("builtInMeta");
  });
});
