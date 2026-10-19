// The catalogue shape (TOUR_STEPS) and the illustration set.
// Split from tour.test.tsx; shared mocks and fixtures live in ./tourTestSupport.

import { AUDIENCE_ILLUSTRATIONS, EXPECTED, ILLUSTRATION_NAMES, readTsxSources } from "./tourTestSupport";
import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { readdirSync, readFileSync } from "node:fs";
import { TourIllustration, type TourIllustrationName } from "./TourIllustrations";
import { TOUR_STEPS } from "../TourProvider";
import { TARGET_FALLBACK_SELECTORS } from "./targets";

describe("TOUR_STEPS", () => {
  it("is the contracted catalogue, in order, each fully specified", () => {
    expect(TOUR_STEPS.map((s) => s.id)).toEqual(EXPECTED.map((e) => e.id));

    TOUR_STEPS.forEach((step, i) => {
      const want = EXPECTED[i];
      expect(step.titleKey, `step ${step.id} titleKey`).toBeTruthy();
      expect(typeof step.titleKey).toBe("string");
      expect(step.target, `step ${step.id} target`).toBe(want.target);
      expect(step.placement, `step ${step.id} placement`).toBe(want.placement);
      // A drawing the illustration component does not know about silently
      // falls back to "welcome", so every step would look like the hero.
      expect(ILLUSTRATION_NAMES, `step ${step.id} illustration`).toContain(step.illustration);
      // Two bullets, always - the card renders exactly two and an empty one
      // would render a bullet glyph with no text beside it.
      expect(step.points).toHaveLength(2);
      step.points.forEach((p) => expect(p.trim().length).toBeGreaterThan(0));
    });
  });

  it("tags every step with a non-empty, valid audience set", () => {
    // A step with no tag reaches EVERY audience (selectSteps degrades fuller),
    // which is how an admin-only screen would find its way back into a
    // member's tour without anyone editing a member's tour.
    TOUR_STEPS.forEach((step, i) => {
      expect(Array.isArray(step.audiences), `step ${step.id} has no audiences array`).toBe(true);
      expect(step.audiences.length, `step ${step.id} is tagged for nobody`).toBeGreaterThan(0);
      for (const a of step.audiences) {
        expect(["member", "tenant-admin", "platform-admin"], `step ${step.id}`).toContain(a);
      }
      expect([...step.audiences].sort(), `step ${step.id} audiences`).toEqual([...EXPECTED[i].audiences].sort());
      expect(step.simple === true, `step ${step.id} simple flag`).toBe(EXPECTED[i].simple);
    });
  });

  it("illustrates the audience-specific steps with their own drawings", () => {
    // A placeholder here is not a crash and not a missing key: it is a step
    // illustrated with a picture of a different feature. The monitor drawing
    // depicts the Playground, files and the audit log - none of which is what
    // "the platform console is a separate app" is about - and the agents
    // drawing shows an agent you authored, which is the exact opposite of the
    // shared-with-me step's point.
    for (const [id, illustration] of Object.entries(AUDIENCE_ILLUSTRATIONS)) {
      const step = TOUR_STEPS.find((s) => s.id === id);
      expect(step, `no step "${id}" in the catalogue`).toBeTruthy();
      expect(step!.illustration, `step ${id} still carries a placeholder drawing`).toBe(illustration);
    }
  });

  it("only carries an href that is a real absolute in-app route", () => {
    // A "Show me" that 404s is worse than no button; the value must at least
    // be a rooted path, never an external URL or a bare segment.
    TOUR_STEPS.filter((s) => s.href).forEach((s) => {
      expect(s.href, `step ${s.id} href`).toMatch(/^\/[a-z0-9/-]*$/);
    });
  });

  it("never sends a member to a route their audience cannot open", () => {
    // The Channels dead end, closed by construction rather than by a route fix:
    // /admin/channels redirects a non-admin to "/", so any step with an /admin
    // href must be tenant-admin-only.
    for (const step of TOUR_STEPS) {
      if (!step.href?.startsWith("/admin")) continue;
      expect(step.audiences, `step ${step.id} sends a non-admin to ${step.href}`).toEqual(["tenant-admin"]);
    }
  });

  it("every step target is actually anchored on an element somewhere in app/", () => {
    // The spotlight finds its element with
    // document.querySelector('[data-tour="<target>"]'), so a nav refactor that
    // renames or drops one of these attributes does not break the build, does
    // not throw, and does not fail any other test - the step simply stops
    // highlighting anything. Worse since round 3: rule 4 then DELETES the step
    // from every tour, silently, and the visitor never learns the screen
    // exists. This is the only thing that notices.
    // The tour's own files are excluded: TourProvider.tsx declares every
    // target as a string literal AND mentions data-tour in its comments, so
    // scanning it would make this test pass itself.
    const sources = readTsxSources("app").filter(
      (src) => !src.path.includes("/tour/") && !src.path.endsWith("TourProvider.tsx")
    );
    for (const step of TOUR_STEPS) {
      if (!step.target) continue;
      const anchored = sources.some((src) => src.text.includes("data-tour") && src.text.includes(`"${step.target}"`));
      if (anchored) continue;
      // Round 4: a target with no marker yet may resolve through an element
      // that already exists (./targets.ts). That is only a real anchor if the
      // thing the fallback selects is really rendered, so the route it keys on
      // must still be a nav row in AppShell - otherwise this is the same silent
      // deletion by another door.
      const fallback = TARGET_FALLBACK_SELECTORS[step.target];
      expect(fallback, `no element carries data-tour="${step.target}" and it has no fallback selector`).toBeTruthy();
      const href = /a\[href="([^"]+)"\]/.exec(fallback)?.[1];
      expect(href, `fallback for "${step.target}" is not an a[href] selector`).toBeTruthy();
      const shell = sources.find((src) => src.path.endsWith("components/AppShell.tsx"));
      const navText = readFileSync("app/components/nav/navSections.ts", "utf8"); // rows live here, not in AppShell
      expect(
        navText.includes(`href: "${href}"`),
        `the nav no longer has a row for ${href}, so "${step.target}" anchors on nothing`
      ).toBe(true);
      expect(shell!.text).toContain('data-tour="nav-rail"');
    }
  });
});

describe("TourIllustration", () => {
  it("draws every name the steps use, and falls back rather than throwing", () => {
    for (const name of ILLUSTRATION_NAMES) {
      const { container, unmount } = render(<TourIllustration name={name as TourIllustrationName} />);
      const svg = container.querySelector("svg");
      expect(svg, `illustration "${name}" rendered no svg`).not.toBeNull();
      // Decorative by contract: the step title/body already carry the meaning,
      // so a label here would double-announce.
      expect(svg).toHaveAttribute("aria-hidden", "true");
      // A drawing with no geometry is an empty box, not an illustration.
      expect(
        svg!.querySelectorAll("path, rect, circle, line, polyline, polygon, image").length,
        `illustration "${name}" has no shapes`
      ).toBeGreaterThan(0);
      unmount();
    }

    // An unknown name must degrade, not crash the whole overlay.
    expect(() => render(<TourIllustration name={"not-a-real-name" as TourIllustrationName} />)).not.toThrow();
  });

  it("hardcodes no colour, so it survives dark mode and the tenant accent", () => {
    // Every colour has to come from a CSS custom property or currentColor;
    // a literal hex renders as a light-mode drawing on a dark card.
    // The drawings live in illustrations/*.tsx since the split; read them all.
    const files = [
      "TourIllustrations.tsx",
      ...readdirSync("app/components/tour/illustrations").map((f) => `illustrations/${f}`),
    ];
    const source = files.map((f) => readFileSync(`app/components/tour/${f}`, "utf8")).join("\n");
    expect(source).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
  });
});
