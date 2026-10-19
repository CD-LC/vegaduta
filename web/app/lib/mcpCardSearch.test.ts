import { describe, expect, it } from "vitest";
import { cardSearchName } from "./mcpCardSearch";

const t = (key: string) => `T:${key}`;

describe("cardSearchName", () => {
  it("does not throw for the data-less 'Connect Google' card (the keystroke crash)", () => {
    expect(() => cardSearchName({ kind: "google-connect", data: null }, t)).not.toThrow();
    expect(cardSearchName({ kind: "google-connect", data: null }, t)).toBe("T:googleWorkspace");
  });

  it("uses the i18n key for built-in and Google cards", () => {
    expect(cardSearchName({ kind: "builtin", data: { nameKey: "k1" } }, t)).toBe("T:k1");
    expect(cardSearchName({ kind: "google", data: { nameKey: "k2" } }, t)).toBe("T:k2");
    expect(cardSearchName({ kind: "google", data: {} }, t)).toBe("T:googleWorkspace");
  });

  it("uses the plain name for MCP servers, catalog entries and custom servers", () => {
    expect(cardSearchName({ kind: "mcp", data: { name: "GitHub" } }, t)).toBe("GitHub");
    expect(cardSearchName({ kind: "catalog", data: { name: "Slack" } }, t)).toBe("Slack");
  });

  it("never throws on missing, null or non-string names - they just match nothing", () => {
    expect(cardSearchName({ kind: "mcp", data: null }, t)).toBe("");
    expect(cardSearchName({ kind: "mcp", data: { name: null } }, t)).toBe("");
    expect(cardSearchName({ kind: "mcp", data: { name: 42 } }, t)).toBe("");
    expect(cardSearchName({ kind: "mystery" }, t)).toBe("");
  });
});
