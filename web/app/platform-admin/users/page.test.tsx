import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

vi.mock("../components/AdminShell", async () => {
  const React = await import("react");
  return {
    default: ({ title, children }: { title: string; children: any }) =>
      React.createElement("div", null, React.createElement("h1", null, title), children),
  };
});
vi.mock("../components/governance/ViewAsDialog", () => ({ default: (): null => null }));
vi.mock("../lib/pagedFetch", () => ({ fetchPaged: vi.fn() }));
vi.mock("../lib/api", () => ({
  api: { getPlatformTenants: vi.fn().mockResolvedValue([]), getPlatformUser: vi.fn() },
}));
vi.mock("../lib/adminApi/operations", () => ({ opsApi: { logoutUser: vi.fn() } }));

import { fetchPaged } from "../lib/pagedFetch";
import PlatformUsersPage from "./page";

const fetchMock = vi.mocked(fetchPaged);

function user(i: number): Record<string, any> {
  return { id: `u${i}`, username: `user${i}`, email: `u${i}@x.io`, enabled: true, tenantId: "t1", createdAt: null, lastSeenAt: null };
}

describe("PlatformUsersPage server paging", () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it("requests limit/offset and advances with the pager", async () => {
    fetchMock.mockResolvedValue({ body: { users: Array.from({ length: 25 }, (_, i) => user(i)), total: 60 }, total: 60, hasMore: true });
    render(<PlatformUsersPage />);
    await waitFor(() => expect(screen.getByText("user0")).toBeInTheDocument());
    expect(fetchMock.mock.calls[0][0]).toContain("limit=25");
    expect(fetchMock.mock.calls[0][0]).toContain("offset=0");
    expect(screen.getByText(/1–25 of 60 users/)).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText("Next page"));
    await waitFor(() => expect(fetchMock.mock.calls.some((c) => c[0].includes("offset=25"))).toBe(true));
  });

  it("shows an error with Retry", async () => {
    fetchMock.mockRejectedValueOnce(new Error("boom"));
    render(<PlatformUsersPage />);
    await waitFor(() => expect(screen.getByText("boom")).toBeInTheDocument());
    fetchMock.mockResolvedValue({ body: { users: [user(1)], total: 1 }, total: 1, hasMore: false });
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(screen.getByText("user1")).toBeInTheDocument());
  });
});
