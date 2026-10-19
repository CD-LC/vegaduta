import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});

import ApiKeysList from "./ApiKeysList";

const mk = (i: number, over: Record<string, unknown> = {}) =>
  ({
    id: `k${i}`,
    name: `key-${i}`,
    keyPrefix: `pre${i}`,
    permissions: [],
    createdBy: "me",
    enabled: true,
    expired: false,
    expiresAt: null,
    lastUsedAt: null,
    ...over,
  }) as never;

describe("ApiKeysList", () => {
  const noop = () => {};
  it("pages the keys and filters by search and status", () => {
    const keys = [
      ...Array.from({ length: 12 }, (_, i) => mk(i)),
      mk(100, { name: "ci-deploy", enabled: false }),
      mk(101, { name: "old", expired: true }),
    ];
    render(<ApiKeysList keys={keys} busyIds={new Set()} onToggle={noop} onRotate={noop} onRevoke={noop} />);
    // first page holds 10 rows (+ header row)
    expect(screen.getAllByRole("row")).toHaveLength(11);
    fireEvent.change(screen.getByLabelText("Search API keys"), { target: { value: "ci-" } });
    expect(screen.getAllByRole("row")).toHaveLength(2);
    expect(screen.getByText("ci-deploy")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Search API keys"), { target: { value: "" } });
    fireEvent.click(screen.getByLabelText("Status"));
    fireEvent.click(screen.getByRole("option", { name: "Expired" }));
    expect(screen.getAllByRole("row")).toHaveLength(2);
    expect(screen.getByText("old")).toBeTruthy();
  });

  it("shows the no-match state", () => {
    render(<ApiKeysList keys={[mk(1)]} busyIds={new Set()} onToggle={noop} onRotate={noop} onRevoke={noop} />);
    fireEvent.change(screen.getByLabelText("Search API keys"), { target: { value: "zzz" } });
    expect(screen.getByText("Nothing matches the current filters.")).toBeTruthy();
  });
});
