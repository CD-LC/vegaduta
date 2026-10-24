// Accessibility (axe) of the approvals queue in the states a person meets: a request with its tool-call preview and
// expiry countdown, a bulk selection with its confirmation dialog, the tool-rule form with its risk confirmation, the
// autonomy-tier explainer and the empty queue. Same client-boundary mocks as ApprovalsQueue.test.tsx.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import en from "../../messages/en.json";
import { axeViolations } from "../lib/axeTestKit";

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

describe("approvals queue accessibility (axe)", () => {
  it("a pending tool call with its preview, deny reason and decision buttons", async () => {
    dataset = [toolRow("r1", "send_email"), toolRow("r2", "get_invoice")];
    render(<ApprovalsPanel isAdmin />);
    await screen.findByTestId("tool-call-preview");
    expect(await axeViolations()).toEqual([]);
  });

  it("the expiry countdown and the empty queue", async () => {
    getTtl.mockResolvedValue(3600);
    dataset = [toolRow("r1", "get_invoice", { createdAt: minutesAgo(55) })];
    const { unmount } = render(<ApprovalsPanel isAdmin />);
    await screen.findByTestId("tool-call-preview");
    expect(await axeViolations()).toEqual([]);
    unmount();
    dataset = [];
    render(<ApprovalsPanel isAdmin />);
    await screen.findByTestId("autonomy-tier-explainer");
    expect(await axeViolations()).toEqual([]);
  });

  it("a bulk selection and its confirmation dialog", async () => {
    dataset = [toolRow("r1", "get_invoice"), toolRow("r2", "get_invoice")];
    render(<ApprovalsPanel isAdmin />);
    await screen.findByTestId("tool-call-preview");
    screen.getAllByRole("checkbox", { name: "Select the pending call to get_invoice" }).forEach((b) => fireEvent.click(b));
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(screen.getByTestId("bulk-approve"));
    await screen.findByRole("alertdialog");
    expect(await axeViolations()).toEqual([]);
  });

  it("the tool-rule form, its inline error and its risk confirmation", async () => {
    dataset = [];
    render(<ApprovalsPanel isAdmin initialShowRules />);
    const input = await screen.findByLabelText("Tool name");
    fireEvent.change(input, { target: { value: "send email" } });
    expect(screen.getByTestId("rules-name-error")).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
    fireEvent.change(input, { target: { value: "mcp_tool.send_email" } });
    fireEvent.click(screen.getByTestId("rules-mode-never"));
    fireEvent.click(screen.getByTestId("rules-save"));
    await screen.findByRole("alertdialog");
    expect(await axeViolations()).toEqual([]);
  });
});
