import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import LearnedWeekSummary from "./LearnedWeekSummary";

const getSummary = vi.fn();

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (k: string, v?: Record<string, unknown>) => (v ? `${k}:${JSON.stringify(v)}` : k),
}));
vi.mock("../../lib/api/assistant", () => ({
  assistantApi: { getLearningWeekSummaryQuiet: () => getSummary() },
}));

describe("LearnedWeekSummary", () => {
  beforeEach(() => getSummary.mockReset());

  it("shows what was learned this week, with the tidy-ups", async () => {
    getSummary.mockResolvedValue({ approved: 3, tidied: 2, pending: 1, text: "" });
    render(<LearnedWeekSummary refreshKey={0} />);
    await waitFor(() => expect(screen.getByTestId("learned-week-summary")).toBeInTheDocument());
    expect(screen.getByText(/learnedWeekLearned:\{"n":3\}/)).toBeInTheDocument();
    expect(screen.getByText(/learnedWeekTidied:\{"n":2\}/)).toBeInTheDocument();
  });

  it("says so when nothing was learned", async () => {
    getSummary.mockResolvedValue({ approved: 0, tidied: 0, pending: 0, text: "" });
    render(<LearnedWeekSummary refreshKey={0} />);
    await waitFor(() => expect(screen.getByText("learnedWeekNone")).toBeInTheDocument());
  });

  it("renders nothing when the review is off (a 404 comes back as null)", async () => {
    getSummary.mockResolvedValue(null);
    const { container } = render(<LearnedWeekSummary refreshKey={0} />);
    await waitFor(() => expect(getSummary).toHaveBeenCalledTimes(1));
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when the read fails", async () => {
    getSummary.mockReturnValue({ catch: (f: () => null) => Promise.resolve(f()) });
    const { container } = render(<LearnedWeekSummary refreshKey={0} />);
    await waitFor(() => expect(getSummary).toHaveBeenCalledTimes(1));
    expect(container).toBeEmptyDOMElement();
  });

  it("re-reads when the number of waiting suggestions changes", async () => {
    getSummary.mockResolvedValue({ approved: 1, tidied: 0, pending: 0, text: "" });
    const { rerender } = render(<LearnedWeekSummary refreshKey={2} />);
    await waitFor(() => expect(getSummary).toHaveBeenCalledTimes(1));
    rerender(<LearnedWeekSummary refreshKey={1} />);
    await waitFor(() => expect(getSummary).toHaveBeenCalledTimes(2));
  });
});
