import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import en from "../../messages/en.json";

vi.mock("../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string) => (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key] ?? `${namespace}.${key}`,
}));

import { FamilyLinks, PricesPager, SeeCostPerAgentLink } from "./RoiFamily";

afterEach(cleanup);

describe("RoiFamily", () => {
  it("links the cost views together", () => {
    render(<><FamilyLinks /><SeeCostPerAgentLink /></>);
    expect(screen.getByRole("link", { name: "Usage and quota" }).getAttribute("href")).toBe("/usage");
    expect(screen.getByRole("link", { name: "Insights: spend and activity" }).getAttribute("href")).toBe("/insights");
    expect(screen.getByRole("link", { name: "See cost per agent" }).getAttribute("href")).toBe("/roi");
  });

  it("pages the price list", () => {
    const pager = { page: 1, totalPages: 3, totalItems: 25, pageSize: 10, setPage: vi.fn(), setPageSize: vi.fn() };
    render(<PricesPager pager={pager} />);
    expect(screen.getByText(/25/)).toBeTruthy();
  });
});
