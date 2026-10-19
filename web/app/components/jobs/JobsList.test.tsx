import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render as rtlRender, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { I18nProvider } from "../../lib/i18n";
import messages from "../../../messages/en.json";

const render = (ui: ReactNode) =>
  rtlRender(
    <I18nProvider locale="en" messages={messages} enMessages={messages}>
      {ui}
    </I18nProvider>
  );

const listTenantAgentJobs = vi.fn();
vi.mock("./jobsPaged", () => ({ listTenantJobsPage: (...a: unknown[]) => listTenantAgentJobs(...a) }));
vi.mock("next/link", () => ({ default: ({ children, href }: any) => <a href={href}>{children}</a> }));

import JobsList from "./JobsList";

const job = (n: number) => ({
  id: `j${n}`,
  agentId: "a1",
  goalPreview: `goal ${n}`,
  agentName: "Agent",
  status: "SUCCEEDED",
  createdAt: "2026-01-01T00:00:00Z",
});

describe("JobsList", () => {
  beforeEach(() => listTenantAgentJobs.mockReset());

  it("pages with the shared pager and requests the next offset", async () => {
    listTenantAgentJobs.mockResolvedValue({ rows: [job(1), job(2)], total: undefined, hasMore: true });
    render(<JobsList />);
    await screen.findByText("goal 1");
    expect(listTenantAgentJobs).toHaveBeenLastCalledWith(expect.objectContaining({ limit: 25, offset: 0 }));
    fireEvent.click(screen.getByRole("button", { name: /next page/i }));
    await waitFor(() =>
      expect(listTenantAgentJobs).toHaveBeenLastCalledWith(expect.objectContaining({ limit: 25, offset: 25 }))
    );
  });

  it("shows an error with Retry instead of an empty state when the fetch fails", async () => {
    listTenantAgentJobs.mockRejectedValueOnce(new Error("down")).mockResolvedValue({ rows: [job(1)], total: undefined, hasMore: false });
    render(<JobsList />);
    await screen.findByText("down");
    expect(screen.queryByText("No jobs yet")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await screen.findByText("goal 1");
  });

  it("shows the real total from X-Total-Count and filters the loaded page with the search box", async () => {
    listTenantAgentJobs.mockResolvedValue({ rows: [job(1), job(2)], total: 40, hasMore: true });
    render(<JobsList />);
    await screen.findByText("goal 1");
    expect(screen.getByText(/of 40/)).toBeTruthy();
    fireEvent.change(screen.getByRole("searchbox", { name: /search/i }), { target: { value: "goal 2" } });
    expect(screen.queryByText("goal 1")).toBeNull();
    expect(screen.getByText("goal 2")).toBeTruthy();
  });
});
