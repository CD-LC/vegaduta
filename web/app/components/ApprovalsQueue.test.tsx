// The approvals queue experience, end to end at the client boundary: real components, the API mocked at the
// fetch/client layer. Covers the tool-call preview, deny reasons, filters, expiry, bulk decisions, continuation
// state, deep links, notifications, tool rules and the autonomy-tier explainer.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import en from "../../messages/en.json";
import { pickOption } from "./ui/test-select";

vi.mock("../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});

const approveRequest = vi.fn();
const rejectRequest = vi.fn();
const setApprovalPolicy = vi.fn();
const getApproval = vi.fn();
const streamChat = vi.fn();
vi.mock("../lib/api", () => ({
  api: {
    countApprovals: () => Promise.resolve(2),
    approveRequest: (...a: unknown[]) => approveRequest(...a),
    rejectRequest: (...a: unknown[]) => rejectRequest(...a),
    setApprovalPolicy: (...a: unknown[]) => setApprovalPolicy(...a),
    getApproval: (...a: unknown[]) => getApproval(...a),
    streamChat: (...a: unknown[]) => streamChat(...a),
  },
}));
const listApprovalsPage = vi.fn();
vi.mock("./approvalsServerPaging", () => ({
  listApprovalsPage: (...a: unknown[]) => listApprovalsPage(...a),
}));
const getTtl = vi.fn();
vi.mock("../lib/api/approvalsQueue", () => ({
  approvalsQueueApi: { getExpiryTtlSeconds: () => getTtl() },
}));
const getChatTranscript = vi.fn();
vi.mock("../lib/api/assistant", () => ({
  assistantApi: { getChatTranscript: (...a: unknown[]) => getChatTranscript(...a) },
}));
vi.mock("../lib/pulseBus", () => ({ firePulseAtElement: vi.fn() }));

import ApprovalsPanel from "./ApprovalsPanel";

const AGENT = "11111111-aaaa-bbbb-cccc-222222222222";
const minutesAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString();

function toolRow(id: string, tool: string, over: Record<string, unknown> = {}) {
  return {
    id,
    status: "PENDING",
    actionType: `mcp_tool.${tool}`,
    summary: `Tool call: ${tool}`,
    requestedBy: `agent:${AGENT}:session:sess-1`,
    createdAt: minutesAgo(10),
    payload: JSON.stringify({
      replay: { version: 1, toolName: tool, tenantId: "t-secret", goal: "Email the quarterly report" },
      arguments: { to: "boss@example.com", apiKey: "sk-live-999" },
    }),
    ...over,
  };
}

let dataset: Array<Record<string, unknown>> = [];
function installList() {
  listApprovalsPage.mockImplementation(async (status: string, page: number, size: number) => {
    const rows = dataset.filter((r) => !status || r.status === status);
    const start = (page - 1) * size;
    return { rows: rows.slice(start, start + size), total: rows.length, hasMore: start + size < rows.length };
  });
}

beforeEach(() => {
  approveRequest.mockReset().mockImplementation(async (id: string) => ({ ...dataset.find((r) => r.id === id), status: "APPROVED", decidedAt: new Date().toISOString() }));
  rejectRequest.mockReset().mockImplementation(async (id: string) => ({ ...dataset.find((r) => r.id === id), status: "REJECTED", decidedAt: new Date().toISOString() }));
  setApprovalPolicy.mockReset().mockResolvedValue(undefined);
  getApproval.mockReset();
  streamChat.mockReset().mockResolvedValue("sess-1");
  getTtl.mockReset().mockResolvedValue(null);
  getChatTranscript.mockReset().mockResolvedValue({ messages: [] });
  dataset = [];
  installList();
  window.localStorage.clear();
});
afterEach(() => cleanup());

describe("tool-call preview and decisions", () => {
  it("shows the tool, the task, redacted arguments, the risk and what approve and deny each do", async () => {
    dataset = [toolRow("r1", "send_email")];
    render(<ApprovalsPanel isAdmin />);
    const preview = await screen.findByTestId("tool-call-preview");
    expect(within(preview).getByTestId("tool-call-name")).toHaveTextContent("send_email");
    expect(within(preview).getByTestId("tool-call-goal")).toHaveTextContent("Email the quarterly report");
    const args = within(preview).getByTestId("tool-call-args");
    expect(args).toHaveTextContent("boss@example.com");
    expect(args.textContent).not.toContain("sk-live-999");
    expect(args.textContent).not.toContain("t-secret");
    expect(within(preview).getByTestId("tool-call-redacted")).toHaveTextContent("1 sensitive-looking value");
    expect(preview.querySelector('[data-risk="high"]')).not.toBeNull();
    const consequences = within(preview).getByTestId("tool-call-consequences");
    expect(consequences).toHaveTextContent("send_email runs right now");
    expect(consequences).toHaveTextContent("does not run");
  });

  it("sends the optional deny reason to the agent, and clears it when another request is selected", async () => {
    dataset = [toolRow("r1", "send_email"), toolRow("r2", "send_email", { createdAt: minutesAgo(5) })];
    render(<ApprovalsPanel isAdmin />);
    const reason = await screen.findByRole("textbox", { name: "Reason if you deny (optional)" });
    fireEvent.change(reason, { target: { value: "Wrong recipient" } });
    // Selecting the other request must not carry the typed reason over.
    const rows = screen.getAllByRole("row");
    fireEvent.click(rows[2]);
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Reason if you deny (optional)" })).toHaveValue(""));
    fireEvent.click(rows[1]);
    const again = await screen.findByRole("textbox", { name: "Reason if you deny (optional)" });
    fireEvent.change(again, { target: { value: "Wrong recipient" } });
    fireEvent.click(screen.getByRole("button", { name: "Deny" }));
    await waitFor(() => expect(rejectRequest).toHaveBeenCalledWith("r1", null, "Wrong recipient"));
  });

  it("denies without a reason as null", async () => {
    dataset = [toolRow("r1", "send_email")];
    render(<ApprovalsPanel isAdmin />);
    fireEvent.click(await screen.findByRole("button", { name: "Deny" }));
    await waitFor(() => expect(rejectRequest).toHaveBeenCalledWith("r1", null, null));
  });

  it("refuses a one-click approve when the arguments cannot be reviewed in full, but still allows deny", async () => {
    dataset = [
      toolRow("r1", "send_email", {
        payload: JSON.stringify({ replay: { version: 1 }, arguments: { body: "x".repeat(250_000) } }),
      }),
    ];
    render(<ApprovalsPanel isAdmin />);
    expect(await screen.findByTestId("approve-blocked-too-long")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Deny" })).toBeEnabled();
  });

  it("shows an API failure inline", async () => {
    dataset = [toolRow("r1", "get_invoice")];
    approveRequest.mockRejectedValueOnce(new Error("Replay failed"));
    render(<ApprovalsPanel isAdmin />);
    fireEvent.click(await screen.findByRole("button", { name: "Approve" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Replay failed");
  });
});

describe("filters", () => {
  it("reads a larger window when an agent filter is active and filters it locally", async () => {
    const other = "99999999-aaaa-bbbb-cccc-000000000000";
    dataset = [
      toolRow("r1", "send_email"),
      toolRow("r2", "get_invoice", { requestedBy: `agent:${other}:session:s9` }),
    ];
    render(<ApprovalsPanel isAdmin />);
    await screen.findByTestId("tool-call-preview");
    pickOption("Filter by agent", `${other.slice(0, 8)}…`);
    await waitFor(() => expect(listApprovalsPage).toHaveBeenCalledWith("PENDING", 1, 200));
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(2)); // header + one row
    expect(screen.getByTestId("tool-call-name")).toHaveTextContent("get_invoice");
  });

  it("filters by tool and by age, and clears", async () => {
    dataset = [
      toolRow("r1", "send_email", { createdAt: minutesAgo(5) }),
      toolRow("r2", "get_invoice", { createdAt: minutesAgo(60 * 30) }),
    ];
    render(<ApprovalsPanel isAdmin />);
    await screen.findByTestId("tool-call-preview");
    pickOption("Filter by age", "Older than 1 day");
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(2));
    expect(screen.getByTestId("tool-call-name")).toHaveTextContent("get_invoice");
    fireEvent.click(screen.getByTestId("approvals-filters-clear"));
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(3));
    pickOption("Filter by tool", "send_email");
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(2));
    expect(screen.getByTestId("tool-call-name")).toHaveTextContent("send_email");
  });

  it("lists expired requests separately from rejected ones", async () => {
    dataset = [
      toolRow("r1", "send_email", { status: "REJECTED", decidedBy: "system:approval-expiry", decidedAt: minutesAgo(1), rejectionReason: "No decision within 24 hours" }),
      toolRow("r2", "get_invoice", { status: "REJECTED", decidedBy: "user-1", decidedAt: minutesAgo(2), rejectionReason: "no" }),
    ];
    render(<ApprovalsPanel isAdmin />);
    await screen.findByText("Nothing to review"); // PENDING: empty state first
    fireEvent.click(screen.getByRole("button", { name: "EXPIRED" }));
    await waitFor(() => expect(listApprovalsPage).toHaveBeenCalledWith("REJECTED", 1, 200));
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(2));
    expect(screen.getByTestId("tool-call-name")).toHaveTextContent("send_email");
    expect(document.querySelector('[data-status="EXPIRED"]')).not.toBeNull();
    expect(screen.getAllByTestId("expiry-expired").length).toBeGreaterThan(0);
    expect(screen.getAllByRole("alert").map((a) => a.textContent).join(" ")).toContain("Expired: No decision within 24 hours");
  });
});

describe("expiry countdown", () => {
  it("counts down to the deadline when the workspace expires waiting requests", async () => {
    getTtl.mockResolvedValue(3600);
    dataset = [toolRow("r1", "send_email", { createdAt: minutesAgo(10) })];
    render(<ApprovalsPanel isAdmin />);
    const countdowns = await screen.findAllByTestId("expiry-countdown");
    expect(countdowns[0].textContent).toMatch(/Expires in (49|50)m/);
    expect(countdowns[0]).toHaveAttribute("data-urgent", "false");
  });

  it("marks the last minutes as urgent and an overdue request as past its limit", async () => {
    getTtl.mockResolvedValue(3600);
    dataset = [toolRow("r1", "send_email", { createdAt: minutesAgo(55) }), toolRow("r2", "get_invoice", { createdAt: minutesAgo(70) })];
    render(<ApprovalsPanel isAdmin />);
    await waitFor(() => expect(screen.getAllByTestId("expiry-countdown")[0]).toHaveAttribute("data-urgent", "true"));
    expect(screen.getAllByTestId("expiry-overdue").length).toBeGreaterThan(0);
  });

  it("shows no countdown when the TTL is unknown, and says so when requests never expire", async () => {
    dataset = [toolRow("r1", "send_email")];
    const { unmount } = render(<ApprovalsPanel isAdmin />);
    await screen.findByTestId("tool-call-preview");
    expect(screen.queryByTestId("expiry-countdown")).toBeNull();
    expect(screen.queryByTestId("expiry-never")).toBeNull();
    unmount();
    getTtl.mockResolvedValue(0);
    render(<ApprovalsPanel isAdmin />);
    expect(await screen.findByTestId("expiry-never")).toBeInTheDocument();
  });
});

const MARKER = (id: string) => `<approval_result request="${id}" tool="t" outcome="completed">\nx\n</approval_result>`;

describe("bulk decisions", () => {
  const boxes = (tool: string) => screen.getAllByRole("checkbox", { name: `Select the pending call to ${tool}` });

  it("approves several low-risk calls to one tool, only after a confirmation that names the tool and count", async () => {
    dataset = [toolRow("r1", "get_invoice"), toolRow("r2", "get_invoice"), toolRow("r3", "send_email")];
    render(<ApprovalsPanel isAdmin />);
    await screen.findByTestId("tool-call-preview");
    boxes("get_invoice").forEach((b) => fireEvent.click(b));
    expect(screen.getByText("2 selected")).toBeInTheDocument();
    expect(screen.getByTestId("bulk-approve")).toBeEnabled();
    fireEvent.click(screen.getByTestId("bulk-approve"));
    expect(approveRequest).not.toHaveBeenCalled();
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent("Approve 2 calls to get_invoice?");
    expect(within(dialog).getByTestId("bulk-confirm-list").children).toHaveLength(2);
    fireEvent.click(within(dialog).getByTestId("bulk-confirm"));
    await waitFor(() => expect(approveRequest).toHaveBeenCalledTimes(2));
    expect(approveRequest.mock.calls.map((c) => c[0]).sort()).toEqual(["r1", "r2"]);
    expect(await screen.findByTestId("bulk-result")).toHaveTextContent("2 decided, 0 failed.");
  });

  it("does not offer a bulk approve for a risky tool, or for a mix of tools, but allows deny for one tool", async () => {
    dataset = [toolRow("r1", "send_email"), toolRow("r2", "send_email"), toolRow("r3", "get_invoice")];
    render(<ApprovalsPanel isAdmin />);
    await screen.findByTestId("tool-call-preview");
    boxes("send_email").forEach((b) => fireEvent.click(b));
    expect(screen.getByTestId("bulk-approve")).toBeDisabled();
    expect(screen.getByTestId("bulk-deny")).toBeEnabled();
    expect(screen.getByTestId("bulk-hint")).toHaveTextContent("only for low-risk tools");

    fireEvent.click(boxes("get_invoice")[0]);
    expect(screen.getByTestId("bulk-deny")).toBeDisabled();
    expect(screen.getByTestId("bulk-hint")).toHaveTextContent("same tool");
    fireEvent.click(boxes("get_invoice")[0]);

    fireEvent.click(screen.getByTestId("bulk-deny"));
    const dialog = await screen.findByRole("alertdialog");
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Reason if you deny (optional)" }), { target: { value: "Hold all mail" } });
    fireEvent.click(within(dialog).getByTestId("bulk-confirm"));
    await waitFor(() => expect(rejectRequest).toHaveBeenCalledTimes(2));
    expect(rejectRequest).toHaveBeenCalledWith("r1", null, "Hold all mail");
    expect(rejectRequest).toHaveBeenCalledWith("r2", null, "Hold all mail");
    expect(approveRequest).not.toHaveBeenCalled();
  });

  it("reports which calls failed and keeps going", async () => {
    dataset = [toolRow("r1", "get_invoice"), toolRow("r2", "get_invoice")];
    approveRequest.mockRejectedValueOnce(new Error("Tool no longer resolvable"));
    render(<ApprovalsPanel isAdmin />);
    await screen.findByTestId("tool-call-preview");
    boxes("get_invoice").forEach((b) => fireEvent.click(b));
    fireEvent.click(screen.getByTestId("bulk-approve"));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByTestId("bulk-confirm"));
    const result = await screen.findByTestId("bulk-result");
    expect(result).toHaveTextContent("1 decided, 1 failed.");
    expect(result).toHaveTextContent("Tool no longer resolvable");
    expect(approveRequest).toHaveBeenCalledTimes(2);
  });

  it("only offers ticks on pending tool calls", async () => {
    dataset = [{ id: "c1", status: "PENDING", actionType: "channel.slack.send", summary: "Send hello", requestedBy: "agent:a1", createdAt: minutesAgo(1) }];
    render(<ApprovalsPanel isAdmin />);
    await screen.findByRole("table");
    expect(screen.queryAllByRole("checkbox", { name: /Select the pending call/ })).toHaveLength(0);
  });
});

describe("what the agent did next", () => {
  it("follows the agent after a decision made in the queue: carrying on, then finished", async () => {
    dataset = [toolRow("r1", "get_invoice")];
    getChatTranscript.mockResolvedValue({ messages: [{ role: "user", content: MARKER("r1") }] });
    render(<ApprovalsPanel isAdmin />);
    fireEvent.click(await screen.findByRole("button", { name: "Approve" }));
    const card = await screen.findByTestId("last-decision");
    expect(card).toHaveTextContent("Your last decision: get_invoice");
    await waitFor(() => expect(within(card).getByTestId("approval-continuing")).toBeInTheDocument());
    expect(getChatTranscript).toHaveBeenCalledWith(AGENT, "sess-1");
    getChatTranscript.mockResolvedValue({
      messages: [{ role: "user", content: MARKER("r1") }, { role: "assistant", content: "Found it." }],
    });
    await waitFor(() => expect(within(card).getByTestId("approval-continuation-completed")).toBeInTheDocument(), { timeout: 4000 });
  });

  it("says when the agent failed to carry on and retries by asking it to carry on in the same conversation", async () => {
    dataset = [toolRow("r1", "get_invoice")];
    getChatTranscript.mockResolvedValue({
      messages: [{ role: "user", content: MARKER("r1") }, { role: "assistant", content: "[reply failed] Provider unavailable" }],
    });
    render(<ApprovalsPanel isAdmin />);
    fireEvent.click(await screen.findByRole("button", { name: "Approve" }));
    const card = await screen.findByTestId("last-decision");
    const failed = await within(card).findByTestId("approval-continuation-failed");
    expect(failed).toHaveTextContent("could not carry on");
    fireEvent.click(within(card).getByTestId("approval-continuation-retry"));
    await waitFor(() => expect(streamChat).toHaveBeenCalledWith(AGENT, "Please carry on with the task.", "sess-1", expect.any(Function)));
    await waitFor(() => expect(within(card).queryByTestId("approval-continuation-failed")).toBeNull());
  });

  it("links a request to its agent and turns", async () => {
    dataset = [toolRow("r1", "get_invoice")];
    render(<ApprovalsPanel isAdmin />);
    await screen.findByTestId("tool-call-preview");
    expect(screen.getByTestId("approval-link-agent")).toHaveAttribute("href", `/playground?agent=${AGENT}`);
    expect(screen.getByTestId("approval-link-turns")).toHaveAttribute("href", `/agents/turns?agentId=${AGENT}`);
    expect(screen.getByTestId("approval-links")).toHaveTextContent("sess-1");
  });
});

describe("deep links", () => {
  it("opens a request that is not on the first page", async () => {
    dataset = [toolRow("r1", "get_invoice")];
    getApproval.mockResolvedValue(toolRow("old-1", "send_email", { status: "APPROVED", decidedAt: minutesAgo(3) }));
    render(<ApprovalsPanel isAdmin initialRequestId="old-1" />);
    await waitFor(() => expect(screen.getByTestId("tool-call-name")).toHaveTextContent("send_email"));
    expect(getApproval).toHaveBeenCalledWith("old-1", [403, 404], true);
    // A decided request lives under "All", so the status list follows it.
    expect(screen.getByRole("button", { name: "ALL" })).toHaveAttribute("aria-pressed", "true");
  });

  it("says so when the request cannot be found", async () => {
    getApproval.mockRejectedValue(new Error("404"));
    render(<ApprovalsPanel isAdmin initialRequestId="nope" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("could not be found");
  });

  it("applies filters and status from the link", async () => {
    dataset = [toolRow("r1", "get_invoice"), toolRow("r2", "send_email")];
    render(<ApprovalsPanel isAdmin initialFilters={{ tool: "send_email" }} />);
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(2));
    expect(screen.getByTestId("tool-call-name")).toHaveTextContent("send_email");
  });
});

describe("notifications", () => {
  class FakeNotification {
    static permission = "default";
    static requestPermission = vi.fn(async () => {
      FakeNotification.permission = "granted";
      return "granted";
    });
    static instances: FakeNotification[] = [];
    onclick: (() => void) | null = null;
    close = vi.fn();
    constructor(public title: string, public options?: { body?: string; tag?: string }) {
      FakeNotification.instances.push(this);
    }
  }
  let hidden = true;
  beforeEach(() => {
    window.focus = vi.fn();
    FakeNotification.permission = "default";
    FakeNotification.instances = [];
    FakeNotification.requestPermission.mockClear();
    (window as unknown as { Notification: unknown }).Notification = FakeNotification;
    (globalThis as unknown as { Notification: unknown }).Notification = FakeNotification;
    hidden = true;
    Object.defineProperty(document, "hidden", { configurable: true, get: () => hidden });
  });
  afterEach(() => {
    delete (window as unknown as { Notification?: unknown }).Notification;
    delete (globalThis as unknown as { Notification?: unknown }).Notification;
    Object.defineProperty(document, "hidden", { configurable: true, get: () => false });
  });

  it("asks permission only when the person opts in, and remembers the choice", async () => {
    dataset = [toolRow("r1", "get_invoice")];
    render(<ApprovalsPanel isAdmin />);
    await screen.findByTestId("tool-call-preview");
    expect(FakeNotification.requestPermission).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByTestId("notify-enable"));
    await waitFor(() => expect(FakeNotification.requestPermission).toHaveBeenCalledTimes(1));
    expect(await screen.findByTestId("notify-disable")).toHaveAttribute("aria-pressed", "true");
    expect(window.localStorage.getItem("vegaduta.approvals.notify")).toBe("1");
    fireEvent.click(screen.getByTestId("notify-disable"));
    expect(await screen.findByTestId("notify-enable")).toBeInTheDocument();
    expect(window.localStorage.getItem("vegaduta.approvals.notify")).toBeNull();
  });

  it("explains a blocked browser and an unsupported one instead of offering a dead button", async () => {
    FakeNotification.permission = "denied";
    dataset = [];
    const { unmount } = render(<ApprovalsPanel isAdmin />);
    expect(await screen.findByTestId("notify-blocked")).toBeInTheDocument();
    unmount();
    delete (window as unknown as { Notification?: unknown }).Notification;
    delete (globalThis as unknown as { Notification?: unknown }).Notification;
    render(<ApprovalsPanel isAdmin />);
    expect(await screen.findByTestId("notify-unsupported")).toBeInTheDocument();
  });

  it("raises one notification for a new request while the tab is in the background, and its click opens the request", async () => {
    FakeNotification.permission = "granted";
    window.localStorage.setItem("vegaduta.approvals.notify", "1");
    dataset = [toolRow("r1", "get_invoice")];
    getApproval.mockImplementation(async (id: string) => dataset.find((r) => r.id === id));
    render(<ApprovalsPanel isAdmin notifyPollMs={20} />);
    await screen.findByTestId("tool-call-preview");
    await new Promise((r) => setTimeout(r, 60)); // the first poll only seeds what is already waiting
    expect(FakeNotification.instances).toHaveLength(0);
    dataset = [...dataset, toolRow("r2", "send_email")];
    await waitFor(() => expect(FakeNotification.instances).toHaveLength(1));
    const n = FakeNotification.instances[0];
    expect(n.title).toBe("An agent is waiting for approval");
    expect(n.options?.body).toBe("send_email needs your decision.");
    await new Promise((r) => setTimeout(r, 80));
    expect(FakeNotification.instances).toHaveLength(1);
    act(() => n.onclick?.());
    await waitFor(() => expect(getApproval).toHaveBeenCalledWith("r2", [403, 404], true));
    await waitFor(() => expect(screen.getByTestId("tool-call-name")).toHaveTextContent("send_email"));
    expect(screen.getByTestId("approvals-live")).toHaveTextContent("1 new approval request");
  });

  it("stays quiet when the tab is in front, or the person did not opt in", async () => {
    FakeNotification.permission = "granted";
    dataset = [toolRow("r1", "get_invoice")];
    render(<ApprovalsPanel isAdmin notifyPollMs={20} />);
    await screen.findByTestId("tool-call-preview");
    await new Promise((r) => setTimeout(r, 40));
    dataset = [...dataset, toolRow("r2", "send_email")];
    await new Promise((r) => setTimeout(r, 120));
    expect(FakeNotification.instances).toHaveLength(0); // not opted in
  });
});

describe("tool rules and the autonomy tiers", () => {
  it("explains the three autonomy levels in plain language", async () => {
    dataset = [];
    render(<ApprovalsPanel isAdmin />);
    const explainer = await screen.findByTestId("autonomy-tier-explainer");
    expect(within(explainer).getByTestId("tier-card-supervised")).toHaveTextContent("Every tool call waits for a person");
    expect(within(explainer).getByTestId("tier-card-semi_autonomous")).toHaveTextContent("Your per-tool rules take effect here");
    expect(within(explainer).getByTestId("tier-card-fully_autonomous")).toHaveTextContent("It acts without asking");
  });

  it("saves an always-ask rule for a tool in one step", async () => {
    dataset = [];
    render(<ApprovalsPanel isAdmin initialShowRules />);
    fireEvent.change(await screen.findByLabelText("Tool name"), { target: { value: "send_email" } });
    fireEvent.click(screen.getByTestId("rules-save"));
    await waitFor(() => expect(setApprovalPolicy).toHaveBeenCalledWith("mcp_tool.send_email", true));
    expect(await screen.findByTestId("rules-ok")).toHaveTextContent("send_email will always ask for approval.");
  });

  it("asks for a confirmation that explains the consequence before a tool stops asking", async () => {
    dataset = [];
    render(<ApprovalsPanel isAdmin initialShowRules />);
    fireEvent.change(await screen.findByLabelText("Tool name"), { target: { value: "mcp_tool.send_email" } });
    fireEvent.click(screen.getByTestId("rules-mode-never"));
    expect(screen.getByTestId("rules-risk")).toHaveTextContent("looks like it changes things");
    fireEvent.click(screen.getByTestId("rules-save"));
    expect(setApprovalPolicy).not.toHaveBeenCalled();
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent("run send_email without anyone approving it");
    fireEvent.click(within(dialog).getByRole("button", { name: "Stop asking" }));
    await waitFor(() => expect(setApprovalPolicy).toHaveBeenCalledWith("mcp_tool.send_email", false));
  });

  it("surfaces a refused save and rejects a tool name with spaces", async () => {
    dataset = [];
    setApprovalPolicy.mockRejectedValueOnce(new Error("Requires MANAGE_APPROVALS"));
    render(<ApprovalsPanel isAdmin initialShowRules />);
    const input = await screen.findByLabelText("Tool name");
    fireEvent.change(input, { target: { value: "send email" } });
    expect(screen.getByTestId("rules-name-error")).toBeInTheDocument();
    expect(screen.getByTestId("rules-save")).toBeDisabled();
    fireEvent.change(input, { target: { value: "get_invoice" } });
    fireEvent.click(screen.getByTestId("rules-save"));
    expect(await screen.findByTestId("rules-error")).toHaveTextContent("Requires MANAGE_APPROVALS");
  });

  it("is read-only without the approvals permission", async () => {
    dataset = [];
    render(<ApprovalsPanel isAdmin={false} initialShowRules />);
    expect(await screen.findByLabelText("Tool name")).toBeDisabled();
    expect(screen.getByTestId("rules-save")).toBeDisabled();
  });
});
