// Prod re-verification 2026-10-05, bug 13: a failed turn's error showed live and was gone after a reload. In the
// chat panel the failure is now a transcript entry: an inline "Reply failed" bubble with "Try again", and a
// history that the server returns with the failed turn recorded (any of the markers in lib/failedTurn.ts) renders
// the same bubble.

import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const chat = vi.hoisted(() => ({ calls: [] as string[], fail: true }));
const REJECTED = "The AI provider rejected our credentials. Check the API key.";

vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => (vars ? `${key}:${JSON.stringify(vars)}` : key),
}));
vi.mock("../lib/auth", () => ({ auth: { getUserId: () => "u1" } }));
vi.mock("../lib/api", () => ({
  api: {
    streamChat: vi.fn(async (_a: string, message: string, _s: unknown, onChunk: (c: string) => void) => {
      chat.calls.push(message);
      if (chat.fail) throw new Error(REJECTED);
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
  chat.fail = true;
});

async function sendFailing() {
  const { default: ChatPanel } = await import("./ChatPanel");
  render(<ChatPanel agent={AGENT} />);
  fireEvent.change(screen.getByPlaceholderText("sendPlaceholder"), { target: { value: "Say ok." } });
  await act(async () => {
    fireEvent.submit(screen.getByPlaceholderText("sendPlaceholder").closest("form") as HTMLFormElement);
  });
  return screen.findByTestId("failed-reply");
}

describe("ChatPanel - a failed reply is an inline transcript entry", () => {
  it("shows 'Reply failed' + the reason under the user message, once", async () => {
    const bubble = await sendFailing();
    expect(bubble.textContent).toContain("label");
    expect(bubble.textContent).toContain(REJECTED);
    expect(screen.getAllByText(REJECTED)).toHaveLength(1);
    expect(screen.getByText("Say ok.")).toBeTruthy();
  });

  it("'Try again' resends the previous user message and replaces the failed exchange", async () => {
    await sendFailing();
    chat.fail = false;
    chat.calls.length = 0;
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "tryAgainLabel" }));
    });
    await waitFor(() => expect(screen.getByRole("log").textContent).toContain("All good now."));
    expect(chat.calls).toEqual(["Say ok."]);
    expect(screen.queryByTestId("failed-reply")).toBeNull();
    expect(screen.getAllByText("Say ok.")).toHaveLength(1);
  });
});
