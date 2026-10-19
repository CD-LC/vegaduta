import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

vi.mock("../components/AdminShell", async () => {
  const React = await import("react");
  return {
    default: ({ title, actions, children }: { title: string; actions?: any; children: any }) =>
      React.createElement("div", null, React.createElement("h1", null, title), actions, children),
  };
});
vi.mock("../lib/pagedFetch", () => ({ fetchPaged: vi.fn() }));
vi.mock("../lib/api", () => ({ api: { reviewAiReport: vi.fn() } }));

import { fetchPaged } from "../lib/pagedFetch";
import AiReportsPage from "./page";

const fetchMock = vi.mocked(fetchPaged);

function report(i: number): Record<string, any> {
  return {
    id: `r${i}`, tenantId: "t", reporterId: "p", surface: "chat", reason: "offensive_content", comment: null,
    content: "x", prompt: null, agentId: null, sessionId: null, messageRef: null, onDevice: false,
    appPlatform: null, appVersion: null, status: "OPEN", reviewNote: null, reviewedBy: null, reviewedAt: null, createdAt: null,
  };
}

describe("AiReportsPage server paging", () => {
  beforeEach(() => fetchMock.mockReset());

  it("pages with offset and shows the total", async () => {
    fetchMock.mockResolvedValue({ body: { reports: Array.from({ length: 25 }, (_, i) => report(i)) }, total: 80, hasMore: true });
    render(<AiReportsPage />);
    await waitFor(() => expect(screen.getByText(/1–25 of 80 reports/)).toBeInTheDocument());
    expect(fetchMock.mock.calls[0][0]).toContain("status=OPEN");
    fireEvent.click(screen.getByLabelText("Next page"));
    await waitFor(() => expect(fetchMock.mock.calls.some((c) => c[0].includes("offset=25"))).toBe(true));
  });
});
