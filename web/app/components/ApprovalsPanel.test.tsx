import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import en from "../../messages/en.json";

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
vi.mock("../lib/api", () => ({
  api: {
    countApprovals: () => Promise.resolve(2),
    approveRequest: (...a: unknown[]) => approveRequest(...a),
    rejectRequest: (...a: unknown[]) => rejectRequest(...a),
    setApprovalPolicy: vi.fn(),
  },
}));
const listApprovalsPage = vi.fn();
vi.mock("./approvalsServerPaging", () => ({
  listApprovalsPage: (...a: unknown[]) => listApprovalsPage(...a),
}));
vi.mock("../lib/pulseBus", () => ({ firePulseAtElement: vi.fn() }));

import ApprovalsPanel from "./ApprovalsPanel";

const rows = [
  { id: "a1", status: "PENDING", actionType: "channel.slack.send", summary: "Send hello", requestedBy: "agent:12345678-aaaa", createdAt: new Date().toISOString() },
  { id: "a2", status: "PENDING", actionType: "agent.proposal", summary: "Run report", requestedBy: "system:email-poll:inbox", createdAt: new Date().toISOString() },
];

describe("ApprovalsPanel", () => {
  beforeEach(() => {
    approveRequest.mockReset().mockResolvedValue({});
    rejectRequest.mockReset().mockResolvedValue({});
    listApprovalsPage.mockReset().mockResolvedValue({ rows, total: 2, hasMore: false });
  });

  it("renders a table with status badges and the first request in the detail panel", async () => {
    render(<ApprovalsPanel isAdmin />);
    expect(await screen.findByRole("table")).toBeInTheDocument();
    expect(screen.getAllByText("PENDING").length).toBeGreaterThan(0);
    expect(screen.getByTestId("approval-detail")).toHaveTextContent("channel.slack.send");
  });

  it("selects a row from the keyboard and approves", async () => {
    render(<ApprovalsPanel isAdmin />);
    const row = (await screen.findByText("Run report")).closest("tr")!;
    fireEvent.keyDown(row, { key: "Enter" });
    await waitFor(() => expect(screen.getByTestId("approval-detail")).toHaveTextContent("agent proposal"));
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(approveRequest).toHaveBeenCalledWith("a2", null, {}));
  });

  it("shows an empty state and an error state", async () => {
    listApprovalsPage.mockResolvedValueOnce({ rows: [], total: 0, hasMore: false });
    const { unmount } = render(<ApprovalsPanel isAdmin />);
    expect(await screen.findByText("Nothing to review")).toBeInTheDocument();
    unmount();
    listApprovalsPage.mockRejectedValueOnce(new Error("boom"));
    render(<ApprovalsPanel isAdmin />);
    expect(await screen.findByRole("alert")).toHaveTextContent("boom");
  });
});
