// The generic job screen may only point at /research/{jobId} when that page
// can actually open the job: a research-shaped result on someone else's agent
// must not produce a link that lands on "run not found".

import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

const getResearchRun = vi.fn();
vi.mock("../../lib/api", () => ({
  api: { getResearchRun: (...args: unknown[]) => getResearchRun(...args) },
}));

import ResearchReportLink from "./ResearchReportLink";

const SHAPED = { title: "Tea", reportMarkdown: "## Findings", sources: [] as unknown[] };

describe("ResearchReportLink", () => {
  beforeEach(() => {
    getResearchRun.mockReset();
  });

  it("links to the report when the job is one of the viewer's research runs", async () => {
    getResearchRun.mockResolvedValue({ id: "j1" });
    render(<ResearchReportLink jobId="j1" resultJson={SHAPED} />);
    const link = await screen.findByTestId("research-report-link");
    expect(link.getAttribute("href")).toBe("/research/j1");
    expect(getResearchRun).toHaveBeenCalledWith("j1");
  });

  it("shows nothing when the research page would not find the job", async () => {
    getResearchRun.mockRejectedValue(Object.assign(new Error("not found"), { status: 404 }));
    render(<ResearchReportLink jobId="j2" resultJson={SHAPED} />);
    await waitFor(() => expect(getResearchRun).toHaveBeenCalled());
    expect(screen.queryByTestId("research-report-link")).toBeNull();
  });

  it("does not ask the server about a result that is not research-shaped", () => {
    render(<ResearchReportLink jobId="j3" resultJson={{ answer: 42 }} />);
    expect(getResearchRun).not.toHaveBeenCalled();
    expect(screen.queryByTestId("research-report-link")).toBeNull();
  });
});
