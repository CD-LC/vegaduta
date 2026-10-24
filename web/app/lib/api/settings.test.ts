import { describe, expect, it, vi } from "vitest";
import {
  SettingsApiError,
  createSettingsClient,
  normalizeCatalog,
  normalizeEffective,
  normalizeHistoryPage,
  parseSettingsError,
  toFieldError,
} from "./settings";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("settings client", () => {
  it("calls the contract endpoints with the right verbs, paths and bodies", async () => {
    const fetcher = vi.fn(async (path: string) =>
      path.includes("catalog") ? json(200, { entries: [] }) : path.includes("history") ? json(200, []) : path.includes("effective") ? json(200, { values: {} }) : new Response(null, { status: 204 })
    );
    const c = createSettingsClient(fetcher);
    await c.getCatalog();
    await c.getEffective();
    await c.getEffective("a b");
    await c.put("tenant", "agent.limits.maxToolSteps", { value: 9, reason: "why" });
    await c.put("agent", "k.x", { value: true }, "ag-1");
    await c.reset("tenant", "k.x");
    await c.reset("agent", "k.x", "ag-1");
    await c.getHistory("k.x", "tenant");
    await c.getHistory("k.x", "agent", "ag-1");

    const calls = fetcher.mock.calls as unknown as [string, { method?: string; body?: string }?][];
    expect(calls.map((x) => [x[1]?.method ?? "GET", x[0]])).toEqual([
      ["GET", "/api/settings/catalog"],
      ["GET", "/api/settings/effective"],
      ["GET", "/api/settings/effective?agentId=a%20b"],
      ["PUT", "/api/settings/tenant/agent.limits.maxToolSteps"],
      ["PUT", "/api/agents/ag-1/settings/k.x"],
      ["DELETE", "/api/settings/tenant/k.x"],
      ["DELETE", "/api/agents/ag-1/settings/k.x"],
      ["GET", "/api/settings/k.x/history?scope=tenant"],
      ["GET", "/api/settings/k.x/history?scope=agent&agentId=ag-1"],
    ]);
    expect(JSON.parse(calls[3][1]!.body!)).toEqual({ value: 9, reason: "why" });
    expect(JSON.parse(calls[4][1]!.body!)).toEqual({ value: true }); // no reason key when none was given
  });

  it("refuses an agent-scope write without an agent id", async () => {
    const c = createSettingsClient(vi.fn());
    await expect(c.put("agent", "k", { value: 1 })).rejects.toThrow(/agentId/);
  });

  it("turns SETTING_INVALID / SETTING_LOCKED bodies into typed field errors", async () => {
    const c = createSettingsClient(
      vi.fn(async (path: string) =>
        path.endsWith("/bad")
          ? json(400, { code: "SETTING_INVALID", key: "bad", message: "must be between 1 and 50", min: 1, max: 50 })
          : path.endsWith("/enum")
            ? json(400, { code: "SETTING_INVALID", key: "enum", message: "not an option", enumValues: ["a", "b"] })
            : path.endsWith("/locked")
              ? json(409, { code: "SETTING_LOCKED", key: "locked", message: "pinned by operator" })
              : path.endsWith("/missing")
                ? json(404, { message: "unknown" })
                : path.endsWith("/forbidden")
                  ? json(403, { error: "nope" })
                  : new Response("<html>bad gateway</html>", { status: 502 })
      )
    );
    const bad = await c.put("tenant", "bad", { value: 99 }).catch((e) => e);
    expect(bad).toBeInstanceOf(SettingsApiError);
    expect(toFieldError(bad)).toMatchObject({ kind: "invalid", key: "bad", min: 1, max: 50, message: "must be between 1 and 50" });
    expect(toFieldError(await c.put("tenant", "enum", { value: "z" }).catch((e) => e))).toMatchObject({ kind: "invalid", enumValues: ["a", "b"] });
    expect(toFieldError(await c.put("tenant", "locked", { value: 1 }).catch((e) => e))).toMatchObject({ kind: "locked", message: "pinned by operator" });
    expect(toFieldError(await c.reset("tenant", "missing").catch((e) => e))).toMatchObject({ kind: "notFound" });
    expect(toFieldError(await c.put("tenant", "forbidden", { value: 1 }).catch((e) => e))).toMatchObject({ kind: "forbidden", message: "nope" });
    const gateway = await c.put("tenant", "x", { value: 1 }).catch((e) => e);
    expect(gateway).toBeInstanceOf(SettingsApiError);
    expect(toFieldError(gateway)).toMatchObject({ kind: "other", message: "HTTP 502" });
  });

  it("classifies a thrown network error", () => {
    expect(toFieldError(new Error("offline"))).toEqual({ kind: "network", message: "offline" });
  });

  it("parseSettingsError tolerates an empty body", async () => {
    const err = await parseSettingsError(new Response(null, { status: 500 }));
    expect(err.status).toBe(500);
    expect(err.message).toBe("HTTP 500");
  });
});

describe("reset reason and the all-keys history", () => {
  const okFetcher = (body: unknown = {}) => vi.fn(async () => json(200, body));
  const calls = (f: ReturnType<typeof vi.fn>) => f.mock.calls as unknown as [string, { method?: string }?][];

  it("sends the reason of a reset as ?reason=, encoded, and nothing when there is none", async () => {
    const f = okFetcher();
    const c = createSettingsClient(f as never);
    await c.reset("tenant", "k.x", undefined, "back & forth: 100%");
    await c.reset("agent", "k.x", "ag-1", "agent retired");
    await c.reset("tenant", "k.x");
    expect(calls(f).map((x) => [x[1]?.method, x[0]])).toEqual([
      ["DELETE", "/api/settings/tenant/k.x?reason=back%20%26%20forth%3A%20100%25"],
      ["DELETE", "/api/agents/ag-1/settings/k.x?reason=agent%20retired"],
      ["DELETE", "/api/settings/tenant/k.x"],
    ]);
  });

  it("asks the workspace route for every key at once, or one agent's own route", async () => {
    const f = okFetcher({ entries: [] });
    const c = createSettingsClient(f as never);
    await c.getHistoryPage();
    await c.getHistoryPage({ scope: "tenant", limit: 100, offset: 200, since: "2026-10-01T00:00:00Z" });
    await c.getHistoryPage({ scope: "agent", limit: 50 });
    await c.getHistoryPage({ scope: "agent", agentId: "ag 1", offset: 10 });
    expect(calls(f).map((x) => x[0])).toEqual([
      "/api/settings/history?scope=all",
      "/api/settings/history?scope=tenant&since=2026-10-01T00%3A00%3A00Z&limit=100&offset=200",
      "/api/settings/history?scope=agent&limit=50",
      "/api/agents/ag%201/settings/history?offset=10",
    ]);
  });

  it("normalizes a page and drops rows it cannot show", () => {
    const page = normalizeHistoryPage({
      entries: [
        { key: "a.b", scope: "agent", agentId: "ag-1", version: 2, action: "RESET", value: null, previousValue: 5, actor: "x", at: "2026-10-01T00:00:00Z", reason: "r" },
        { scope: "tenant", version: 1 },
        null,
        { key: "c.d", version: 1 },
      ],
      total: 9,
      offset: 4,
      limit: 100,
      hasMore: true,
    });
    expect(page.entries).toHaveLength(2);
    expect(page.entries[0]).toMatchObject({ key: "a.b", scope: "agent", agentId: "ag-1", action: "RESET", previousValue: 5, reason: "r" });
    expect(page.entries[1]).toMatchObject({ key: "c.d", scope: "tenant", agentId: undefined, action: "SET", actor: "", reason: null });
    expect(page).toMatchObject({ total: 9, offset: 4, limit: 100, hasMore: true });
    expect(normalizeHistoryPage(null)).toEqual({ entries: [], total: 0, offset: 0, limit: 0, hasMore: false });
  });

  it("surfaces a 403 of the history as forbidden", async () => {
    const c = createSettingsClient(vi.fn(async () => json(403, { message: "A tenant admin is required" })) as never);
    expect(toFieldError(await c.getHistoryPage().catch((e) => e))).toMatchObject({ kind: "forbidden" });
  });
});

describe("strictness", () => {
  it("is carried through from the catalog for security keys and ignored when unknown", () => {
    const c = normalizeCatalog({
      entries: [
        { key: "g.a", type: "int", risk: "security", strictness: "higher_is_stricter" },
        { key: "g.b", type: "int", risk: "security", strictness: "lower_is_stricter" },
        { key: "g.c", type: "enum", risk: "security", strictness: "enum_order" },
        { key: "g.d", type: "int", strictness: "sideways" },
        { key: "g.e", type: "int" },
      ],
    });
    expect(c.entries.map((e) => e.strictness)).toEqual(["higher_is_stricter", "lower_is_stricter", "enum_order", undefined, undefined]);
  });
});

describe("normalizers", () => {
  it("fills safe defaults and drops garbage from the catalog", () => {
    const c = normalizeCatalog({
      entries: [
        { key: "a.b", type: "int", risk: "weird", default: 1 },
        { type: "int" },
        null,
        { key: "c.d", type: "bool", scopes: ["tenant"], minRole: "VIEWER", risk: "security", restartRequired: true },
      ],
    });
    expect(c.entries).toHaveLength(2);
    expect(c.entries[0]).toMatchObject({ key: "a.b", group: "a", risk: "low", scopes: ["tenant", "agent"], minRole: "TENANT_ADMIN", restartRequired: false });
    expect(c.entries[1]).toMatchObject({ scopes: ["tenant"], minRole: "VIEWER", risk: "security", restartRequired: true });
    expect(normalizeCatalog(null).entries).toEqual([]);
  });
  it("normalizes effective values", () => {
    const e = normalizeEffective({ values: { a: { value: 1, source: "tenant", locked: true, lockedReason: "r" }, b: { value: 2, source: "??" }, c: 5 } });
    expect(e.values.a).toEqual({ value: 1, source: "tenant", locked: true, lockedReason: "r" });
    expect(e.values.b).toMatchObject({ source: "default", locked: false });
    expect(e.values.c).toBeUndefined();
  });
});
