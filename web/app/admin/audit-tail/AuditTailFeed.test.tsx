import { describe, expect, it, vi, beforeEach } from "vitest";
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
const listAuditLogPage = vi.fn();
vi.mock("../../lib/api", () => ({ api: { listAuditLogPage: (...a: unknown[]) => listAuditLogPage(...a) } }));

import AuditTailFeed from "./AuditTailFeed";

const entry = (id: string, actionType: string) => ({
  id,
  createdAt: new Date().toISOString(),
  actorId: "u1",
  actionType,
  targetType: "agent",
  targetId: id,
  outcome: "SUCCESS",
  detail: null as string | null,
});

describe("AuditTailFeed", () => {
  beforeEach(() => {
    listAuditLogPage.mockReset();
    listAuditLogPage.mockResolvedValue({
      rows: [entry("a", "agent.create"), entry("b", "tool.call")],
      total: 120,
      hasMore: true,
    });
  });

  it("requests the newest server page and offers paging", async () => {
    render(<AuditTailFeed />);
    await screen.findByText("agent.create");
    expect(listAuditLogPage).toHaveBeenCalledWith({ limit: 50, offset: 0 });
    expect(screen.getByText(/120/)).toBeInTheDocument();
  });

  it("filters the loaded page client-side", async () => {
    render(<AuditTailFeed />);
    await screen.findByText("agent.create");
    fireEvent.change(screen.getByLabelText("Filter this page"), { target: { value: "tool" } });
    expect(screen.queryByText("agent.create")).not.toBeInTheDocument();
    expect(screen.getByText("tool.call")).toBeInTheDocument();
  });

  it("shows an error with Retry", async () => {
    listAuditLogPage.mockRejectedValueOnce(new Error("down"));
    render(<AuditTailFeed />);
    fireEvent.click(await screen.findByRole("button", { name: "Retry" }));
    await screen.findByText("agent.create");
  });
});
