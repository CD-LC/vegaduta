// Several tool calls parked in one turn are decided one at a time, in one panel: only the oldest waiting call
// shows its buttons, the panel says how many wait behind it, and a decided call stays below as a receipt.

import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

const listApprovals = vi.fn();
const getApproval = vi.fn();
const approveRequest = vi.fn();
const rejectRequest = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get listApprovals() {
      return listApprovals;
    },
    get getApproval() {
      return getApproval;
    },
    get approveRequest() {
      return approveRequest;
    },
    get rejectRequest() {
      return rejectRequest;
    },
  },
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, string | number>) =>
    v ? `${key}:${Object.values(v).join(",")}` : key,
}));

vi.mock("next/link", () => ({
  default: ({ children, href, ...rest }: { children: ReactNode; href: string }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import PendingToolApprovals from "./PendingToolApprovals";

function parked(id: string, tool: string, minute: number) {
  return {
    id,
    actionType: `mcp_tool.${tool}`,
    summary: `Tool call: ${tool}`,
    status: "PENDING",
    requestedBy: "agent:a1:session:s1",
    payload: JSON.stringify({ arguments: { n: id } }),
    createdAt: `2026-10-23T10:0${minute}:00Z`,
  };
}
const FIRST = parked("req-1", "send_email", 1);
const SECOND = parked("req-2", "create_event", 2);
const THIRD = parked("req-3", "post_message", 3);

beforeEach(() => {
  listApprovals.mockReset();
  getApproval.mockReset();
  approveRequest.mockReset();
  rejectRequest.mockReset();
  listApprovals.mockResolvedValue([FIRST, SECOND, THIRD]);
});

describe("PendingToolApprovals - one decision at a time", () => {
  it("shows only the oldest waiting call with its buttons, in a single panel", async () => {
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    const current = await screen.findByTestId("tool-approval-current");

    expect(within(current).getByTestId("tool-approval-req-1")).toBeTruthy();
    expect(screen.queryByTestId("tool-approval-req-2")).toBeNull();
    expect(screen.queryByTestId("tool-approval-req-3")).toBeNull();
    expect(screen.getAllByTestId("tool-approval-approve")).toHaveLength(1);
    expect(screen.getAllByTestId("pending-tool-approvals")).toHaveLength(1);
    expect(screen.getByTestId("tool-approval-queue-status").textContent).toContain("queuePosition:1,3");
    expect(screen.getByTestId("tool-approval-queue-status").textContent).toContain("queueMore:2");
  });

  it("moves to the next call once this one is decided, and keeps the decision as a receipt", async () => {
    rejectRequest.mockResolvedValue({ ...FIRST, status: "REJECTED" });
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-reject"));

    const current = await screen.findByTestId("tool-approval-current");
    expect(await within(current).findByTestId("tool-approval-req-2")).toBeTruthy();
    expect(screen.getByTestId("tool-approval-queue-status").textContent).toContain("queuePosition:1,2");
    // The rejected call is still visible, outside the current slot, with its outcome.
    const receipt = screen.getByTestId("tool-approval-req-1");
    expect(within(current).queryByTestId("tool-approval-req-1")).toBeNull();
    expect(within(receipt).getByTestId("tool-approval-outcome-rejected")).toBeTruthy();
    expect(screen.getAllByTestId("tool-approval-approve")).toHaveLength(1);
  });

  it("drops the queue line when only one call is waiting", async () => {
    listApprovals.mockResolvedValue([FIRST]);
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    await screen.findByTestId("tool-approval-req-1");
    expect(screen.queryByTestId("tool-approval-queue-status")).toBeNull();
  });
});
