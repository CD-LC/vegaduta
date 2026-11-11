import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/api/approvalTrace", () => ({ approvalTraceApi: { getApprovalTrace: vi.fn() } }));
vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key) => ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key),
  };
});

import ApprovalTracePanel from "./ApprovalTracePanel";
import type { ApprovalRequestItem } from "./approvalsModel";
import type { ApprovalTrace } from "../../lib/api/approvalTrace";

const item = { id: "r1", status: "APPROVED", actionType: "mcp_tool.load_skill" } as ApprovalRequestItem;
const trace = (over: Partial<ApprovalTrace> = {}): ApprovalTrace => ({
  requestId: "r1",
  actionType: "mcp_tool.load_skill",
  status: "APPROVED",
  decidedBy: "admin",
  decidedAt: "2026-10-29T17:09:15Z",
  conversation: { agentId: "a1", sessionId: "s1", goal: "write the report" },
  windowEnd: "2026-10-29T17:39:15Z",
  callsTruncated: false,
  calls: [],
  traceNote: "Tool calls of the same conversation within 30 minutes of the decision.",
  ...over,
});

afterEach(cleanup);

describe("ApprovalTracePanel", () => {
  it("lists the calls with their outcome and the server's note", async () => {
    const load = vi.fn().mockResolvedValue(
      trace({
        calls: [
          {
            id: "c1",
            toolName: "load_skill",
            startedAt: "2026-10-29T17:09:24Z",
            completedAt: "2026-10-29T17:09:25Z",
            outcome: "OK",
            inputSummary: '{"name":"x"}',
            outputSummary: null,
            errorMessage: null,
          },
          {
            id: "c2",
            toolName: "generate_document",
            startedAt: "2026-10-29T17:09:30Z",
            completedAt: "2026-10-29T17:09:31Z",
            outcome: "FAILED",
            inputSummary: null,
            outputSummary: null,
            errorMessage: "boom",
          },
        ],
      })
    );
    render(<ApprovalTracePanel item={item} load={load} />);
    await waitFor(() => expect(screen.getByText("load_skill")).toBeTruthy());
    expect(screen.getByText("generate_document")).toBeTruthy();
    expect(screen.getByText("boom")).toBeTruthy();
    expect(screen.getByText(/within 30 minutes/)).toBeTruthy();
    expect(load).toHaveBeenCalledWith("r1");
  });

  it("says no trace is available when the read comes back empty", async () => {
    render(<ApprovalTracePanel item={item} load={vi.fn().mockResolvedValue(null)} />);
    await waitFor(() => expect(screen.getByText(en.approvalsPanel.traceUnavailable)).toBeTruthy());
  });
});
