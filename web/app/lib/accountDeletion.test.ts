import { describe, it, expect, vi, beforeEach } from "vitest";

const m = vi.hoisted(() => ({
  login: vi.fn<(opts?: Record<string, unknown>) => Promise<void>>(() => Promise.resolve()),
  getAuthTimeMs: vi.fn<() => number | null>(() => null),
  deleteAccount: vi.fn<() => Promise<unknown>>(),
}));

vi.mock("./auth", () => ({ auth: { login: m.login, getAuthTimeMs: m.getAuthTimeMs } }));
vi.mock("./api/account", () => ({ accountApi: { deleteAccount: m.deleteAccount } }));

import {
  buildReauthReturnUrl,
  consumePrearmedDeletion,
  deleteAccountOrReauth,
  hasConfirmDeleteMarker,
  stripConfirmDeleteMarker,
} from "./accountDeletion";
import { isReauthRequired, reauthMaxAgeSeconds } from "./errors";
import { toJson } from "./api/core";

const apiErr = (status: number, extra: Record<string, unknown> = {}) => Object.assign(new Error("x"), { status, ...extra });

beforeEach(() => {
  vi.clearAllMocks();
  window.history.replaceState(null, "", "/profile");
});

describe("isReauthRequired / reauthMaxAgeSeconds", () => {
  it("matches only a 401 carrying code reauth_required", () => {
    expect(isReauthRequired(apiErr(401, { code: "reauth_required" }))).toBe(true);
    expect(isReauthRequired(apiErr(401))).toBe(false);
    expect(isReauthRequired(apiErr(401, { code: "other" }))).toBe(false);
    expect(isReauthRequired(apiErr(403, { code: "reauth_required" }))).toBe(false);
    expect(isReauthRequired(null)).toBe(false);
    expect(isReauthRequired("reauth_required")).toBe(false);
  });
  it("reads the max_age hint", () => {
    expect(reauthMaxAgeSeconds(apiErr(401, { maxAge: 900 }))).toBe(900);
    expect(reauthMaxAgeSeconds(apiErr(401, { maxAge: "900" }))).toBeUndefined();
    expect(reauthMaxAgeSeconds(undefined)).toBeUndefined();
  });
});

describe("toJson keeps code and max_age from core's 401 body", () => {
  it("parses the RFC 9457 body into a guard-matching error", async () => {
    const res = new Response(
      JSON.stringify({ status: 401, detail: "Please sign in again to delete your account.", code: "reauth_required", max_age: 900 }),
      { status: 401, headers: { "content-type": "application/problem+json" } },
    );
    const err = await toJson(res).then(
      (): unknown => null,
      (e: unknown): unknown => e,
    );
    expect(isReauthRequired(err)).toBe(true);
    expect(reauthMaxAgeSeconds(err)).toBe(900);
    expect((err as Error).message).toBe("Please sign in again to delete your account.");
  });
  it("parses the legacy {error, code} body", async () => {
    const res = new Response(JSON.stringify({ error: "Recent sign-in required", code: "reauth_required", max_age: 60 }), { status: 401 });
    const err = await toJson(res).then(
      (): unknown => null,
      (e: unknown): unknown => e,
    );
    expect(isReauthRequired(err)).toBe(true);
    expect((err as Error).message).toBe("Recent sign-in required");
  });
});

describe("marker helpers", () => {
  it("builds a return URL with the marker and no hash", () => {
    const u = new URL(buildReauthReturnUrl("https://app.example.com/profile?tab=a#state=1&code=2"));
    expect(u.searchParams.get("confirmDelete")).toBe("1");
    expect(u.searchParams.get("tab")).toBe("a");
    expect(u.hash).toBe("");
  });
  it("detects and strips the marker", () => {
    expect(hasConfirmDeleteMarker("https://a.b/p?confirmDelete=1")).toBe(true);
    expect(hasConfirmDeleteMarker("https://a.b/p?confirmDelete=0")).toBe(false);
    expect(hasConfirmDeleteMarker("not a url")).toBe(false);
    expect(stripConfirmDeleteMarker("https://a.b/p?x=1&confirmDelete=1")).toBe("https://a.b/p?x=1");
  });
});

describe("deleteAccountOrReauth", () => {
  it("returns deleted when core accepts, without any redirect", async () => {
    m.deleteAccount.mockResolvedValue(null);
    await expect(deleteAccountOrReauth()).resolves.toBe("deleted");
    expect(m.login).not.toHaveBeenCalled();
  });

  it("on reauth_required redirects with prompt=login, maxAge 0 and the marker; deletes only once", async () => {
    m.deleteAccount.mockRejectedValue(apiErr(401, { code: "reauth_required", maxAge: 900 }));
    window.history.replaceState(null, "", "/profile?tab=a");
    await expect(deleteAccountOrReauth()).resolves.toBe("reauth");
    expect(m.deleteAccount).toHaveBeenCalledTimes(1);
    expect(m.login).toHaveBeenCalledTimes(1);
    const opts = m.login.mock.calls[0][0] as { prompt: string; maxAge: number; redirectUri: string };
    expect(opts.prompt).toBe("login");
    expect(opts.maxAge).toBe(0);
    expect(new URL(opts.redirectUri).searchParams.get("confirmDelete")).toBe("1");
    expect(new URL(opts.redirectUri).searchParams.get("tab")).toBe("a");
  });

  it("rethrows other failures without redirecting", async () => {
    const boom = apiErr(500);
    m.deleteAccount.mockRejectedValue(boom);
    await expect(deleteAccountOrReauth()).rejects.toBe(boom);
    m.deleteAccount.mockRejectedValue(apiErr(401));
    await expect(deleteAccountOrReauth()).rejects.toBeTruthy();
    expect(m.login).not.toHaveBeenCalled();
  });
});

describe("consumePrearmedDeletion (return from Keycloak)", () => {
  it("pre-arms on marker + fresh auth_time, strips the marker, and never deletes", () => {
    window.history.replaceState(null, "", "/profile?confirmDelete=1&tab=a");
    m.getAuthTimeMs.mockReturnValue(Date.now() - 5_000);
    expect(consumePrearmedDeletion()).toBe(true);
    expect(window.location.search).toBe("?tab=a");
    expect(m.deleteAccount).not.toHaveBeenCalled();
    // A reload (marker gone) does not re-arm.
    expect(consumePrearmedDeletion()).toBe(false);
  });

  it("does not arm without the marker", () => {
    m.getAuthTimeMs.mockReturnValue(Date.now());
    expect(consumePrearmedDeletion()).toBe(false);
  });

  it("does not arm when the token is stale or has no auth_time, but still strips the marker", () => {
    window.history.replaceState(null, "", "/profile?confirmDelete=1");
    m.getAuthTimeMs.mockReturnValue(Date.now() - 3_600_000);
    expect(consumePrearmedDeletion()).toBe(false);
    expect(window.location.search).toBe("");
    window.history.replaceState(null, "", "/profile?confirmDelete=1");
    m.getAuthTimeMs.mockReturnValue(null);
    expect(consumePrearmedDeletion()).toBe(false);
    expect(m.deleteAccount).not.toHaveBeenCalled();
  });
});
