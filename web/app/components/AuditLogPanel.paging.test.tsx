import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import en from "../../messages/en.json";

vi.mock("../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});
const listAuditLogPage = vi.fn();
vi.mock("../lib/api", () => ({
  api: {
    listAuditLogPage: (...a: unknown[]) => listAuditLogPage(...a),
    listAuditLogActionTypes: () => Promise.resolve([]),
  },
}));

import AuditLogPanel from "./AuditLogPanel";

describe("AuditLogPanel server paging", () => {
  beforeEach(() => {
    listAuditLogPage.mockReset();
    listAuditLogPage.mockResolvedValue({
      rows: [
        {
          id: "1",
          createdAt: new Date().toISOString(),
          actorId: "u1",
          actionType: "agent.create",
          targetType: "agent",
          targetId: "x",
          outcome: "SUCCESS",
          detail: null as string | null,
        },
      ],
      total: 4321,
      hasMore: true,
    });
  });

  it("asks the server for limit/offset and labels the export as this page", async () => {
    render(<AuditLogPanel />);
    await screen.findByText("agent.create");
    const arg = listAuditLogPage.mock.calls[0][0];
    expect(arg.limit).toBe(25);
    expect(arg.offset).toBe(0);
    expect(arg.from).toBeTruthy();
    expect(screen.getByRole("button", { name: /Export this page/ })).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByText(/4,?321/).length).toBeGreaterThan(0));
  });
});
