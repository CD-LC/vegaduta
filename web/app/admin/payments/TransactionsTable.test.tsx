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

import TransactionsTable from "./TransactionsTable";

const txn = (i: number, status: string) =>
  ({
    id: `t${i}`,
    mandateId: "m1",
    amountPaise: 100 * (i + 1),
    type: "PAY",
    status,
    vpa: `v${i}@bank`,
    description: `desc-${i}`,
    createdAt: "2026-01-01T00:00:00Z",
  }) as never;

describe("TransactionsTable", () => {
  it("paginates and filters by status", () => {
    const rows = [...Array.from({ length: 14 }, (_, i) => txn(i, "CAPTURED")), txn(99, "FAILED")];
    render(<TransactionsTable transactions={rows} />);
    expect(screen.getAllByRole("row")).toHaveLength(11);
    fireEvent.click(screen.getByRole("button", { name: "FAILED" }));
    expect(screen.getAllByRole("row")).toHaveLength(2);
    expect(screen.getByText("desc-99")).toBeTruthy();
  });
});
