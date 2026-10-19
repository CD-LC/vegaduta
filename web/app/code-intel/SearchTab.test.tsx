import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import en from "../../messages/en.json";

const search = vi.hoisted(() => vi.fn());
vi.mock("./lib", () => ({ codeIntel: { search } }));
vi.mock("../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
  },
}));

import SearchTab from "./SearchTab";

afterEach(() => {
  cleanup();
  search.mockReset();
});

const hit = (n: number) => ({ path: `f${n}.ts`, start_line: 1, end_line: 2, content: "x", score: 0.5 });

async function run(results: unknown[]) {
  search.mockResolvedValue(results);
  render(<SearchTab repoId="r1" onError={vi.fn()} />);
  fireEvent.change(screen.getByRole("textbox"), { target: { value: "auth" } });
  fireEvent.click(screen.getByRole("button", { name: /search/i }));
}

describe("SearchTab", () => {
  it("shows an empty state when nothing matched", async () => {
    await run([]);
    await waitFor(() => expect(screen.getByText("No matches")).toBeTruthy());
  });

  it("says when the result list was capped and pages the hits", async () => {
    await run(Array.from({ length: 8 }, (_, i) => hit(i)));
    await waitFor(() => expect(screen.getByTestId("ci-search-count").textContent).toContain("top 8"));
    expect(search).toHaveBeenCalledWith("r1", "auth", 8);
    expect(screen.getAllByText(/^f\d\.ts/).length).toBe(5);
  });
});
