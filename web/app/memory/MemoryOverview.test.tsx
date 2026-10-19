// The figures above the facts list on /memory, rendered with the real English strings.

import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import en from "../../messages/en.json";

vi.mock("../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key] ?? `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
  },
  useLocale: () => "en-GB",
}));

import MemoryOverview from "./MemoryOverview";
import type { MemoryFact } from "./memoryTypes";

function fact(over: Partial<MemoryFact>): MemoryFact {
  return { id: "f1", scope: "agent", content: "likes tea", createdAt: "2026-09-01T00:00:00Z", lastUsedAt: "2026-09-01T00:00:00Z", useCount: 0, ...over };
}

describe("MemoryOverview", () => {
  it("has a quiet empty state and draws no chart for zero facts", () => {
    const { container } = render(<MemoryOverview facts={[]} />);
    expect(screen.getByTestId("memory-overview-empty").textContent).toBe(en.memoryPage.overviewEmpty);
    expect(container.querySelector("svg")).toBeNull();
    expect(screen.queryByRole("button", { name: "View as table" })).toBeNull();
  });

  it("says the scope split in a sentence, as a bar with a text legend, and as a table", () => {
    const { container } = render(
      <MemoryOverview facts={[fact({ id: "a" }), fact({ id: "b", scope: "shared" }), fact({ id: "c", scope: "shared" })]} />
    );
    const sentence = "3 facts: 1 learned by one assistant, 2 shared across your assistants.";
    expect(screen.getByText(sentence)).toBeTruthy();
    expect(container.querySelector("svg")?.getAttribute("aria-label")).toBe(sentence);
    expect(container.querySelectorAll("[data-segment]")).toHaveLength(2);

    fireEvent.click(screen.getAllByRole("button", { name: "View as table" })[0]);
    const rows = within(screen.getByRole("table")).getAllByRole("row");
    expect(rows.slice(1).map((row) => row.textContent)).toEqual(["Learned by one assistant1", "Shared2"]);
  });

  it("uses the singular for one fact", () => {
    render(<MemoryOverview facts={[fact({})]} />);
    expect(screen.getByText("1 fact: 1 learned by one assistant, 0 shared across your assistants.")).toBeTruthy();
  });

  it("ranks the five most used facts and folds the rest into 'and N more'", () => {
    const facts = [1, 2, 3, 4, 5, 6, 7].map((n) => fact({ id: `f${n}`, content: `fact number ${n}`, useCount: n }));
    facts.push(fact({ id: "unused", content: "never recalled", useCount: 0 }));
    const { container } = render(<MemoryOverview facts={facts} />);

    expect(screen.getByText("Facts used in a conversation: 7 of 8. Highest use count: 7.")).toBeTruthy();
    const ranked = [...container.querySelectorAll("ol > li")];
    expect(ranked.map((li) => li.querySelector("span")?.textContent)).toEqual([
      "fact number 7",
      "fact number 6",
      "fact number 5",
      "fact number 4",
      "fact number 3",
    ]);
    expect(ranked[0].textContent).toContain("Used 7 times");
    expect(container.querySelector("[data-ranked-more]")?.textContent).toBe("and 2 more");
    expect(screen.queryByText("never recalled")).toBeNull();
  });

  it("reports never-used facts by use count, with the newest and oldest dates", () => {
    render(
      <MemoryOverview
        facts={[
          fact({ id: "a", useCount: 0, createdAt: "2026-10-01T12:00:00Z" }),
          fact({ id: "b", useCount: 0, createdAt: "2026-07-02T12:00:00Z" }),
          fact({ id: "c", useCount: 1, createdAt: "2026-08-15T12:00:00Z" }),
        ]}
      />
    );
    const figures = screen.getByTestId("memory-overview-facts");
    expect(figures.textContent).toContain("Never used2 of 3");
    expect(figures.textContent).toContain("Newest fact1 Oct 2026");
    expect(figures.textContent).toContain("Oldest fact2 Jul 2026");
    expect(screen.getByText("Used once")).toBeTruthy();
  });

  it("shows an empty state, not an empty chart, when no fact has been used yet", () => {
    const { container } = render(<MemoryOverview facts={[fact({ id: "a" }), fact({ id: "b" })]} />);
    expect(screen.getByText("No fact has been used yet")).toBeTruthy();
    expect(screen.getByText(en.memoryPage.overviewUsedEmptyHint)).toBeTruthy();
    expect(container.querySelector("ol")).toBeNull();
    expect(screen.getByTestId("memory-overview-facts").textContent).toContain("Never used2 of 2");
  });

  it("hides every usage figure when an older core sends no use counts", () => {
    render(<MemoryOverview facts={[fact({ id: "a", useCount: undefined }), fact({ id: "b", useCount: undefined })]} />);
    expect(screen.queryByText("Most used facts")).toBeNull();
    expect(screen.queryByText("Never used")).toBeNull();
    expect(screen.getByText("Newest fact")).toBeTruthy();
  });
});
