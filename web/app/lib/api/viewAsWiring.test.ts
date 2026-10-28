import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../auth", () => ({
  auth: {
    getToken: vi.fn().mockResolvedValue("admin-token"),
    refreshToken: vi.fn().mockResolvedValue(false),
    getUserId: vi.fn().mockReturnValue(null),
  },
}));

import { apiFetch, clearApiGetCache, toJson } from "./core";
import { pumpChatSse } from "./chatSse";
import { toast } from "../toastBus";
import { __resetViewAsForTests, activateViewAs, exitViewAs } from "../viewAs";

const GRANT = "vag_testgrant_123456";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

beforeEach(async () => {
  window.sessionStorage.clear();
  __resetViewAsForTests();
  await clearApiGetCache();
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("apiFetch + view-as", () => {
  it("sends X-View-As next to the admin bearer only while active", async () => {
    const fetchMock = vi.fn().mockImplementation(async () => jsonResponse(200, { ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    await apiFetch("/api/x", { method: "POST" });
    expect(fetchMock.mock.calls[0][1].headers["X-View-As"]).toBeUndefined();
    activateViewAs(GRANT, null);
    await apiFetch("/api/x", { method: "POST" });
    expect(fetchMock.mock.calls[1][1].headers).toMatchObject({
      Authorization: "Bearer admin-token",
      "X-View-As": GRANT,
    });
    // A caller-named grant (validating a new link) wins over the active one.
    await apiFetch("/api/view-as/session", { headers: { "X-View-As": "vag_other_grant_9" } });
    expect(fetchMock.mock.calls[2][1].headers["X-View-As"]).toBe("vag_other_grant_9");
    exitViewAs();
    await apiFetch("/api/x", { method: "POST" });
    expect(fetchMock.mock.calls[3][1].headers["X-View-As"]).toBeUndefined();
  });

  it("entering view-as drops cached GETs from the admin's own identity", async () => {
    let n = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(async () => jsonResponse(200, { n: ++n }))
    );
    expect(await apiFetch("/api/agents").then(toJson)).toEqual({ n: 1 });
    expect(await apiFetch("/api/agents").then(toJson)).toEqual({ n: 1 });
    activateViewAs(GRANT, null);
    expect(await apiFetch("/api/agents").then(toJson)).toEqual({ n: 2 });
  });
});

describe("toJson guard refusals", () => {
  it("402 spend_cap_reached -> clear message, warning toast, no generic error toast", async () => {
    const warn = vi.spyOn(toast, "warn");
    const error = vi.spyOn(toast, "error");
    const res = jsonResponse(402, {
      error: "spend_cap_reached",
      capUsd: 50,
      monthToDateUsd: 50,
      resetsAt: "2026-11-01T00:00:00Z",
    });
    await expect(toJson(res)).rejects.toMatchObject({ status: 402, code: "spend_cap_reached" });
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("monthly AI spend limit ($50)"));
    expect(error).not.toHaveBeenCalled();
  });

  it("403 impersonation_read_only -> read-only info toast", async () => {
    const info = vi.spyOn(toast, "info");
    const error = vi.spyOn(toast, "error");
    await expect(toJson(jsonResponse(403, { error: "impersonation_read_only" }))).rejects.toMatchObject({
      readOnly: true,
    });
    expect(info).toHaveBeenCalledWith("Read-only view: changes are disabled");
    expect(error).not.toHaveBeenCalled();
  });

  it("403 tenant_missing -> throws marked error, NO toast (OnboardingGate owns that state)", async () => {
    // Regression: the missing-tenant 403 body told users to "sign out and back
    // in" via the global error toast on every tenant-scoped call - which loops
    // forever for an account whose onboarding has not run.
    const info = vi.spyOn(toast, "info");
    const warn = vi.spyOn(toast, "warn");
    const error = vi.spyOn(toast, "error");
    const body = {
      timestamp: "2026-10-28T00:00:00Z",
      status: 403,
      error: "Your account is not linked to an organization. Please sign out and back in; if this persists, contact support.",
    };
    await expect(toJson(jsonResponse(403, body))).rejects.toMatchObject({
      status: 403,
      code: "tenant_missing",
      tenantMissing: true,
    });
    expect(info).not.toHaveBeenCalled();
    expect(warn).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
  });
});

describe("chat SSE spend cap", () => {
  function sse(text: string): Response {
    return new Response(text, { status: 200, headers: { "content-type": "text/event-stream" } });
  }
  const ctx: any = { onChunk: vi.fn(), signal: new AbortController().signal };

  it("an SSE error event with the 402 JSON throws the spend-cap message (not resumable)", async () => {
    const chunks: string[] = [];
    const payload = JSON.stringify({ error: "spend_cap_reached", capUsd: 20, resetsAt: "2026-11-01T00:00:00Z" });
    await expect(
      pumpChatSse(sse(`data:Hello\n\nevent:error\ndata:${payload}\n\n`), ctx, {
        onChunk: (c: string) => chunks.push(c),
      } as any)
    ).rejects.toMatchObject({ code: "spend_cap_reached", streamMarker: true });
    expect(chunks).toEqual(["Hello"]);
  });
});
