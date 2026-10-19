// GuardrailActivity: the "In the last 7 days: blocked N, sent for approval M, redacted K" line on the hooks and
// tool-policy cards. Numbers come only from GET /api/audit-log/counts; nothing is shown that the server did
// not return, and no request is made without the audit permission (a 403 there is itself audit-logged).

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
  },
}));

const auth = vi.hoisted(() => ({ isAdmin: false, permissions: [] as string[] }));
vi.mock("../AuthProvider", () => ({
  useAuth: () => ({ isAdmin: auth.isAdmin, hasPermission: (name: string) => auth.permissions.includes(name) }),
}));

const mockApi = vi.hoisted(() => ({ getAuditLogCounts: vi.fn() }));
vi.mock("../../lib/api", () => ({ api: mockApi }));

import GuardrailActivity from "./GuardrailActivity";

function counts(byOutcome: Record<string, number>, opts: Record<string, unknown> = {}) {
  const full = { SUCCESS: 0, FAILURE: 0, PENDING_APPROVAL: 0, AUTO_APPROVED: 0, APPROVED: 0, REJECTED: 0, ...byOutcome };
  return {
    actionType: "x",
    from: "2026-09-28T12:00:00Z",
    to: "2026-10-05T12:00:00Z",
    total: Object.values(full).reduce((a, b) => a + b, 0),
    byOutcome: full,
    ...opts,
  };
}

function noRawKeys(container: HTMLElement) {
  expect(container.innerHTML).not.toMatch(/guardrailActivity\.|charts\.|NaN|\{\w+\}/);
}

describe("GuardrailActivity", () => {
  beforeEach(() => {
    mockApi.getAuditLogCounts.mockReset();
    auth.isAdmin = false;
    auth.permissions = ["VIEW_AUDIT_LOG"];
  });

  it("makes no request and renders nothing without the audit permission", async () => {
    auth.permissions = [];
    const { container } = render(<GuardrailActivity kind="hooks" agentId="a1" />);
    await Promise.resolve();
    expect(mockApi.getAuditLogCounts).not.toHaveBeenCalled();
    expect(container).toBeEmptyDOMElement();
  });

  it("hooks: counts this agent's hook decisions, and redactions by detail, over one shared 7 day window", async () => {
    mockApi.getAuditLogCounts.mockImplementation((opts: { detailContains?: string }) =>
      Promise.resolve(opts.detailContains ? counts({ SUCCESS: 4 }) : counts({ SUCCESS: 30, REJECTED: 5, PENDING_APPROVAL: 2 }))
    );
    const { container } = render(<GuardrailActivity kind="hooks" agentId="a1" />);
    const block = await screen.findByTestId("guardrail-activity-hooks");

    expect(mockApi.getAuditLogCounts).toHaveBeenCalledTimes(2);
    const [first, second] = mockApi.getAuditLogCounts.mock.calls.map((c) => c[0]);
    expect(first).toMatchObject({ actionType: "agent.hook.decision", targetId: "a1" });
    expect(first.detailContains).toBeUndefined();
    expect(second).toMatchObject({ actionType: "agent.hook.decision", targetId: "a1", detailContains: "decision=redacted" });
    expect(second.from).toBe(first.from);
    expect(second.to).toBe(first.to);
    expect(Date.parse(first.to) - Date.parse(first.from)).toBe(7 * 86_400_000);

    const sentence = "In the last 7 days: blocked 5, sent for approval 2, redacted 4.";
    expect(within(block).getByText(sentence)).toBeInTheDocument();
    expect(within(block).getByRole("img")).toHaveAttribute("aria-label", sentence);
    // SUCCESS rows that were plain log lines are not presented as anything
    expect(block.textContent).not.toContain("30");
    noRawKeys(container);

    fireEvent.click(within(block).getByRole("button", { name: "View as table" }));
    const rows = within(screen.getByRole("table")).getAllByRole("row");
    expect(rows.map((r) => r.textContent)).toEqual([
      "What happenedTimes",
      "Blocked or rate-limited5",
      "Sent for approval2",
      "Redacted4",
      "Hook check failed0",
    ]);
  });

  it("hooks: mentions failed hook checks only when there are some", async () => {
    mockApi.getAuditLogCounts.mockImplementation((opts: { detailContains?: string }) =>
      Promise.resolve(opts.detailContains ? counts({}) : counts({ REJECTED: 1, FAILURE: 3 }))
    );
    render(<GuardrailActivity kind="hooks" agentId="a1" />);
    const block = await screen.findByTestId("guardrail-activity-hooks");
    expect(block.textContent).toContain("In the last 7 days: blocked 1, sent for approval 0, redacted 0. Hook checks that failed: 3.");
  });

  it("hooks: zero activity is a calm sentence with no chart", async () => {
    mockApi.getAuditLogCounts.mockResolvedValue(counts({ SUCCESS: 12 }, { total: 0 }));
    const { container } = render(<GuardrailActivity kind="hooks" agentId="a1" />);
    const block = await screen.findByTestId("guardrail-activity-hooks");
    expect(block.textContent).toContain("In the last 7 days no hook blocked, paused or redacted anything for this agent.");
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    noRawKeys(container);
  });

  it("tool policy: counts refusals by the agent actor id, in one call", async () => {
    mockApi.getAuditLogCounts.mockResolvedValue(counts({ REJECTED: 6 }));
    const { container } = render(<GuardrailActivity kind="toolPolicy" agentId="a1" />);
    const block = await screen.findByTestId("guardrail-activity-toolPolicy");
    expect(mockApi.getAuditLogCounts).toHaveBeenCalledTimes(1);
    expect(mockApi.getAuditLogCounts.mock.calls[0][0]).toMatchObject({ actionType: "tool.arg_policy.denied", actorId: "agent:a1" });
    expect(mockApi.getAuditLogCounts.mock.calls[0][0].targetId).toBeUndefined();
    expect(block.textContent).toContain("In the last 7 days, tool calls refused by these policies: 6.");
    noRawKeys(container);
  });

  it("tool policy: zero refusals says so", async () => {
    mockApi.getAuditLogCounts.mockResolvedValue(counts({}));
    render(<GuardrailActivity kind="toolPolicy" agentId="a1" />);
    const block = await screen.findByTestId("guardrail-activity-toolPolicy");
    expect(block.textContent).toContain("In the last 7 days no tool call was refused by these policies.");
  });

  it("renders nothing when the endpoint fails or answers something else (older core)", async () => {
    mockApi.getAuditLogCounts.mockRejectedValue(new Error("404"));
    const first = render(<GuardrailActivity kind="hooks" agentId="a1" />);
    await waitFor(() => expect(mockApi.getAuditLogCounts).toHaveBeenCalled());
    await Promise.resolve();
    expect(first.container).toBeEmptyDOMElement();
    first.unmount();

    mockApi.getAuditLogCounts.mockReset();
    mockApi.getAuditLogCounts.mockResolvedValue([]);
    const second = render(<GuardrailActivity kind="toolPolicy" agentId="a1" />);
    await waitFor(() => expect(mockApi.getAuditLogCounts).toHaveBeenCalled());
    await Promise.resolve();
    expect(second.container).toBeEmptyDOMElement();
  });

  it("an admin without the granular permission still sees it", async () => {
    auth.permissions = [];
    auth.isAdmin = true;
    mockApi.getAuditLogCounts.mockResolvedValue(counts({ REJECTED: 1 }));
    render(<GuardrailActivity kind="toolPolicy" agentId="a1" />);
    expect(await screen.findByTestId("guardrail-activity-toolPolicy")).toBeInTheDocument();
  });
});
