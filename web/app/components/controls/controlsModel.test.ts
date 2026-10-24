import { describe, expect, it } from "vitest";
import { CONTROL_AREAS, controlAreaForPath, reachableAreas } from "./controlAreas";
import { matchesQuery, sectionsByAnchor, settingLinks } from "./controlsModel";

describe("controlAreaForPath", () => {
  it("finds the five control pages, with or without a trailing slash or sub-path", () => {
    expect(controlAreaForPath("/admin/runtime-settings")?.id).toBe("runtimeSettings");
    expect(controlAreaForPath("/admin/instructions/")?.id).toBe("prompts");
    expect(controlAreaForPath("/admin/approvals")?.id).toBe("approvals");
    expect(controlAreaForPath("/admin/guardrails")?.id).toBe("guardrails");
    expect(controlAreaForPath("/agents/turns/abc")?.id).toBe("turns");
  });
  it("is null elsewhere, including the agent workbench which has its own header", () => {
    expect(controlAreaForPath("/agents")).toBeNull();
    expect(controlAreaForPath("/admin")).toBeNull();
    expect(controlAreaForPath("/admin/runtime-settings-old")).toBeNull();
    expect(controlAreaForPath(null)).toBeNull();
  });
});

describe("reachableAreas", () => {
  it("hides pages whose scope the person lacks and shows everything to a tenant admin", () => {
    const none = reachableAreas({ isAdmin: false, hasPermission: () => false }).map((a) => a.id);
    expect(none).toEqual(["agentBehavior"]);
    expect(reachableAreas({ isAdmin: true, hasPermission: () => false })).toHaveLength(CONTROL_AREAS.length);
    const approver = reachableAreas({ isAdmin: false, hasPermission: (n) => n === "MANAGE_APPROVALS" }).map((a) => a.id);
    expect(approver).toEqual(["approvals", "agentBehavior"]);
  });
});

describe("settingLinks", () => {
  it("links the workspace page by key and the agent tab with focus, per scope", () => {
    expect(settingLinks({ key: "chat.retry.maxAttempts", scopes: ["tenant"] })).toEqual({ workspace: "/admin/runtime-settings#chat.retry.maxAttempts" });
    expect(settingLinks({ key: "a.b", scopes: ["tenant", "agent"] })).toEqual({
      workspace: "/admin/runtime-settings#a.b",
      agent: "/agents?section=behavior&focus=a.b",
    });
  });
});

describe("sectionsByAnchor / matchesQuery", () => {
  it("groups by docsAnchor in first-seen order and puts the anchorless last", () => {
    const s = sectionsByAnchor([{ docsAnchor: "b" }, { docsAnchor: undefined }, { docsAnchor: "a" }, { docsAnchor: "b" }]);
    expect(s.map((x) => [x.anchor, x.items.length])).toEqual([["b", 2], ["a", 1], ["other", 1]]);
  });
  it("needs every word of the query somewhere in the text", () => {
    expect(matchesQuery("tool time", "Longest time per tool call")).toBe(true);
    expect(matchesQuery("tool memory", "Longest time per tool call")).toBe(false);
    expect(matchesQuery("  ", "x")).toBe(true);
  });
});
