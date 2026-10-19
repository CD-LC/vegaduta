import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { NorthStarResponse, WeekPoint } from "../lib/adminApi/northStar";

// AdminShell pulls in auth, nav and the api cache; the page under test only
// needs its title and children.
vi.mock("../components/AdminShell", async () => {
  const React = await import("react");
  return {
    default: ({ title, children }: { title: string; children: any }) =>
      React.createElement("div", null, React.createElement("h1", null, title), children),
  };
});

vi.mock("../lib/adminApi/northStar", () => ({
  northStarApi: { get: vi.fn() },
}));

import { northStarApi } from "../lib/adminApi/northStar";
import NorthStarPage from "./page";

function week(weekStart: string, over: Partial<WeekPoint> = {}): WeekPoint {
  return {
    weekStart,
    partial: false,
    wau: 7,
    runs: 20,
    scheduledShare: 0.25,
    successRate: 0.9,
    runsByKind: { INTERACTIVE: 15, SCHEDULED: 5, TRIGGERED: 0, CHANNEL: 0 },
    suppressed: false,
    ...over,
  };
}

function response(weeks: WeekPoint[], over: Partial<NorthStarResponse> = {}): NorthStarResponse {
  return {
    generatedAt: new Date().toISOString(),
    weeks,
    timeToFirstRunMedianMinutes: null,
    timeToFirstRunCohorts: [],
    languages: [],
    channels: [],
    passK: null,
    coverage: { tenantsTotal: 3, tenantsScanned: 3, tenantsSkipped: 0, truncated: false },
    notes: ["Metric 'conversations by language' unavailable because no language is recorded."],
    ...over,
  };
}

const get = vi.mocked(northStarApi.get);

describe("NorthStarPage", () => {
  beforeEach(() => {
    get.mockReset();
  });

  it("shows a loading skeleton while the sweep runs", () => {
    get.mockReturnValue(new Promise<NorthStarResponse>(() => {}));
    render(<NorthStarPage />);
    expect(screen.getByTestId("north-star-loading")).toBeInTheDocument();
  });

  it("shows the error and a retry when the request fails", async () => {
    get.mockRejectedValue(new Error("boom"));
    render(<NorthStarPage />);
    expect(await screen.findByText(/boom/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("shows the empty state, and still the notes, when no run was recorded", async () => {
    get.mockResolvedValue(
      response([week("2026-09-21", { wau: 0, runs: 0, scheduledShare: null, successRate: null, runsByKind: null })])
    );
    render(<NorthStarPage />);
    expect(await screen.findByTestId("north-star-empty")).toBeInTheDocument();
    expect(screen.queryByTestId("north-star-tiles")).not.toBeInTheDocument();
    expect(screen.getByTestId("notes-list")).toHaveTextContent("conversations by language");
  });

  it("headlines the latest complete week, not the in-progress one", async () => {
    get.mockResolvedValue(
      response([week("2026-09-14", { wau: 6 }), week("2026-09-21", { wau: 7 }), week("2026-09-28", { wau: 1, partial: true })])
    );
    render(<NorthStarPage />);
    const tile = await screen.findByTestId("tile-wau");
    expect(within(tile).getByText("7")).toBeInTheDocument();
    expect(tile).toHaveTextContent("Week of 21 Sep");
    expect(tile).toHaveTextContent("+1 vs previous week");
    expect(tile).toHaveTextContent("Weekly active = signed-in users with at least one completed agent run");
  });

  it("renders a withheld cell as hidden, never as zero", async () => {
    get.mockResolvedValue(
      response([
        week("2026-09-14"),
        week("2026-09-21", { wau: null, runs: null, scheduledShare: null, successRate: null, runsByKind: null, suppressed: true }),
      ])
    );
    render(<NorthStarPage />);
    const tile = await screen.findByTestId("tile-wau");
    expect(tile).toHaveTextContent("Hidden (<5 users)");
    expect(tile).not.toHaveTextContent(/\b0\b/);
    const table = screen.getByTestId("weekly-table");
    expect(within(table).getAllByText("Hidden (<5 users)").length).toBeGreaterThan(0);
  });

  it("shows n/a for an unavailable metric and explains it in the notes", async () => {
    get.mockResolvedValue(response([week("2026-09-21")]));
    render(<NorthStarPage />);
    const ttfr = await screen.findByTestId("tile-ttfr");
    expect(ttfr).toHaveTextContent("n/a");
    expect(screen.getByTestId("tile-passk")).toHaveTextContent("n/a");
    expect(screen.getByTestId("notes-list")).toHaveTextContent("unavailable because");
  });

  it("warns when the sweep was partial", async () => {
    get.mockResolvedValue(
      response([week("2026-09-21")], {
        coverage: { tenantsTotal: 9, tenantsScanned: 4, tenantsSkipped: 5, truncated: true },
      })
    );
    render(<NorthStarPage />);
    expect(await screen.findByText(/Partial sweep: 4 of 9 active tenants/)).toBeInTheDocument();
  });
});
