// The quarantine review on a fact row: a shared (tenant-wide) fact has an Approve button next to
// Reject, like a private fact does, and the button is absent when the page passes no handler.

import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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

const base: MemoryFact = {
  id: "f1",
  scope: "shared",
  agentId: "a1",
  content: "Priya's manager is Raj",
  createdAt: "2026-10-01T10:00:00Z",
  useCount: 0,
  trust: "QUARANTINED",
};

function renderRow(fact: MemoryFact, onApprove?: (f: MemoryFact) => Promise<void>) {
  return render(
    <FactRow
      fact={fact}
      agentName="Alpha"
      agentNames={{ a1: "Alpha" }}
      maxChars={500}
      onEdit={vi.fn()}
      onDelete={vi.fn()}
      onApprove={onApprove}
    />
  );
}

describe("FactRow quarantine review", () => {
  it("offers Approve and Reject on a quarantined shared fact", async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    renderRow(base, onApprove);
    expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(onApprove).toHaveBeenCalledWith(base));
  });

  it("offers Approve on a shared fact even when the writing agent is unknown", () => {
    renderRow({ ...base, agentId: null }, vi.fn());
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
  });

  it("still offers Approve on a private quarantined fact with an agent", () => {
    renderRow({ ...base, scope: "agent" }, vi.fn());
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
  });

  it("does not offer Approve on a private fact without an agent", () => {
    renderRow({ ...base, scope: "agent", agentId: null }, vi.fn());
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it("shows no Approve on a shared fact without a handler, and no review strip on a trusted fact", () => {
    const { unmount } = renderRow(base, undefined);
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    unmount();
    renderRow({ ...base, trust: "TRUSTED" }, vi.fn());
    expect(screen.queryByTestId("memory-fact-quarantined")).not.toBeInTheDocument();
  });
});
