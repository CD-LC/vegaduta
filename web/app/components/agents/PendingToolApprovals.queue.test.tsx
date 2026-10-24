// The inline tool-confirmation card's queue-facing additions: risk, redaction, the optional deny reason the
// agent reads, the expiry countdown and the deep link into the approvals queue.
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import en from "../../../messages/en.json";

const listApprovals = vi.fn();
const getApproval = vi.fn();
const approveRequest = vi.fn();
const rejectRequest = vi.fn();
vi.mock("../../lib/api", () => ({
  api: {
    get listApprovals() { return listApprovals; },
    get getApproval() { return getApproval; },
    get approveRequest() { return approveRequest; },
    get rejectRequest() { return rejectRequest; },
  },
}));
const getTtl = vi.fn();
vi.mock("../../lib/api/approvalsQueue", () => ({ approvalsQueueApi: { getExpiryTtlSeconds: () => getTtl() } }));
vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});
vi.mock("next/link", () => ({
  default: ({ children, href, ...rest }: { children: ReactNode; href: string }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import PendingToolApprovals from "./PendingToolApprovals";

const row = (over: Record<string, unknown> = {}) => ({
  id: "req-1",
  actionType: "mcp_tool.send_email",
  summary: "Tool call: send_email",
  status: "PENDING",
  requestedBy: "agent:a1:session:s1",
  payload: JSON.stringify({ replay: { version: 1, tenantId: "t-secret" }, arguments: { to: "x@example.com", password: "hunter2" } }),
  createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
  ...over,
});

beforeEach(() => {
  listApprovals.mockReset().mockResolvedValue([row()]);
  getApproval.mockReset();
  approveRequest.mockReset();
  rejectRequest.mockReset().mockResolvedValue(row({ status: "REJECTED" }));
  getTtl.mockReset().mockResolvedValue(null);
});
afterEach(() => cleanup());

describe("PendingToolApprovals (queue additions)", () => {
  it("hides credential-looking arguments and says how many were hidden", async () => {
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    const args = await screen.findByTestId("tool-approval-args");
    expect(args).toHaveTextContent("x@example.com");
    expect(args.textContent).not.toContain("hunter2");
    expect(args.textContent).not.toContain("t-secret");
    expect(screen.getByTestId("tool-approval-redacted")).toHaveTextContent("1 sensitive-looking value");
  });

  it("shows the estimated risk of the tool", async () => {
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    await screen.findByTestId("tool-approval-req-1");
    expect(document.querySelector('[data-risk="high"]')).not.toBeNull();
  });

  it("sends the typed reason when rejecting, and nothing when none is typed", async () => {
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-reason-toggle"));
    const reason = await screen.findByLabelText("Reason if you deny (optional)");
    expect(screen.getByTestId("tool-approval-reason-toggle")).toHaveAttribute("aria-expanded", "true");
    expect(reason).toHaveFocus();
    fireEvent.change(reason, { target: { value: "Ask me first" } });
    fireEvent.click(screen.getByTestId("tool-approval-reject"));
    await waitFor(() => expect(rejectRequest).toHaveBeenCalled());
    expect(rejectRequest.mock.calls[0][0]).toBe("req-1");
    expect(rejectRequest.mock.calls[0][2]).toBe("Ask me first");
  });

  it("links the card to its request in the queue", async () => {
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    expect(await screen.findByTestId("tool-approval-queue-link")).toHaveAttribute("href", "/admin/approvals?id=req-1");
  });

  it("counts down when the workspace expires waiting requests", async () => {
    getTtl.mockResolvedValue(3600);
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    const countdown = await screen.findByTestId("expiry-countdown");
    expect(countdown.textContent).toMatch(/Expires in (54|55)m/);
  });
});
