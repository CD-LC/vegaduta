// A fact a newer one replaced stays on the Memory page (the person can delete it), marked so it is not mistaken for
// something the assistant still uses.

import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import en from "../../messages/en.json";

vi.mock("../lib/i18n", () => ({
  useTranslations:
    (namespace: string) =>
    (key: string, values?: Record<string, unknown>): string => {
      const template =
        (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key] ?? `${namespace}.${key}`;
      return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
    },
  useLocale: () => "en-GB",
}));
vi.mock("../lib/api", () => ({ api: { getMemoryFactSource: vi.fn() } }));

import FactRow from "./FactRow";
import type { MemoryFact } from "./memoryTypes";

const live: MemoryFact = {
  id: "f1",
  scope: "agent",
  agentId: "a1",
  content: "I live in Mumbai",
  createdAt: "2026-10-01T10:00:00Z",
  useCount: 0,
  trust: "TRUSTED",
};

function renderRow(fact: MemoryFact) {
  return render(
    <FactRow
      fact={fact}
      agentName="Alpha"
      agentNames={{ a1: "Alpha" }}
      maxChars={500}
      onEdit={vi.fn()}
      onDelete={vi.fn()}
    />
  );
}

describe("FactRow superseded fact", () => {
  it("says a replaced fact is kept but no longer used", () => {
    renderRow({ ...live, content: "I live in Pune", supersededAt: "2026-10-02T10:00:00Z" });
    const hint = screen.getByTestId("memory-fact-superseded");
    expect(hint).toHaveTextContent("Replaced by a newer fact");
    expect(hint).toHaveTextContent("no longer uses it");
  });

  it("shows nothing extra on a live fact", () => {
    renderRow(live);
    expect(screen.queryByTestId("memory-fact-superseded")).not.toBeInTheDocument();
  });
});
