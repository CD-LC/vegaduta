// SE-30: the composer blocks a send above the server's 8000-character cap (ChatRequest.message) instead of
// letting the server answer 400.

import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";

const chat = vi.hoisted(() => ({ calls: [] as string[] }));

vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => (vars ? `${key}:${JSON.stringify(vars)}` : key),
}));
vi.mock("../lib/auth", () => ({ auth: { getUserId: () => "u1" } }));
vi.mock("../lib/api", () => ({
  api: {
    streamChat: vi.fn(async (_a: string, message: string, _s: unknown, onChunk: (c: string) => void) => {
      chat.calls.push(message);
      onChunk("All good now.");
      return "session-1";
    }),
    getAgentSessionLlmRequests: vi.fn(async () => []),
    listToolCalls: vi.fn(async () => []),
    listMemories: vi.fn(async () => []),
    getChatBranchingCapabilities: vi.fn(async () => ({ enabled: false })),
    getBranchPath: vi.fn(async () => ({ messages: [] })),
    resumeChatTurn: vi.fn(),
  },
}));
vi.mock("../lib/edge/capabilities", () => ({
  detectCapabilities: vi.fn(async () => ({})),
  edgeEnabled: () => false,
  edgeFallbackLabel: (r: string) => r,
  edgeFallbackShortLabel: (r: string) => r,
  resolveAutomaticEdgeFallback: vi.fn(async () => true),
  EDGE_FALLBACK_ASK_TIMEOUT_MS: 1000,
  getEdgeOptIn: () => false,
}));
vi.mock("../lib/edge/tierRouter", () => ({ routeTurn: vi.fn(), resetEdgeSession: vi.fn() }));
vi.mock("../lib/edge/agentPolicy", () => ({ fetchEdgeAgentPolicy: vi.fn(async () => null) }));
vi.mock("../lib/edge/localTools", () => ({ listLocalTools: vi.fn(() => []) }));
vi.mock("../lib/edge/toolBridge", () => ({ fetchEdgeTools: vi.fn(async () => null), serverToolBlockReason: vi.fn(() => null) }));
vi.mock("../lib/edge/tier1", () => ({ getTier1RunModel: vi.fn(() => null) }));
vi.mock("../lib/edge/useEdgeWarmState", () => ({
  useEdgeWarmState: () => ({
    edgePhase: null as string | null,
    edgeWarmingPercent: null as number | null,
    tier1PreparingNotice: false,
    beginEdgeAttempt: vi.fn(),
    endEdgeAttempt: vi.fn(),
  }),
}));
vi.mock("../lib/edge/useWebMcpTools", () => ({ useWebMcpTools: vi.fn() }));
vi.mock("./edge/OnDeviceSetupNudge", () => ({ default: (): null => null }));
vi.mock("./agents/PendingToolApprovals", () => ({ default: (): null => null }));
vi.mock("./agents/BrowserTakeoverPanel", () => ({ default: (): null => null }));
vi.mock("./agents/RunCostCard", () => ({ default: (): null => null }));
vi.mock("./memory/ChatMemoryControls", () => ({ ActiveProjectSwitcher: (): null => null, ManageMemoryLink: (): null => null }));
vi.mock("./ChatExportMenu", () => ({ default: (): null => null }));
vi.mock("../agents/quality/SaveAsEvalCaseButton", () => ({ default: (): null => null }));
vi.mock("./agents/TurnFeedback", () => ({ default: (): null => null }));

const AGENT = { id: "agent-1", name: "e2e-agent-2", providerName: "GOOGLE_VERTEX" };

beforeAll(() => {
  Element.prototype.scrollTo = vi.fn();
});
afterEach(() => {
  cleanup();
  chat.calls.length = 0;
});

async function renderPanel() {
  const { default: ChatPanel } = await import("./ChatPanel");
  render(<ChatPanel agent={AGENT} />);
  return screen.getByPlaceholderText("sendPlaceholder") as HTMLInputElement;
}

describe("ChatPanel - composer length limit", () => {
  it("allows exactly 8000 characters and shows the counter", async () => {
    const box = await renderPanel();
    fireEvent.change(box, { target: { value: "a".repeat(8000) } });
    expect(screen.getByRole("button", { name: "send" }).hasAttribute("disabled")).toBe(false);
    expect(screen.getByText(/messageCounter/)).toBeTruthy();
  });

  it("disables Send and never calls the server above 8000", async () => {
    const box = await renderPanel();
    fireEvent.change(box, { target: { value: "a".repeat(8001) } });
    expect(screen.getByRole("button", { name: "send" }).hasAttribute("disabled")).toBe(true);
    expect(screen.getByRole("alert").textContent).toContain("messageTooLong");
    await act(async () => {
      fireEvent.submit(box.closest("form") as HTMLFormElement);
    });
    expect(chat.calls).toHaveLength(0);
  });

  it("shows no counter for a short message", async () => {
    const box = await renderPanel();
    fireEvent.change(box, { target: { value: "hi" } });
    expect(screen.queryByText(/messageCounter/)).toBeNull();
  });
});
