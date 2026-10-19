import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../../messages/en.json";
import {
  READ_ONLY_ERROR,
  VIEW_AS_HEADER,
  __resetViewAsForTests,
  activateViewAs,
  applyViewAsHeader,
  classifyViewAsStatus,
  clearPendingGrant,
  exitViewAs,
  formatCountdown,
  getViewAsGrant,
  isPlausibleGrant,
  isReadOnlyBody,
  isViewAsActive,
  msUntil,
  normalizeSession,
  parseGrantFromHash,
  peekPendingGrant,
  sessionExpired,
  stashPendingGrant,
  subscribeViewAs,
  updateViewAsSession,
  viewAsHeaders,
} from "./viewAs";

const GRANT = "vag_abcDEF123-_.~+/=xyz";
const session = {
  id: "s1",
  tenantName: "Acme",
  userEmail: "a@acme.test",
  expiresAt: "2026-10-09T10:30:00Z",
  readOnly: true,
};

beforeEach(() => {
  window.sessionStorage.clear();
  window.localStorage.clear();
  __resetViewAsForTests();
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe("parseGrantFromHash", () => {
  it("reads the grant from the fragment", () => {
    expect(parseGrantFromHash("#grant=abcdefgh12")).toBe("abcdefgh12");
    expect(parseGrantFromHash("grant=abcdefgh12&x=1")).toBe("abcdefgh12");
  });
  it("keeps a literal + that URLSearchParams would turn into a space", () => {
    expect(parseGrantFromHash("#grant=ab+cd/ef==")).toBe("ab+cd/ef==");
  });
  it("refuses missing, short or header-unsafe values", () => {
    expect(parseGrantFromHash("")).toBeNull();
    expect(parseGrantFromHash("#")).toBeNull();
    expect(parseGrantFromHash("#other=abcdefgh12")).toBeNull();
    expect(parseGrantFromHash("#grant=short")).toBeNull();
    expect(parseGrantFromHash("#grant=abc%0Adefghij")).toBeNull();
    expect(parseGrantFromHash('#grant=abc"defghij')).toBeNull();
    expect(isPlausibleGrant(null)).toBe(false);
  });
});

describe("store", () => {
  it("is inactive by default and adds no header", () => {
    expect(isViewAsActive()).toBe(false);
    expect(viewAsHeaders()).toEqual({});
    expect(applyViewAsHeader({ Authorization: "Bearer t" })).toEqual({ Authorization: "Bearer t" });
  });

  it("activates into sessionStorage (never localStorage) and adds X-View-As", () => {
    const fn = vi.fn();
    subscribeViewAs(fn);
    activateViewAs(GRANT, session);
    expect(fn).toHaveBeenCalledTimes(1);
    expect(getViewAsGrant()).toBe(GRANT);
    expect(viewAsHeaders()).toEqual({ [VIEW_AS_HEADER]: GRANT });
    expect(applyViewAsHeader({ Authorization: "Bearer t" })).toEqual({ Authorization: "Bearer t", "X-View-As": GRANT });
    expect(JSON.stringify(window.localStorage)).not.toContain(GRANT);
    expect(Object.values(window.sessionStorage).join("|")).toContain(GRANT);
  });

  it("re-loads from sessionStorage after a reload", () => {
    activateViewAs(GRANT, session);
    __resetViewAsForTests();
    expect(getViewAsGrant()).toBe(GRANT);
  });

  it("refuses a malformed grant", () => {
    activateViewAs("bad grant", session);
    expect(isViewAsActive()).toBe(false);
  });

  it("exit clears grant, session and pending, and notifies", () => {
    activateViewAs(GRANT, session);
    stashPendingGrant(GRANT);
    const fn = vi.fn();
    subscribeViewAs(fn);
    exitViewAs();
    expect(fn).toHaveBeenCalledTimes(1);
    expect(isViewAsActive()).toBe(false);
    expect(peekPendingGrant()).toBeNull();
    expect(window.sessionStorage.length).toBe(0);
    exitViewAs();
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it("updateViewAsSession only applies while active", () => {
    updateViewAsSession(session);
    expect(isViewAsActive()).toBe(false);
    activateViewAs(GRANT, null);
    updateViewAsSession({ ...session, tenantName: "Beta" });
    __resetViewAsForTests();
    expect(JSON.parse(window.sessionStorage.getItem("vd.viewAs.session") || "{}").tenantName).toBe("Beta");
  });

  it("survives a storage that throws", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(() => activateViewAs(GRANT, session)).not.toThrow();
    expect(getViewAsGrant()).toBe(GRANT);
    expect(() => exitViewAs()).not.toThrow();
  });

  it("pending grant round-trip", () => {
    stashPendingGrant("x");
    expect(peekPendingGrant()).toBeNull();
    stashPendingGrant(GRANT);
    expect(peekPendingGrant()).toBe(GRANT);
    clearPendingGrant();
    expect(peekPendingGrant()).toBeNull();
  });
});

describe("countdown + expiry", () => {
  const now = Date.parse("2026-10-09T10:00:00Z");
  it("formats mm:ss and clamps at zero", () => {
    expect(formatCountdown(30 * 60_000)).toBe("30:00");
    expect(formatCountdown(65_400)).toBe("01:06");
    expect(formatCountdown(9_000)).toBe("00:09");
    expect(formatCountdown(-5)).toBe("00:00");
    expect(formatCountdown(null)).toBe("--:--");
    expect(formatCountdown(Number.NaN)).toBe("--:--");
  });
  it("msUntil / sessionExpired", () => {
    expect(msUntil(session.expiresAt, now)).toBe(30 * 60_000);
    expect(msUntil("nope", now)).toBeNull();
    expect(msUntil(null, now)).toBeNull();
    expect(sessionExpired(session, now)).toBe(false);
    expect(sessionExpired(session, Date.parse(session.expiresAt))).toBe(true);
    expect(sessionExpired({ ...session, expiresAt: null }, now)).toBe(false);
  });
});

describe("response helpers", () => {
  it("normalizeSession", () => {
    expect(normalizeSession(null)).toBeNull();
    expect(normalizeSession({ id: "1", tenantName: "", userEmail: "u@x", expiresAt: "t" })).toEqual({
      id: "1",
      tenantName: null,
      userEmail: "u@x",
      expiresAt: "t",
      readOnly: true,
    });
  });
  it("classifyViewAsStatus", () => {
    expect(classifyViewAsStatus(200)).toBe("ok");
    expect(classifyViewAsStatus(401)).toBe("invalid");
    expect(classifyViewAsStatus(410)).toBe("invalid");
    expect(classifyViewAsStatus(404)).toBe("invalid");
    expect(classifyViewAsStatus(503)).toBe("error");
    expect(classifyViewAsStatus(403)).toBe("error");
  });
  it("isReadOnlyBody", () => {
    expect(isReadOnlyBody(403, { error: READ_ONLY_ERROR })).toBe(true);
    expect(isReadOnlyBody(403, { error: "forbidden" })).toBe(false);
    expect(isReadOnlyBody(400, { error: READ_ONLY_ERROR })).toBe(false);
    expect(isReadOnlyBody(403, null)).toBe(false);
  });
});

describe("messages", () => {
  it("has every viewAs key the banner and page use", () => {
    const ns = (en as any).viewAs;
    for (const k of [
      "bannerViewing",
      "bannerReadOnly",
      "bannerExpiresIn",
      "exit",
      "checkingTitle",
      "invalidTitle",
      "endedTitle",
      "backToConsole",
    ]) {
      expect(typeof ns[k]).toBe("string");
    }
    expect(ns.bannerViewing).toContain("{email}");
    expect(ns.bannerViewing).toContain("{tenant}");
    expect(ns.bannerExpiresIn).toContain("{time}");
  });
});

describe("chat state during view-as", () => {
  it("purges this tab's in-flight turn records on enter and on exit", async () => {
    const { savePendingTurn, loadPendingTurn, pendingTurnKey } = await import("./resumableStream");
    const turn = {
      turnId: "t1",
      agentId: "a1",
      sessionId: null as string | null,
      userMessage: "admin's own",
      startedAt: Date.now(),
    };
    savePendingTurn(turn);
    expect(window.sessionStorage.getItem(pendingTurnKey("a1"))).not.toBeNull();

    activateViewAs(GRANT, session);
    expect(window.sessionStorage.getItem(pendingTurnKey("a1"))).toBeNull();

    // Nothing is written or restored while viewing as someone else.
    savePendingTurn({ ...turn, userMessage: "viewed user's" });
    expect(window.sessionStorage.getItem(pendingTurnKey("a1"))).toBeNull();
    expect(loadPendingTurn("a1")).toBeNull();

    // A record that slipped in some other way is gone after exit.
    window.sessionStorage.setItem(pendingTurnKey("a2"), "{}");
    exitViewAs();
    expect(window.sessionStorage.getItem(pendingTurnKey("a2"))).toBeNull();
  });

  it("keeps the admin's own build drafts but hides and never writes them while viewing", async () => {
    const { saveDraft, loadDraft, saveThread, loadThread } = await import("../build/chatStore");
    saveDraft("p1", "admin draft");
    activateViewAs(GRANT, session);

    expect(loadDraft("p1")).toBe("");
    saveDraft("p1", "viewed user's draft");
    saveThread("p2", [{ kind: "user", text: "viewed" }]);
    expect(loadThread("p2")).toEqual([]);

    exitViewAs();
    expect(loadDraft("p1")).toBe("admin draft");
    expect(window.localStorage.getItem("vd.build.chat.p2")).toBeNull();
  });
});
