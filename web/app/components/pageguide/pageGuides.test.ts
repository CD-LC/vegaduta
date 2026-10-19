import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { CONSOLE_SCREEN_LESSONS } from "../../learn/consoleLessonMap";
import { ALL_PAGE_GUIDES, addSeen, guideForPath, parseSeen } from "./pageGuides";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

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

  // The Academy is a separate deployable that is not part of this repository, so this
  // cross-repo guard only runs where its seed sources are checked out beside the console.
  const ACADEMY_SRC = path.resolve(__dirname, "../../../../vegaduta-webapp-prototype/vegaduta-webapp/src");
  it.skipIf(!existsSync(ACADEMY_SRC))("every Academy lesson link points at a lesson that is actually seeded", () => {
    // Cross-repo guard, the mirror image of the Academy's own
    // test/webapp-links-routes.test.ts (which checks Academy→console links
    // against web/app/<path>/page.tsx on disk). A learnLessonId or
    // consoleLessonMap id that no seed file declares renders the Academy's
    // 404 — a dead link is worse than no link.
    const srcDir = path.resolve(
      __dirname,
      "../../../../vegaduta-webapp-prototype/vegaduta-webapp/src",
    );
    const seedFiles = readdirSync(srcDir).filter(
      (f) => f === "seed.ts" || /^seed-level-\d+\.ts$/.test(f),
    );
    expect(seedFiles.length).toBeGreaterThan(1);
    const seededIds = new Set<string>();
    for (const f of seedFiles) {
      const text = readFileSync(path.join(srcDir, f), "utf8");
      for (const m of text.matchAll(/^\s*id: "(l[\w-]+)"/gm)) seededIds.add(m[1]);
    }
    expect(seededIds.size).toBeGreaterThan(200);

    for (const g of ALL_PAGE_GUIDES) {
      if (!g.learnLessonId) continue;
      expect(g.learnLessonId, g.id).toMatch(/^l\d+-[\w-]+$/);
      expect(seededIds.has(g.learnLessonId), `${g.id} -> ${g.learnLessonId}`).toBe(true);
    }
    for (const screen of CONSOLE_SCREEN_LESSONS) {
      for (const l of screen.lessons) {
        expect(seededIds.has(l.lessonId), `${screen.route} -> ${l.lessonId}`).toBe(true);
      }
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
