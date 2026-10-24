// The agent workbench playground (AgentPlayground + MessageList): a parked tool call is decided on its card.
// Core then carries the task on by itself (stores a platform-written "<approval_result ...>" message and runs a
// turn). The playground must visibly follow that turn - say the agent is carrying on, hold the composer back, and
// append the agent's reply once it is stored - for Approve and Reject alike, without starting a turn of its own.

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const edge = vi.hoisted(() => ({ on: false }));
const chat = vi.hoisted(() => ({ calls: [] as string[] }));
const net = vi.hoisted(() => ({
  transcript: [] as Array<{ role: string; content: string }>,
  pending: [] as unknown[],
}));
const approveRequest = vi.hoisted(() => vi.fn());
const rejectRequest = vi.hoisted(() => vi.fn());
const getChatTranscript = vi.hoisted(() => vi.fn());

const REQUEST_ID = "req-1";
const PENDING = {
  id: REQUEST_ID,
  actionType: "mcp_tool.send_email",
  summary: "Tool call: send_email",
  status: "PENDING",
  requestedBy: "agent:agent-1:session:session-1",
  payload: JSON.stringify({ replay: { version: 1 }, arguments: { to: "bob@example.com" } }),
  createdAt: "2026-10-03T10:00:00Z",
};
const marker = (outcome: string) =>
  `<approval_result request="${REQUEST_ID}" tool="send_email" outcome="${outcome}">\nplatform text\n</approval_result>`;

vi.mock("../../lib/api/assistant", () => ({ assistantApi: { getChatTranscript } }));
vi.mock("./approvalContinuation", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./approvalContinuation")>()),
  CONTINUATION_POLL_MS: 10,
  CONTINUATION_START_WAIT_MS: 400,
}));
vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => (vars ? `${key}:${JSON.stringify(vars)}` : key),
}));
vi.mock("../../lib/api", () => ({
  api: {
    streamChat: vi.fn(async (_a: string, message: string, _s: unknown, onChunk: (c: string) => void) => {
      chat.calls.push(message);
      onChunk("I need approval to send that. Waiting.");
      return "session-1";
    }),
    listApprovals: vi.fn(async () => net.pending),
    getApproval: vi.fn(async () => null),
    approveRequest,
    rejectRequest,
    listToolCalls: vi.fn(async () => []),
    getAgentSessionLlmUsage: vi.fn(async () => []),
    getAgentSessionLlmRequests: vi.fn(async () => []),
    getTenantSettings: vi.fn(async () => ({})),
    listProviders: vi.fn(async () => []),
    listProviderModels: vi.fn(async () => []),
    listCollections: vi.fn(async () => []),
    listFaceEnrollments: vi.fn(async () => []),
  },
}));
vi.mock("../../lib/edge/capabilities", async (importOriginal) => {
  const real = await importOriginal<typeof import("../../lib/edge/capabilities")>();
  return {
    ...real,
    edgeEnabled: () => edge.on,
    resolveAutomaticEdgeFallback: vi.fn(async () => true),
  };
});
vi.mock("../../lib/edge/tierRouter", () => ({
  routeTurn: vi.fn(async () => ({ handled: false, reason: "all-tiers-failed" })),
  resetEdgeSession: vi.fn(),
}));
vi.mock("./IntelligenceSourceControl", () => ({
  default: (): null => null,
  getEffectiveEdgeOptIn: async () => true,
}));
vi.mock("./CallPanel", () => ({ default: (): null => null }));
vi.mock("./CameraPanel", () => ({ default: (): null => null }));
vi.mock("./ShareAgentPanel", () => ({ default: (): null => null }));
vi.mock("./BrowserTakeoverPanel", () => ({ default: (): null => null }));
vi.mock("./RunCostCard", () => ({ default: (): null => null }));
vi.mock("./SaveRunAsRecipe", () => ({ default: (): null => null }));
vi.mock("./TurnFeedback", () => ({ default: (): null => null }));
vi.mock("./RequestInspector", () => ({ default: (): null => null }));
vi.mock("./lab/AgentLab", () => ({ default: (): null => null }));
vi.mock("./lab/AgentLabLauncher", () => ({ default: (): null => null }));
vi.mock("../edge/OnDeviceSetupNudge", () => ({ default: (): null => null }));
vi.mock("../SandboxDataHint", () => ({ default: (): null => null }));
vi.mock("../PlatformModelChangeNotice", () => ({ default: (): null => null }));
vi.mock("../../agents/quality/SaveAsEvalCaseButton", () => ({ default: (): null => null }));
vi.mock("../../lib/edge/useWebMcpTools", () => ({ useWebMcpTools: vi.fn() }));
vi.mock("../../lib/edge/tier1", () => ({
  prewarmTier1Engine: vi.fn(),
  getTier1RunModel: vi.fn(() => null),
}));
vi.mock("../../lib/edge/tier0", () => ({ tier0Available: vi.fn(async () => false) }));


const AGENT = { id: "agent-1", name: "mailer", providerName: "OPENROUTER", model: "deepseek/deepseek-chat" };

beforeAll(() => {
  HTMLMediaElement.prototype.pause = vi.fn();
  HTMLMediaElement.prototype.load = vi.fn();
  Element.prototype.scrollTo = vi.fn();
  Element.prototype.scrollIntoView = vi.fn();
});
beforeEach(() => {
  window.localStorage.clear();
  edge.on = false;
  chat.calls.length = 0;
  net.transcript = [];
  net.pending = [];
  approveRequest.mockReset();
  rejectRequest.mockReset();
  getChatTranscript.mockReset();
  getChatTranscript.mockImplementation(async () => ({ sessionId: "session-1", messages: net.transcript }));
});
afterEach(() => {
  cleanup();
  window.localStorage.clear();
});

const composer = () => screen.getByPlaceholderText("sendPlaceholder") as HTMLTextAreaElement;

async function parkedTurn() {
  const { default: AgentPlayground } = await import("./AgentPlayground");
  render(<AgentPlayground agent={AGENT} />);
  net.pending = [PENDING];
  fireEvent.change(composer(), { target: { value: "email bob the report" } });
  await act(async () => {
    fireEvent.keyDown(composer(), { key: "Enter" });
  });
  await screen.findByTestId(`tool-approval-${REQUEST_ID}`);
  net.transcript = [
    { role: "user", content: "email bob the report" },
    { role: "assistant", content: "I need approval to send that. Waiting." },
  ];
}

describe("AgentPlayground - the conversation continues after a tool approval is decided", () => {
  it("Approve: shows the agent carrying on, holds the composer, then appends its reply once", async () => {
    await parkedTurn();
    approveRequest.mockImplementation(async () => {
      net.transcript = [...net.transcript, { role: "user", content: marker("completed") }];
      return { ...PENDING, status: "APPROVED", executionResult: "Email sent" };
    });

    fireEvent.click(screen.getByTestId("tool-approval-approve"));
    expect(await screen.findByTestId("approval-continuing")).toBeTruthy();
    expect(composer().disabled).toBe(true);
    expect(screen.queryByText(/platform text/)).toBeNull();

    net.transcript = [...net.transcript, { role: "assistant", content: "Done - bob has the report." }];
    expect(await screen.findByText("Done - bob has the report.")).toBeTruthy();
    await waitFor(() => expect(screen.queryByTestId("approval-continuing")).toBeNull());
    expect(composer().disabled).toBe(false);

    expect(approveRequest).toHaveBeenCalledTimes(1);
    expect(chat.calls).toEqual(["email bob the report"]);
    const reads = getChatTranscript.mock.calls.length;
    await new Promise((r) => setTimeout(r, 60));
    expect(getChatTranscript.mock.calls.length).toBe(reads);
    expect(screen.getAllByText("Done - bob has the report.")).toHaveLength(1);
  });

  it("Reject: the agent's reply to the denial is shown the same way", async () => {
    await parkedTurn();
    rejectRequest.mockImplementation(async () => {
      net.transcript = [
        ...net.transcript,
        { role: "user", content: marker("denied") },
        { role: "assistant", content: "Understood - I will not send it." },
      ];
      return { ...PENDING, status: "REJECTED" };
    });

    fireEvent.click(screen.getByTestId("tool-approval-reject"));
    expect(await screen.findByText("Understood - I will not send it.")).toBeTruthy();
    expect(rejectRequest).toHaveBeenCalledTimes(1);
    expect(approveRequest).not.toHaveBeenCalled();
    expect(chat.calls).toEqual(["email bob the report"]);
    expect(composer().disabled).toBe(false);
  });

  it("says so, and frees the composer, when core did not restart the task", async () => {
    await parkedTurn();
    approveRequest.mockResolvedValue({ ...PENDING, status: "APPROVED", executionResult: "Email sent" });

    fireEvent.click(screen.getByTestId("tool-approval-approve"));
    expect(await screen.findByTestId("approval-continuation-notStarted")).toBeTruthy();
    expect(composer().disabled).toBe(false);
  });
});
