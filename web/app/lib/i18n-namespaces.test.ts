import { describe, expect, it } from "vitest";
import { execFileSync } from "node:child_process";
import path from "node:path";
import { hasAuthedHint, matchRoute, namespacesForRequest, pruneBundle } from "./i18n-namespaces";
import { LAYOUT_NAMESPACES } from "./i18n-route-namespaces.generated";

describe("matchRoute", () => {
  it("matches static routes and the root", () => {
    expect(matchRoute("/")).toBe("/");
    expect(matchRoute("/workflows")).toBe("/workflows");
    expect(matchRoute("/workflows/")).toBe("/workflows");
  });

  it("maps dynamic segments and prefers the more specific static route", () => {
    expect(matchRoute("/workflows/abc123")).toBe("/workflows/[id]");
    expect(matchRoute("/workflows/abc123/runs")).toBe("/workflows/[id]/runs");
    expect(matchRoute("/workflows/templates")).toBe("/workflows/templates");
  });

  it("returns null for unknown paths so the caller ships the full catalog", () => {
    expect(matchRoute("/definitely/not/a/route/at/all")).toBeNull();
    expect(matchRoute(null)).toBeNull();
    expect(matchRoute(undefined)).toBeNull();
  });
});

describe("namespacesForRequest", () => {
  it("always includes the layout namespaces", () => {
    const names = namespacesForRequest("/workflows", false)!;
    for (const ns of LAYOUT_NAMESPACES) expect(names.has(ns)).toBe(true);
    expect(names.has("workflowDesignerPanel")).toBe(true);
  });

  it("drops the landing namespace for a signed-in visitor off the root route", () => {
    expect(namespacesForRequest("/workflows", true)!.has("landing")).toBe(false);
    expect(namespacesForRequest("/workflows", false)!.has("landing")).toBe(true);
  });

  it("keeps the landing namespace on '/' even with the authed hint", () => {
    expect(namespacesForRequest("/", true)!.has("landing")).toBe(true);
  });

  it("inlines the landing nav widgets' namespaces for an anonymous visitor (no full-catalog fetch on '/')", () => {
    const anon = namespacesForRequest("/", false)!;
    for (const ns of ["landing", "languageSwitcher", "themePicker"]) expect(anon.has(ns)).toBe(true);
    const authedElsewhere = namespacesForRequest("/workflows", true)!;
    expect(authedElsewhere.has("languageSwitcher")).toBe(false);
  });

  it("returns null (full catalog) for an unknown pathname", () => {
    expect(namespacesForRequest("/nope/nope/nope/nope", false)).toBeNull();
    expect(namespacesForRequest(null, true)).toBeNull();
  });
});

describe("pruneBundle", () => {
  const bundle = { a: { x: 1 }, b: { y: 2 }, version: "3" };

  it("keeps only the requested namespaces plus scalars", () => {
    expect(pruneBundle(bundle, new Set(["a"]))).toEqual({ a: { x: 1 }, version: "3" });
  });

  it("returns the bundle untouched for a null set", () => {
    expect(pruneBundle(bundle, null)).toBe(bundle);
  });
});

describe("hasAuthedHint", () => {
  it("only trusts the exact cookie value", () => {
    expect(hasAuthedHint("1")).toBe(true);
    expect(hasAuthedHint("0")).toBe(false);
    expect(hasAuthedHint(undefined)).toBe(false);
  });
});

describe("generated route namespace map", () => {
  it("is up to date with the source tree", () => {
    const script = path.resolve(__dirname, "../../scripts/i18n-route-namespaces.mjs");
    expect(() => execFileSync(process.execPath, [script, "--check"], { stdio: "pipe" })).not.toThrow();
  });
});
