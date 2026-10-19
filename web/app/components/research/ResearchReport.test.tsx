// What the report screen must never get wrong, whatever the model returned:
// a marker links only to an http/https source it actually names, hostile
// source URLs are shown but not linked, and a run without a structured result
// still shows its text.

import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";

// t() returns the key (with any values appended) so assertions bind to keys,
// not to English copy that may be reworded.
vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${Object.values(values).join(",")}` : key,
}));

// The export menu mounted inside the report talks to the API; nothing in
// these tests clicks it.
vi.mock("../../lib/api", () => ({
  api: {
    generateResearchDocument: vi.fn(),
    getTenantSettings: vi.fn().mockResolvedValue({ plan: "PREMIUM" }),
    listGeneratedDocuments: vi.fn(),
    downloadGeneratedDocument: vi.fn(),
  },
  triggerBrowserDownload: vi.fn(),
}));

import ResearchReport from "./ResearchReport";

const RESULT = {
  title: "Tea and health",
  reportMarkdown: "## Findings\n\nTea is widely drunk [S1]. Claims of cures are unproven [S2][S7].",
  sources: [
    {
      id: "S1",
      url: "https://example.org/tea",
      title: "Tea facts",
      quotes: [{ quote: "Tea is the most widely consumed drink", claim: "Tea is widely drunk", verdict: "SUPPORTED" }],
    },
    {
      id: "S2",
      url: "javascript:alert(1)",
      quotes: [{ quote: "no cure has been shown", verdict: "SOMETHING_NEW" }, { quote: "unchecked quote" }],
    },
  ],
  unanswered: ["How much tea is too much?"],
  partial: true,
};

function job(over: Record<string, unknown> = {}) {
  return { id: "j1", status: "SUCCEEDED", terminal: true, resultJson: RESULT, resultText: null as string | null, ...over };
}

describe("ResearchReport", () => {
  it("renders the title, the partial badge and the unanswered list", () => {
    render(<ResearchReport job={job()} />);
    expect(screen.getByRole("heading", { name: "Tea and health" })).toBeInTheDocument();
    expect(screen.getByTestId("research-partial")).toHaveTextContent("partialBadge");
    expect(within(screen.getByTestId("research-unanswered")).getByText("How much tea is too much?")).toBeInTheDocument();
  });

  it("links a known marker to its source and leaves the others inert", () => {
    render(<ResearchReport job={job()} />);
    const report = screen.getByTestId("research-report");
    const s1 = within(report).getByRole("link", { name: "S1" });
    expect(s1).toHaveAttribute("href", "https://example.org/tea");
    expect(s1).toHaveAttribute("rel", expect.stringContaining("noopener"));
    // S2's url is not http(s) and S7 is not in the source list: plain text.
    expect(within(report).queryByRole("link", { name: "S2" })).toBeNull();
    expect(within(report).queryByRole("link", { name: "S7" })).toBeNull();
    expect(report).toHaveTextContent("[S2][S7]");
  });

  it("lists sources with quotes and verdicts, never linking an unsafe url", () => {
    render(<ResearchReport job={job()} />);
    const sources = screen.getByTestId("research-sources");
    expect(within(sources).getByText("Tea facts")).toBeInTheDocument();
    expect(within(sources).getByRole("link", { name: /https:\/\/example\.org\/tea/ })).toHaveAttribute(
      "href",
      "https://example.org/tea"
    );
    expect(within(sources).getByText("javascript:alert(1)").closest("a")).toBeNull();
    expect(within(sources).getByText("verdictSupported")).toBeInTheDocument();
    // An unrecognised verdict is shown as written; a missing one says so.
    expect(within(sources).getByText("SOMETHING_NEW")).toBeInTheDocument();
    expect(within(sources).getByText("verdictNotChecked")).toBeInTheDocument();
    expect(sources).toHaveTextContent("lexicalNote");
  });

  it("tolerates a result with only the required fields", () => {
    render(
      <ResearchReport
        job={job({ resultJson: { title: "T", reportMarkdown: "Body [S1]", sources: [{ id: "S1", url: "https://a.example/" }] } })}
      />
    );
    expect(screen.queryByTestId("research-partial")).toBeNull();
    expect(screen.queryByTestId("research-unanswered")).toBeNull();
    expect(screen.getByRole("link", { name: "S1" })).toHaveAttribute("href", "https://a.example/");
  });

  it("falls back to the plain result text when there is no structured result", () => {
    render(<ResearchReport job={job({ resultJson: null, resultText: "Plain answer with https://example.org/x" })} />);
    const plain = screen.getByTestId("research-report-plain");
    expect(plain).toHaveTextContent("Plain answer with");
    expect(within(plain).getByRole("link")).toHaveAttribute("href", "https://example.org/x");
    expect(screen.queryByTestId("research-sources")).toBeNull();
  });

  it("renders nothing when the run produced no result at all", () => {
    const { container } = render(<ResearchReport job={job({ resultJson: null, resultText: null })} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe("a run that cited nothing", () => {
  it("warns that nothing was researched instead of presenting the text as a finished report", () => {
    render(
      <ResearchReport
        job={job({
          agentId: "agent-123",
          resultJson: { title: "Invalid JSON Response", reportMarkdown: "The previous response was not in the correct JSON format.", sources: [] },
        })}
      />
    );
    const warn = screen.getByTestId("research-no-sources");
    expect(within(warn).getByText("noSourcesWarnTitle")).toBeTruthy();
    expect(within(warn).getByRole("link", { name: "noSourcesWarnAction" }).getAttribute("href")).toBe("/agents?agentId=agent-123");
  });

  it("warns even when sources are listed if the run never called a tool (placeholder sources)", () => {
    render(
      <ResearchReport
        job={job({ resultJson: { title: "Summary", reportMarkdown: "x [S1]", sources: [{ id: "S1", url: "http://example.com/source1", title: "Source 1 Title" }] } })}
        noToolCalls
      />
    );
    expect(screen.getByTestId("research-no-sources")).toBeTruthy();
  });

  it("does not warn when the report cites sources", () => {
    render(<ResearchReport job={job({ resultJson: RESULT })} />);
    expect(screen.queryByTestId("research-no-sources")).toBeNull();
  });
});
