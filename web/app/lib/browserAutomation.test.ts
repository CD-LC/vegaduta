import { describe, expect, it } from "vitest";
import {
  attachableProfiles,
  browserActionCount,
  buildParameterMap,
  daysUntil,
  formatBytes,
  isBrowserRun,
  isPlausibleDomain,
  parseDomainInput,
  profileForAgent,
  profileStatus,
  recipeHealth,
  recipesForAgent,
} from "./browserAutomation";
import type { BrowserProfile, BrowserRecipeSummary } from "./api/browserAutomation";

function profile(over: Partial<BrowserProfile>): BrowserProfile {
  return {
    id: "p1",
    name: "Work",
    scope: "TENANT",
    ownedByMe: false,
    allowedDomains: ["app.example.com"],
    hasState: false,
    stateBytes: 0,
    stateEntries: 0,
    ttlDays: 30,
    expiresAt: null,
    expired: false,
    lastSavedAt: null,
    lastUsedAt: null,
    createdAt: "2026-10-01T00:00:00Z",
    attachedAgentIds: [],
    ...over,
  };
}

function recipe(over: Partial<BrowserRecipeSummary>): BrowserRecipeSummary {
  return {
    id: "r1",
    agentId: null,
    name: "Recipe",
    description: null,
    allowedDomains: ["shop.example.com"],
    parameters: [],
    stepCount: 3,
    runCount: 0,
    failCount: 0,
    lastRunAt: null,
    lastFailure: null,
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    ...over,
  };
}

describe("isPlausibleDomain", () => {
  it("accepts hostnames with two or more labels", () => {
    expect(isPlausibleDomain("example.com")).toBe(true);
    expect(isPlausibleDomain("App.Example.COM")).toBe(true);
    expect(isPlausibleDomain("a-b.example.co.uk")).toBe(true);
  });
  it("rejects bare labels, IPs and junk", () => {
    for (const bad of ["", "com", "localhost", "10.0.0.1", "exa mple.com", "-x.example.com", "a..b.com", "exa_mple.com"]) {
      expect(isPlausibleDomain(bad)).toBe(false);
    }
  });
});

describe("parseDomainInput", () => {
  it("splits on commas, spaces and newlines and de-duplicates in order", () => {
    expect(parseDomainInput("app.example.com, login.example.com\nAPP.example.com;shop.example.org").domains).toEqual([
      "app.example.com",
      "login.example.com",
      "shop.example.org",
    ]);
  });
  it("accepts pasted URLs, wildcards and ports", () => {
    expect(parseDomainInput("https://app.example.com:8443/login?x=1 *.example.org .example.net.").domains).toEqual([
      "app.example.com",
      "example.org",
      "example.net",
    ]);
  });
  it("reports entries it cannot use instead of dropping them silently", () => {
    const result = parseDomainInput("app.example.com, localhost, 10.0.0.1");
    expect(result.domains).toEqual(["app.example.com"]);
    expect(result.invalid).toEqual(["localhost", "10.0.0.1"]);
  });
  it("copes with empty input", () => {
    expect(parseDomainInput("")).toEqual({ domains: [], invalid: [] });
    expect(parseDomainInput("  ,  \n ")).toEqual({ domains: [], invalid: [] });
  });
});

describe("formatBytes", () => {
  it("formats small, medium and large sizes", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(NaN)).toBe("0 B");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(20 * 1024)).toBe("20 KB");
    expect(formatBytes(3 * 1024 * 1024)).toBe("3.0 MB");
  });
});

describe("profileStatus / daysUntil", () => {
  it("tells no login, a live login and a lapsed login apart", () => {
    expect(profileStatus({ hasState: false, expired: false })).toBe("no-login");
    expect(profileStatus({ hasState: true, expired: false })).toBe("active");
    expect(profileStatus({ hasState: true, expired: true })).toBe("expired");
  });
  it("counts whole days to an expiry", () => {
    const now = new Date("2026-10-04T12:00:00Z");
    expect(daysUntil("2026-10-14T12:00:00Z", now)).toBe(10);
    expect(daysUntil("2026-10-03T12:00:00Z", now)).toBe(-1);
    expect(daysUntil(null, now)).toBeNull();
    expect(daysUntil("not a date", now)).toBeNull();
  });
});

describe("profile selection helpers", () => {
  const profiles = [
    profile({ id: "a", name: "Zed shared", attachedAgentIds: ["agent-1"] }),
    profile({ id: "b", name: "Mine", scope: "USER", ownedByMe: true }),
    profile({ id: "c", name: "Alpha shared" }),
  ];
  it("finds the profile an agent runs with", () => {
    expect(profileForAgent(profiles, "agent-1")?.id).toBe("a");
    expect(profileForAgent(profiles, "agent-9")).toBeUndefined();
  });
  it("offers every other profile, shared ones first", () => {
    expect(attachableProfiles(profiles, "agent-1").map((p) => p.id)).toEqual(["c", "b"]);
    expect(attachableProfiles(profiles, "agent-9").map((p) => p.id)).toEqual(["c", "a", "b"]);
  });
});

describe("buildParameterMap", () => {
  const row = (id: string, name: string, value: string) => ({ id, name, value });

  it("keeps valid rows and ignores a fully blank one", () => {
    const result = buildParameterMap([row("1", "email", "alice@example.com"), row("2", "", ""), row("3", " query ", "blue widget")]);
    expect(result.parameters).toEqual({ email: "alice@example.com", query: "blue widget" });
    expect(result.problems).toEqual([]);
  });
  it("flags each kind of problem against its own row", () => {
    const result = buildParameterMap([
      row("1", "1bad", "whatever"),
      row("2", "secret", "whatever"),
      row("3", "ok", "whatever"),
      row("4", "OK", "another value"),
      row("5", "short", "ab"),
      row("6", "novalue", ""),
      row("7", "", "value only"),
    ]);
    expect(result.parameters).toEqual({ ok: "whatever" });
    expect(result.problems).toEqual([
      { rowId: "1", kind: "badName" },
      { rowId: "2", kind: "reservedName" },
      { rowId: "4", kind: "duplicateName" },
      { rowId: "5", kind: "shortValue" },
      { rowId: "6", kind: "shortValue" },
      { rowId: "7", kind: "badName" },
    ]);
  });
});

describe("browser run detection", () => {
  const call = (tool: string) => ({ type: "tool_call", tool });
  it("counts navigate/click/type tool calls only", () => {
    expect(
      browserActionCount([
        call("browser_navigate"),
        call("browser_click"),
        call("browser_read_page_text"),
        { type: "tool_result", tool: "browser_click" },
        call("fetch_web_page"),
        call("browser_type"),
      ]),
    ).toBe(3);
    expect(browserActionCount(undefined)).toBe(0);
    expect(browserActionCount([])).toBe(0);
  });
  it("offers save-as-recipe only for a run with at least two browser actions", () => {
    expect(isBrowserRun([call("browser_navigate")])).toBe(false);
    expect(isBrowserRun([call("browser_navigate"), call("browser_click")])).toBe(true);
    expect(isBrowserRun([call("fetch_web_page"), call("fetch_web_page")])).toBe(false);
  });
});

describe("recipe helpers", () => {
  it("reads health from the last result", () => {
    expect(recipeHealth({ runCount: 0, lastFailure: null })).toBe("new");
    expect(recipeHealth({ runCount: 4, lastFailure: null })).toBe("healthy");
    expect(recipeHealth({ runCount: 4, lastFailure: "step 2: verification failed" })).toBe("failing");
  });
  it("shows an agent its own recipes first, then tenant-wide ones", () => {
    const list = [
      recipe({ id: "t2", name: "Beta", agentId: null }),
      recipe({ id: "o2", name: "Zeta", agentId: "agent-1" }),
      recipe({ id: "x", name: "Other agent", agentId: "agent-2" }),
      recipe({ id: "o1", name: "Alpha", agentId: "agent-1" }),
      recipe({ id: "t1", name: "Aardvark", agentId: null }),
    ];
    expect(recipesForAgent(list, "agent-1").map((r) => r.id)).toEqual(["o1", "o2", "t1", "t2"]);
  });
});
