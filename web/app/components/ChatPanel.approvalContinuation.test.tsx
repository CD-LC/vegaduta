// A parked tool call is decided on the card inside the chat. Core then carries the task on by itself (it stores a
// platform-written "<approval_result ...>" message and runs a turn). The chat must visibly follow that turn:
// say the agent is carrying on, hold the box back, and show the agent's reply once it is stored - for Approve and
// for Reject alike, and without starting a second turn of its own.

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const net = vi.hoisted(() => ({
  transcript: [] as Array<{ role: string; content: string }>,
  pending: [] as unknown[],
  chatCalls: [] as string[],
}));
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

const approveRequest = vi.hoisted(() => vi.fn());
const rejectRequest = vi.hoisted(() => vi.fn());
const getChatTranscript = vi.hoisted(() => vi.fn());

vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => (vars ? `${key}:${JSON.stringify(vars)}` : key),
}));
vi.mock("../lib/auth", () => ({ auth: { getUserId: () => "u1" } }));
vi.mock("../lib/api/assistant", () => ({ assistantApi: { getChatTranscript } }));
vi.mock("./agents/approvalContinuation", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./agents/approvalContinuation")>()),
  CONTINUATION_POLL_MS: 10,
  CONTINUATION_START_WAIT_MS: 400,
}));
vi.mock("../lib/api", () => ({
  api: {
    streamChat: vi.fn(async (_a: string, message: string, _s: unknown, onChunk: (c: string) => void) => {
      net.chatCalls.push(message);
      onChunk("I need approval to send that. Waiting.");
      return "session-1";
    }),
    listApprovals: vi.fn(async () => net.pending),
    getApproval: vi.fn(async () => null),
    approveRequest,
    rejectRequest,
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
vi.mock("./agents/BrowserTakeoverPanel", () => ({ default: (): null => null }));
vi.mock("./agents/RunCostCard", () => ({ default: (): null => null }));
vi.mock("./memory/ChatMemoryControls", () => ({ ActiveProjectSwitcher: (): null => null, ManageMemoryLink: (): null => null }));
vi.mock("./ChatExportMenu", () => ({ default: (): null => null }));
vi.mock("../agents/quality/SaveAsEvalCaseButton", () => ({ default: (): null => null }));
vi.mock("./agents/TurnFeedback", () => ({ default: (): null => null }));

const AGENT = { id: "agent-1", name: "mailer", providerName: "GOOGLE_VERTEX" };

beforeAll(() => {
  Element.prototype.scrollTo = vi.fn();
});
beforeEach(() => {
  net.transcript = [];
  net.pending = [];
  net.chatCalls.length = 0;
  approveRequest.mockReset();
  rejectRequest.mockReset();
  getChatTranscript.mockReset();
  getChatTranscript.mockImplementation(async () => ({ sessionId: "session-1", messages: net.transcript }));
});
afterEach(() => cleanup());

async function parkedTurn() {
  const { default: ChatPanel } = await import("./ChatPanel");
  render(<ChatPanel agent={AGENT} />);
  net.pending = [PENDING];
  const box = screen.getByPlaceholderText("sendPlaceholder") as HTMLInputElement;
  fireEvent.change(box, { target: { value: "email bob the report" } });
  await act(async () => {
    fireEvent.submit(box.closest("form") as HTMLFormElement);
  });
  await screen.findByTestId(`tool-approval-${REQUEST_ID}`);
  net.transcript = [
    { role: "user", content: "email bob the report" },
    { role: "assistant", content: "I need approval to send that. Waiting." },
  ];
}

const box = () => screen.getByPlaceholderText("sendPlaceholder") as HTMLInputElement;

describe("ChatPanel - the conversation continues after a tool approval is decided", () => {
  it("Approve: shows the agent carrying on, holds the box, then appends its reply once", async () => {
    await parkedTurn();
    approveRequest.mockImplementation(async () => {
      // Core started the continuation inside the approve call: its message is stored, the reply is not.
      net.transcript = [...net.transcript, { role: "user", content: marker("completed") }];
      return { ...PENDING, status: "APPROVED", executionResult: "Email sent" };
    });

    fireEvent.click(screen.getByTestId("tool-approval-approve"));
    expect(await screen.findByTestId("approval-continuing")).toBeTruthy();
    expect(box().disabled).toBe(true);
    // The platform's message is not shown as something the person said.
    expect(screen.queryByText(/platform text/)).toBeNull();

    net.transcript = [...net.transcript, { role: "assistant", content: "Done - bob has the report." }];
    await waitFor(() => expect(screen.getByRole("log").textContent).toContain("Done - bob has the report."));
    expect(screen.getAllByText("Done - bob has the report.")).toHaveLength(1);
    await waitFor(() => expect(screen.queryByTestId("approval-continuing")).toBeNull());
    expect(box().disabled).toBe(false);

    // Observed, not re-sent: one approve call, no extra turn started by the browser, polling ended with the reply.
    expect(approveRequest).toHaveBeenCalledTimes(1);
    expect(net.chatCalls).toEqual(["email bob the report"]);
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
    await waitFor(() => expect(screen.getByRole("log").textContent).toContain("Understood - I will not send it."));
    expect(rejectRequest).toHaveBeenCalledTimes(1);
    expect(approveRequest).not.toHaveBeenCalled();
    expect(net.chatCalls).toEqual(["email bob the report"]);
    expect(box().disabled).toBe(false);
  });

  it("says so, and frees the box, when core did not restart the task", async () => {
    await parkedTurn();
    approveRequest.mockResolvedValue({ ...PENDING, status: "APPROVED", executionResult: "Email sent" });

    fireEvent.click(screen.getByTestId("tool-approval-approve"));
    expect(await screen.findByTestId("approval-continuation-notStarted")).toBeTruthy();
    expect(box().disabled).toBe(false);
    expect(screen.queryByTestId("approval-continuing")).toBeNull();
  });
});
