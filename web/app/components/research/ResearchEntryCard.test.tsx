// The entry card must never advertise research that cannot be started: it
// stays out of the page unless the status probe says all three switches are on.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

const getResearchStatus = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get getResearchStatus() {
      return getResearchStatus;
    },
  },
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

import ResearchEntryCard from "./ResearchEntryCard";

const LIMITS = {
  maxSearchesPerRun: 12,
  maxFetchesPerRun: 12,
  maxSources: 8,
  totalDeadlineSeconds: 120,
  runBudgetUsd: 0,
  maxRunsPerUserPerDay: 5,
  runTimeoutSeconds: 600,
};

function status(over: Record<string, unknown> = {}) {
  return { enabled: true, searchConfigured: true, runsEnabled: true, limits: LIMITS, remainingToday: 5, ...over };
}

describe("ResearchEntryCard", () => {
  beforeEach(() => {
    getResearchStatus.mockReset();
  });

  it("links to /research when research is ready", async () => {
    getResearchStatus.mockResolvedValue(status());
    render(<ResearchEntryCard />);
    const link = await screen.findByRole("link", { name: /entryCta/ });
    expect(link).toHaveAttribute("href", "/research");
  });

  it.each([
    ["research switched off", status({ enabled: false })],
    ["runs switched off", status({ runsEnabled: false })],
    ["no search key", status({ searchConfigured: false })],
  ])("renders nothing when %s", async (_label, value) => {
    getResearchStatus.mockResolvedValue(value);
    const { container } = render(<ResearchEntryCard />);
    await waitFor(() => expect(getResearchStatus).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when the status request fails (older core, 404)", async () => {
    getResearchStatus.mockRejectedValue(Object.assign(new Error("not found"), { status: 404 }));
    const { container } = render(<ResearchEntryCard />);
    await waitFor(() => expect(getResearchStatus).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
  });
});
