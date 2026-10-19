import { afterEach, describe, expect, it, vi } from "vitest";
import en from "../../../messages/en.json";
vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
  },
}));
import { cleanup, render, screen } from "@testing-library/react";
import type { McpToolPinStatus } from "../../lib/api/mcpToolPins";

vi.mock("../../lib/api", () => ({ api: { getMcpToolPin: vi.fn() } }));

import { ToolPinBadge, ToolPinSummary, pinState } from "./ToolPinStatus";

afterEach(cleanup);

const st = (over: Partial<McpToolPinStatus> = {}): McpToolPinStatus => ({
  registrationId: "r1",
  name: "weather",
  mode: "warn",
  pinned: true,
  changed: false,
  pinnedHash: "a",
  observedHash: "a",
  pinnedAt: null,
  pinnedBy: null,
  changedAt: null,
  added: [],
  removed: [],
  changedTools: [],
  ...over,
});

describe("pinState", () => {
  it("maps the API status to the five visible states", () => {
    expect(pinState(st({ mode: "off" }))).toBe("off");
    expect(pinState(st())).toBe("pinned");
    expect(pinState(st({ changed: true }))).toBe("changed");
    expect(pinState(st({ changed: true, mode: "enforce" }))).toBe("awaiting");
    expect(pinState(st({ pinned: false }))).toBe("unpinned");
  });
});

describe("ToolPinBadge / ToolPinSummary", () => {
  it("renders the badge for a status and unknown for a failed read", () => {
    const { rerender } = render(<ToolPinBadge status={st({ changed: true, mode: "enforce" })} />);
    expect(screen.getByTestId("mcp-pin-badge-awaiting")).toBeTruthy();
    rerender(<ToolPinBadge failed />);
    expect(screen.getByText("Pin unknown")).toBeTruthy();
  });
  it("shows the persistent mode and all-current state", () => {
    render(<ToolPinSummary statuses={{ r1: st() }} />);
    expect(screen.getByTestId("mcp-pin-mode").textContent).toBe("Warn");
    expect(screen.getByText("All pins are current.")).toBeTruthy();
  });
  it("counts changed servers", () => {
    render(<ToolPinSummary statuses={{ r1: st({ changed: true }) }} />);
    expect(screen.getByText(/1 server\(s\) changed/)).toBeTruthy();
  });
});
