// "Approve all N" / "Reject all" on the inline approval panel: several calls parked in one turn can be decided in
// one go, one request after another through the same approve/reject calls a single card uses, stopping at the first
// problem - and a call whose arguments are too long to show is never approved that way.

import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

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

function parked(id: string, minute: number, args: Record<string, unknown> = { q: id }, queuedBehind = minute > 1) {
  return {
    id,
    actionType: "mcp_tool.web_search",
    summary: `Tool call: web_search${queuedBehind ? " [queued behind: web_search]" : ""}`,
    status: "PENDING",
    requestedBy: "agent:a1:session:s1",
    payload: JSON.stringify({ arguments: args }),
    createdAt: `2026-10-23T10:0${minute}:00Z`,
  };
}
const FIRST = parked("req-1", 1);
const SECOND = parked("req-2", 2);
const THIRD = parked("req-3", 3);
const done = (row: ReturnType<typeof parked>) => ({ ...row, status: "APPROVED", executionResult: `ok ${row.id}` });

beforeEach(() => {
  listApprovals.mockReset().mockResolvedValue([FIRST, SECOND, THIRD]);
  getApproval.mockReset();
  approveRequest.mockReset();
  rejectRequest.mockReset();
});

describe("PendingToolApprovals - decide all", () => {
  it("offers Approve all / Reject all only when more than one call is waiting", async () => {
    const { unmount } = render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    expect(await screen.findByTestId("tool-approval-approve-all")).toHaveTextContent("approveAll:3");
    expect(screen.getByTestId("tool-approval-reject-all")).toBeTruthy();
    unmount();

    listApprovals.mockResolvedValue([FIRST]);
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    await screen.findByTestId("tool-approval-req-1");
    expect(screen.queryByTestId("tool-approval-bulk")).toBeNull();
  });

  it("approves every waiting call one after another, oldest first, and shows the progress", async () => {
    const pending: Array<(v: unknown) => void> = [];
    approveRequest.mockImplementation(() => new Promise((resolve) => pending.push(resolve)));
    const onDecided = vi.fn();
    render(<PendingToolApprovals agentId="a1" sessionId="s1" onDecided={onDecided} />);
    fireEvent.click(await screen.findByTestId("tool-approval-approve-all"));

    expect(await screen.findByTestId("tool-approval-bulk-progress")).toHaveTextContent("approvingProgress:1,3");
    // Sequential: the second request is not sent before the first one answers.
    expect(approveRequest).toHaveBeenCalledTimes(1);
    expect(approveRequest.mock.calls[0][0]).toBe("req-1");
    await act(async () => pending[0](done(FIRST)));

    await waitFor(() => expect(approveRequest).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId("tool-approval-bulk-progress")).toHaveTextContent("approvingProgress:2,3");
    expect(approveRequest.mock.calls[1][0]).toBe("req-2");
    await act(async () => pending[1](done(SECOND)));

    await waitFor(() => expect(approveRequest).toHaveBeenCalledTimes(3));
    expect(approveRequest.mock.calls[2][0]).toBe("req-3");
    await act(async () => pending[2](done(THIRD)));

    await waitFor(() => expect(onDecided).toHaveBeenCalledTimes(3));
    expect(onDecided.mock.calls.map((c) => c[1])).toEqual(["req-1", "req-2", "req-3"]);
    expect(screen.queryByTestId("tool-approval-current")).toBeNull();
    expect(screen.getAllByTestId("tool-approval-outcome-done")).toHaveLength(3);
    expect(rejectRequest).not.toHaveBeenCalled();
  });

  it("stops at the first error and leaves the rest waiting", async () => {
    approveRequest
      .mockResolvedValueOnce(done(FIRST))
      .mockRejectedValueOnce(Object.assign(new Error("boom"), { status: 500 }));
    getApproval.mockResolvedValue(SECOND); // still pending: nothing was decided
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-approve-all"));

    expect(await screen.findByTestId("tool-approval-bulk-stopped")).toHaveTextContent("bulkStoppedError");
    expect(approveRequest).toHaveBeenCalledTimes(2);
    const current = screen.getByTestId("tool-approval-current");
    expect(within(current).getByTestId("tool-approval-req-2")).toBeTruthy();
    expect(within(current).getByTestId("tool-approval-outcome-error")).toBeTruthy();
    expect(screen.getByTestId("tool-approval-queue-status").textContent).toContain("queuePosition:1,2");
  });

  it("never approves a call with truncated arguments in bulk: it stops there for a manual decision", async () => {
    const long = parked("req-2", 2, { body: "x".repeat(25_000) });
    listApprovals.mockResolvedValue([FIRST, long, THIRD]);
    approveRequest.mockResolvedValueOnce(done(FIRST));
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-approve-all"));

    expect(await screen.findByTestId("tool-approval-bulk-stopped")).toHaveTextContent("bulkStoppedTruncated");
    expect(approveRequest).toHaveBeenCalledTimes(1);
    expect(approveRequest.mock.calls[0][0]).toBe("req-1");
    const current = screen.getByTestId("tool-approval-current");
    expect(within(current).getByTestId("tool-approval-req-2")).toBeTruthy();
    // The card itself still has no Approve, and Approve all is not offered while it is the current call.
    expect(within(current).queryByTestId("tool-approval-approve")).toBeNull();
    expect(screen.queryByTestId("tool-approval-approve-all")).toBeNull();
    expect(screen.getByTestId("tool-approval-reject-all")).toBeTruthy();
  });

  it("rejects every waiting call in order, truncated ones included", async () => {
    const long = parked("req-2", 2, { body: "x".repeat(25_000) });
    listApprovals.mockResolvedValue([FIRST, long, THIRD]);
    rejectRequest.mockImplementation(async (id: string) => ({ ...FIRST, id, status: "REJECTED" }));
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-reject-all"));

    await waitFor(() => expect(rejectRequest).toHaveBeenCalledTimes(3));
    expect(rejectRequest.mock.calls.map((c) => c[0])).toEqual(["req-1", "req-2", "req-3"]);
    await waitFor(() => expect(screen.getAllByTestId("tool-approval-outcome-rejected")).toHaveLength(3));
    expect(approveRequest).not.toHaveBeenCalled();
  });

  it("stops and points to the people who can decide on a 403", async () => {
    approveRequest.mockRejectedValue(Object.assign(new Error("forbidden"), { status: 403 }));
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-approve-all"));

    expect(await screen.findByTestId("tool-approval-needs-admin")).toBeTruthy();
    expect(approveRequest).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId("tool-approval-bulk")).toBeNull();
  });

  it("names the tool the way a person reads it, keeps the raw name for experts, and drops core's annotation", async () => {
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    const card = await screen.findByTestId("tool-approval-req-1");
    expect(within(card).getByText("title:label_web_search")).toBeTruthy();
    expect(within(card).getByTestId("tool-approval-does")).toHaveTextContent("does_web_search");
    expect(within(card).getByTestId("tool-approval-raw-name")).toHaveTextContent("web_search");
    // The waiting list names the queued calls the same way, without "[queued behind: ...]".
    const list = screen.getByTestId("tool-approval-waiting-list");
    expect(list.textContent).toContain("label_web_search");
    expect(list.textContent).not.toContain("queued behind");
  });
});
