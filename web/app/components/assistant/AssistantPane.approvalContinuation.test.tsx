// The Assistant pane: a parked tool call on the thread is decided on its card - with NO folder attached (the
// case the old browser-side "carry on" message skipped). Core carries the task on by itself (it stores a
// platform-written "<approval_result ...>" message and runs a turn). The pane must follow that turn: say the
// agent is carrying on, hold the box, append the agent's reply once it is stored, send nothing of its own, and
// keep the platform's message out of the thread the person sees.

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const net = vi.hoisted(() => ({
  transcript: [] as Array<{ role: string; content: string }>,
  pending: [] as unknown[],
  turns: [] as string[],
}));
const approveRequest = vi.hoisted(() => vi.fn());
const rejectRequest = vi.hoisted(() => vi.fn());
const getChatTranscript = vi.hoisted(() => vi.fn());

const REQUEST_ID = "req-1";
const AGENT = { id: "asst-1", name: "Assistant", providerName: "OPENROUTER", model: "m" };
const PENDING = {
  id: REQUEST_ID,
  actionType: "mcp_tool.send_email",
  summary: "Tool call: send_email",
  status: "PENDING",
  requestedBy: "agent:asst-1:session:session-1",
  payload: JSON.stringify({ replay: { version: 1 }, arguments: { to: "bob@example.com" } }),
  createdAt: "2026-10-03T10:00:00Z",
};
const marker = (outcome: string) =>
  `<approval_result request="${REQUEST_ID}" tool="send_email" outcome="${outcome}">\nplatform text\n</approval_result>`;

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => (vars ? `${key}:${JSON.stringify(vars)}` : key),
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("next/link", () => ({
  default: ({ children, href, ...rest }: { children: React.ReactNode; href: string }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));
vi.mock("../RouteTransition", () => ({ usePageReady: vi.fn() }));
vi.mock("./useAssistantAgent", () => ({ useAssistantAgent: () => ({ status: "ready", agent: AGENT, created: false, retry: vi.fn() }) }));
vi.mock("../../lib/useWarmLocalModel", () => ({ useWarmLocalModel: vi.fn() }));
vi.mock("../../lib/platformBusy", () => ({ usePlatformBusyFlag: vi.fn() }));
vi.mock("../agents/approvalContinuation", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../agents/approvalContinuation")>()),
  CONTINUATION_POLL_MS: 10,
  CONTINUATION_START_WAIT_MS: 400,
}));
// The network boundary: every call answers "nothing" unless this test says otherwise.
const quiet = (overrides: Record<string, unknown>) =>
  new Proxy(overrides, {
    get: (target, key: string) =>
      key in target ? target[key] : vi.fn(async () => (key.startsWith("list") ? [] : null)),
  });
vi.mock("../../lib/api/assistant", () => ({
  assistantApi: quiet({
    listAgents: async () => [AGENT],
    getChatTranscript,
    streamAssistantTurn: async (
      _agent: string,
      message: string,
      _session: string | null,
      onChunk: (c: string) => void
    ) => {
      net.turns.push(message);
      onChunk("I need approval to send that. Waiting.");
      return "session-1";
    },
    listThreads: async () => ({ threads: [] as unknown[], nextBefore: null as string | null }),
  }),
}));
vi.mock("../../lib/api/memory", () => ({ memoryApi: quiet({}) }));
vi.mock("../../lib/api", () => ({
  api: quiet({
    listApprovals: async (): Promise<unknown[]> => net.pending,
    getApproval: async (): Promise<null> => null,
    approveRequest,
    rejectRequest,
    getAgentSessionLlmRequests: async (): Promise<unknown[]> => [],
    getAgentSessionLlmUsage: async (): Promise<unknown[]> => [],
  }),
}));

beforeAll(() => {
  Element.prototype.scrollTo = vi.fn();
  Element.prototype.scrollIntoView = vi.fn();
});
beforeEach(() => {
  window.localStorage.clear();
  net.transcript = [];
  net.pending = [];
  net.turns.length = 0;
  approveRequest.mockReset();
  rejectRequest.mockReset();
  getChatTranscript.mockReset();
  getChatTranscript.mockImplementation(async () => ({ sessionId: "session-1", messages: net.transcript }));
});
afterEach(() => cleanup());

const box = () => document.querySelector("textarea, input[type='text']") as HTMLTextAreaElement | HTMLInputElement;

async function parkedTurn() {
  const { default: AssistantPane } = await import("./AssistantPane");
  render(<AssistantPane />);
  net.pending = [PENDING];
  await waitFor(() => expect(box()).toBeTruthy());
  fireEvent.change(box(), { target: { value: "email bob the report" } });
  await act(async () => {
    fireEvent.keyDown(box(), { key: "Enter" });
  });
  await screen.findByTestId(`tool-approval-${REQUEST_ID}`);
  net.transcript = [
    { role: "user", content: "email bob the report" },
    { role: "assistant", content: "I need approval to send that. Waiting." },
  ];
}

describe("AssistantPane - the thread continues after a tool approval is decided (no folder attached)", () => {
  it("Approve: shows the agent carrying on, holds the box, appends its reply once, sends nothing itself", async () => {
    await parkedTurn();
    approveRequest.mockImplementation(async () => {
      net.transcript = [...net.transcript, { role: "user", content: marker("completed") }];
      return { ...PENDING, status: "APPROVED", executionResult: "Email sent" };
    });

    fireEvent.click(screen.getByTestId("tool-approval-approve"));
    expect(await screen.findByTestId("approval-continuing")).toBeTruthy();
    expect(box().disabled).toBe(true);
    expect(screen.queryByText(/platform text/)).toBeNull();

    net.transcript = [...net.transcript, { role: "assistant", content: "Done - bob has the report." }];
    expect(await screen.findByText("Done - bob has the report.")).toBeTruthy();
    await waitFor(() => expect(screen.queryByTestId("approval-continuing")).toBeNull());
    expect(box().disabled).toBe(false);

    expect(approveRequest).toHaveBeenCalledTimes(1);
    expect(net.turns).toEqual(["email bob the report"]);
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
    expect(net.turns).toEqual(["email bob the report"]);
    expect(box().disabled).toBe(false);
  });

  it("says so, and frees the box, when core did not restart the task", async () => {
    await parkedTurn();
    approveRequest.mockResolvedValue({ ...PENDING, status: "APPROVED", executionResult: "Email sent" });

    fireEvent.click(screen.getByTestId("tool-approval-approve"));
    expect(await screen.findByTestId("approval-continuation-notStarted")).toBeTruthy();
    expect(box().disabled).toBe(false);
  });
});
