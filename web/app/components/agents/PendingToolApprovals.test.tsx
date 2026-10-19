// Inline tool-confirmation cards (trust T2) - the component half: approve,
// reject, and the non-admin 403 state. The card is the place a person
// authorises a risky action, so what it shows and what it calls are pinned.

import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

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

const PENDING = {
  id: "req-1",
  actionType: "mcp_tool.send_email",
  summary: "Tool call: send_email",
  status: "PENDING",
  requestedBy: "agent:a1:session:s1",
  payload: JSON.stringify({ replay: { version: 1, tenantId: "t-secret" }, arguments: { to: "x@example.com" } }),
  createdAt: "2026-10-03T10:00:00Z",
};
const OTHER_SESSION = { ...PENDING, id: "req-2", requestedBy: "agent:a1:session:other" };

beforeEach(() => {
  listApprovals.mockReset();
  getApproval.mockReset();
  approveRequest.mockReset();
  rejectRequest.mockReset();
  listApprovals.mockResolvedValue([PENDING, OTHER_SESSION]);
});

describe("PendingToolApprovals", () => {
  it("renders nothing without a session, and nothing when no request belongs to it", async () => {
    const { container, rerender } = render(<PendingToolApprovals agentId="a1" sessionId={null} />);
    expect(container.firstChild).toBeNull();
    expect(listApprovals).not.toHaveBeenCalled();

    listApprovals.mockResolvedValue([OTHER_SESSION]);
    rerender(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    await waitFor(() => expect(listApprovals).toHaveBeenCalledTimes(1));
    expect(listApprovals.mock.calls[0][0]).toBe("PENDING");
    expect(container.firstChild).toBeNull();
  });

  it("shows only this session's parked call with its arguments, not the replay plumbing", async () => {
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    expect(await screen.findByTestId("tool-approval-req-1")).toBeTruthy();
    expect(screen.queryByTestId("tool-approval-req-2")).toBeNull();
    expect(screen.getByText("title:send_email")).toBeTruthy();
    const args = screen.getByTestId("tool-approval-args").textContent || "";
    expect(args).toContain("x@example.com");
    expect(args).not.toContain("t-secret");
  });

  it("approves and shows the replayed result inline", async () => {
    approveRequest.mockResolvedValue({
      ...PENDING,
      status: "APPROVED",
      executionResult: "Email sent to x@example.com",
    });
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-approve"));

    expect(await screen.findByTestId("tool-approval-outcome-done")).toBeTruthy();
    expect(approveRequest.mock.calls[0][0]).toBe("req-1");
    expect(screen.getByText("Email sent to x@example.com")).toBeTruthy();
    expect(screen.queryByTestId("tool-approval-approve")).toBeNull();
    expect(rejectRequest).not.toHaveBeenCalled();
  });

  it("re-reads the request when the approve response carries no result yet, and reports a failed replay", async () => {
    approveRequest.mockResolvedValue({ ...PENDING, status: "APPROVED" });
    getApproval.mockResolvedValue({ ...PENDING, status: "EXECUTION_FAILED", executionError: "smtp down" });
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-approve"));

    expect(await screen.findByTestId("tool-approval-outcome-failed")).toBeTruthy();
    expect(getApproval.mock.calls[0][0]).toBe("req-1");
    expect(screen.getByText("smtp down")).toBeTruthy();
  });

  it("rejects without running anything", async () => {
    rejectRequest.mockResolvedValue({ ...PENDING, status: "REJECTED" });
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-reject"));

    expect(await screen.findByTestId("tool-approval-outcome-rejected")).toBeTruthy();
    expect(rejectRequest.mock.calls[0][0]).toBe("req-1");
    expect(approveRequest).not.toHaveBeenCalled();
    expect(screen.queryByTestId("tool-approval-reject")).toBeNull();
  });

  it("on 403 swaps the buttons for a pointer to the approvals queue", async () => {
    approveRequest.mockRejectedValue(Object.assign(new Error("forbidden"), { status: 403 }));
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-approve"));

    const hint = await screen.findByTestId("tool-approval-needs-admin");
    expect(hint.querySelector("a")?.getAttribute("href")).toBe("/admin/approvals");
    expect(screen.queryByTestId("tool-approval-approve")).toBeNull();
    expect(screen.queryByTestId("tool-approval-reject")).toBeNull();
    // Still pending - not presented as decided.
    expect(screen.getByText("title:send_email")).toBeTruthy();
  });

  it("on a 500 from approve shows the recorded failure, not a retry prompt", async () => {
    approveRequest.mockRejectedValue(Object.assign(new Error("Internal error"), { status: 500 }));
    getApproval.mockResolvedValue({
      ...PENDING,
      status: "EXECUTION_FAILED",
      executionError: "tool no longer available",
    });
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-approve"));

    expect(await screen.findByTestId("tool-approval-outcome-failed")).toBeTruthy();
    expect(getApproval.mock.calls[0][0]).toBe("req-1");
    expect(screen.getByText("tool no longer available")).toBeTruthy();
    expect(screen.queryByTestId("tool-approval-outcome-error")).toBeNull();
    expect(screen.queryByTestId("tool-approval-approve")).toBeNull();
    expect(screen.queryByTestId("tool-approval-reject")).toBeNull();
    // The failure is reported inline, so the call itself must not raise the global toast.
    expect(approveRequest.mock.calls[0][3]).toContain(500);
  });

  it("on a 500 from approve never shows an approved row without a result as a clean Done", async () => {
    approveRequest.mockRejectedValue(Object.assign(new Error("replay blew up"), { status: 500 }));
    getApproval.mockResolvedValue({ ...PENDING, status: "APPROVED" });
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-approve"));

    expect(await screen.findByTestId("tool-approval-outcome-failed")).toBeTruthy();
    expect(screen.getByText("replay blew up")).toBeTruthy();
    expect(screen.queryByTestId("tool-approval-approve")).toBeNull();
  });

  it("keeps the retry buttons only when the request is still pending after a failed call", async () => {
    approveRequest.mockRejectedValue(Object.assign(new Error("gateway"), { status: 502 }));
    getApproval.mockResolvedValue({ ...PENDING });
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);
    fireEvent.click(await screen.findByTestId("tool-approval-approve"));

    expect(await screen.findByTestId("tool-approval-outcome-error")).toBeTruthy();
    expect(screen.getByTestId("tool-approval-approve")).toBeTruthy();
    expect(screen.getByTestId("tool-approval-reject")).toBeTruthy();
  });

  it("does not offer Approve when the arguments are too long to show in full", async () => {
    const huge = {
      ...PENDING,
      payload: JSON.stringify({ replay: { version: 1 }, arguments: { body: "y".repeat(25_000) } }),
    };
    listApprovals.mockResolvedValue([huge]);
    render(<PendingToolApprovals agentId="a1" sessionId="s1" />);

    const note = await screen.findByTestId("tool-approval-args-truncated");
    expect(note.querySelector("a")?.getAttribute("href")).toBe("/admin/approvals");
    expect(screen.queryByTestId("tool-approval-approve")).toBeNull();
    expect(screen.getByTestId("tool-approval-reject")).toBeTruthy();
  });

  it("closes a decided card when the person clicks its close control", async () => {
    approveRequest.mockResolvedValue({
      ...PENDING,
      status: "APPROVED",
      executionResult: "Email sent to x@example.com",
    });
    render(<PendingToolApprovals agentId="a1" sessionId="s1" autoCloseMs={60_000} />);
    expect(screen.queryByTestId("tool-approval-close-req-1")).toBeNull(); // not before a decision
    fireEvent.click(await screen.findByTestId("tool-approval-approve"));
    expect(await screen.findByTestId("tool-approval-outcome-done")).toBeTruthy();

    fireEvent.click(screen.getByTestId("tool-approval-close-req-1"));
    expect(screen.queryByTestId("tool-approval-req-1")).toBeNull();
    expect(screen.queryByTestId("pending-tool-approvals")).toBeNull();
  });

  it("a clean Done closes itself; a failed replay stays until closed", async () => {
    approveRequest.mockResolvedValue({ ...PENDING, status: "APPROVED", executionResult: "ok" });
    render(<PendingToolApprovals agentId="a1" sessionId="s1" autoCloseMs={20} />);
    fireEvent.click(await screen.findByTestId("tool-approval-approve"));
    expect(await screen.findByTestId("tool-approval-outcome-done")).toBeTruthy();
    await waitFor(() => expect(screen.queryByTestId("tool-approval-req-1")).toBeNull());

    approveRequest.mockReset();
    approveRequest.mockResolvedValue({ ...PENDING, id: "req-3", status: "APPROVED", executionError: "boom" });
    listApprovals.mockResolvedValue([{ ...PENDING, id: "req-3" }]);
    render(<PendingToolApprovals agentId="a1" sessionId="s1" autoCloseMs={20} />);
    fireEvent.click(await screen.findByTestId("tool-approval-approve"));
    expect(await screen.findByTestId("tool-approval-outcome-failed")).toBeTruthy();
    await new Promise((r) => setTimeout(r, 80));
    expect(screen.getByTestId("tool-approval-req-3")).toBeTruthy();
    fireEvent.click(screen.getByTestId("tool-approval-close-req-3"));
    expect(screen.queryByTestId("tool-approval-req-3")).toBeNull();
  });

  it("re-reads the queue when a turn finishes", async () => {
    listApprovals.mockResolvedValue([]);
    const { rerender } = render(<PendingToolApprovals agentId="a1" sessionId="s1" turnInFlight />);
    await waitFor(() => expect(listApprovals).toHaveBeenCalledTimes(1));

    listApprovals.mockResolvedValue([PENDING]);
    rerender(<PendingToolApprovals agentId="a1" sessionId="s1" turnInFlight={false} />);
    expect(await screen.findByTestId("tool-approval-req-1")).toBeTruthy();
    expect(listApprovals).toHaveBeenCalledTimes(2);
  });
});
