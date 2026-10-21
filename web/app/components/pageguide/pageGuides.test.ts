import { describe, expect, it } from "vitest";
import { ALL_PAGE_GUIDES, addSeen, guideForPath, parseSeen } from "./pageGuides";

describe("guideForPath", () => {
  it("routes the specific page before its parent", () => {
    expect(guideForPath("/agents")?.id).toBe("agents");
    expect(guideForPath("/agents/templates")?.id).toBe("agent-templates");
    expect(guideForPath("/workflows")?.id).toBe("workflows");
    expect(guideForPath("/workflows/templates")?.id).toBe("workflow-templates");
    expect(guideForPath("/workflows/abc-123")?.id).toBe("workflow-canvas");
    expect(guideForPath("/workflows/abc-123/runs")?.id).toBe("workflow-runs");
  });

  it("does not confuse /code with /code-intel", () => {
    expect(guideForPath("/code-intel")?.id).toBe("code-intel");
    expect(guideForPath("/code", { edgeAvailable: true })?.id).toBe("code");
  });

  it("ignores a trailing slash and answers null for unknown or empty paths", () => {
    expect(guideForPath("/agents/")?.id).toBe("agents");
    expect(guideForPath("/")?.id).toBe("home");
    expect(guideForPath("/no-such-page")).toBeNull();
    expect(guideForPath("")).toBeNull();
    expect(guideForPath(null)).toBeNull();
  });

  it("hides admin-only guides from members, and unknown role counts as member", () => {
    expect(guideForPath("/admin/channels")).toBeNull();
    expect(guideForPath("/admin/channels", { isAdmin: false })).toBeNull();
    expect(guideForPath("/admin/channels", { isAdmin: true })?.id).toBe("channels");
    expect(guideForPath("/admin/guardrails", { isAdmin: true })?.id).toBe("guardrails");
    expect(guideForPath("/admin/anything-else", { isAdmin: true })?.id).toBe("admin");
  });

  it("hides on-device guides when the deployment has no on-device AI", () => {
    expect(guideForPath("/edge")).toBeNull();
    expect(guideForPath("/edge", { edgeAvailable: true })?.id).toBe("edge");
  });

  it("routes the newer surfaces to their own guides before the parents", () => {
    expect(guideForPath("/home")?.id).toBe("home");
    expect(guideForPath("/agents/quality")?.id).toBe("agent-quality");
    expect(guideForPath("/agents/learning")?.id).toBe("agent-learning");
    expect(guideForPath("/learn/tutor")?.id).toBe("learn-tutor");
    expect(guideForPath("/learn")?.id).toBe("help");
    expect(guideForPath("/agents/x")?.id).toBe("agents");
    expect(guideForPath("/data-usage")?.id).toBe("data-usage");
    expect(guideForPath("/skills")?.id).toBe("skills");
    expect(guideForPath("/providers")?.id).toBe("providers");
    expect(guideForPath("/admin/contacts", { isAdmin: true })?.id).toBe("contacts");
    expect(guideForPath("/admin/audit-tail", { isAdmin: true })?.id).toBe("audit");
    expect(guideForPath("/admin/access", { isAdmin: true })?.id).toBe("access");
    expect(guideForPath("/admin/anything-else", { isAdmin: true })?.id).toBe("admin");
  });
});

describe("guide content", () => {
  it("has unique ids", () => {
    const ids = ALL_PAGE_GUIDES.map((g) => g.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("stays minimal: 2-4 steps, short sentences, internal next links", () => {
    for (const g of ALL_PAGE_GUIDES) {
      expect(g.steps.length, g.id).toBeGreaterThanOrEqual(2);
      expect(g.steps.length, g.id).toBeLessThanOrEqual(4);
      for (const s of [g.title, g.purpose, ...g.steps]) {
        expect(s.trim(), g.id).not.toBe("");
        expect(s.length, `${g.id}: ${s}`).toBeLessThanOrEqual(190);
      }
      if (g.next) expect(g.next.href.startsWith("/"), g.id).toBe(true);
    }
  });

  it("tells a first-time user the true minimum for an agent and a workflow", () => {
    expect(guideForPath("/agents")?.steps[0]).toMatch(/only thing you must fill in/);
    expect(guideForPath("/workflows")?.steps.join(" ")).toMatch(/only thing you must fill in/);
  });

  // The Academy is a separate, proprietary deployable: the OSS console only
  // ever links to its origin, never names a lesson id. This guard keeps it
  // that way — the day a "lN-M" id reappears in a guide, the private
  // curriculum is leaking back into the open repo.
  it("keeps the guides free of Academy lesson ids", () => {
    for (const g of ALL_PAGE_GUIDES) {
      expect(JSON.stringify(g), g.id).not.toMatch(/"learnLessonId"|"l\d+-\d+"/);
    }
  });
});

describe("seen persistence", () => {
  it("survives garbage in storage", () => {
    expect(parseSeen(null)).toEqual([]);
    expect(parseSeen("not json")).toEqual([]);
    expect(parseSeen('{"a":1}')).toEqual([]);
    expect(parseSeen('["agents", 3, null, "home"]')).toEqual(["agents", "home"]);
  });

  it("adds an id once", () => {
    expect(addSeen(["a"], "b")).toEqual(["a", "b"]);
    const same = ["a"];
    expect(addSeen(same, "a")).toBe(same);
  });
});
