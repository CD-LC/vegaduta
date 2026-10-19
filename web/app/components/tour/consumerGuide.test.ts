// The pure half of the 2026-09-19 first-login fix. These rules decide what a
// brand-new consumer is TOLD, so each one is pinned on the side that matters:
// the claim that must never be made falsely, the dead end that must never be
// offered, and the raw key path that must never reach the screen.

import { existsSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  HOSTED_MODEL_NOTE,
  OPTIONAL_TOUR_COPY,
  copyOrFallback,
  hostedModelClaimAllowed,
  nextActionsFor,
  optionalTourCopy,
} from "./consumerGuide";

describe("hostedModelClaimAllowed", () => {
  it("follows the server's answer, whatever the plan is called", () => {
    // The bug: a new signup's complimentary grant makes the plan read as paid.
    expect(hostedModelClaimAllowed({ plan: "UNLIMITED", hostedAvailable: true })).toBe(true);
    expect(hostedModelClaimAllowed({ plan: "PREMIUM", hostedAvailable: true })).toBe(true);
    expect(hostedModelClaimAllowed({ plan: null, hostedAvailable: true })).toBe(true);
  });

  it("holds for a positively FREE plan even when the server never answered", () => {
    expect(hostedModelClaimAllowed({ plan: "FREE", hostedAvailable: null })).toBe(true);
    expect(hostedModelClaimAllowed({ plan: " free ", hostedAvailable: undefined })).toBe(true);
  });

  it("stays silent when it cannot be sure - never a false 'no key needed'", () => {
    expect(hostedModelClaimAllowed({ plan: "UNLIMITED", hostedAvailable: null })).toBe(false);
    expect(hostedModelClaimAllowed({ plan: "PREMIUM", hostedAvailable: false })).toBe(false);
    expect(hostedModelClaimAllowed({ plan: null, hostedAvailable: null })).toBe(false);
    expect(hostedModelClaimAllowed({ plan: "ENTERPRISE", hostedAvailable: undefined })).toBe(false);
  });

  it("reads the flag strictly and survives garbage", () => {
    const loose = hostedModelClaimAllowed as (input: unknown) => boolean;
    expect(loose({ plan: "UNLIMITED", hostedAvailable: "true" })).toBe(false);
    expect(loose({ plan: 7, hostedAvailable: 1 })).toBe(false);
    expect(loose(null)).toBe(false);
    expect(loose(undefined)).toBe(false);
  });
});

describe("nextActionsFor", () => {
  const hrefs = (audience: string | null, edgeAvailable = true) =>
    nextActionsFor(audience, { edgeAvailable }).map((a) => a.href);

  it("opens with the launch path, in the order a first-timer should try it", () => {
    // app -> agent -> workflow -> private test.
    expect(hrefs("tenant-admin").slice(0, 4)).toEqual(["/build", "/agents", "/workflows", "/playground"]);
    expect(hrefs("member").slice(0, 4)).toEqual(["/build", "/agents", "/workflows", "/playground"]);
  });

  it("offers Channels only to the audience that can open it", () => {
    // /admin/channels redirects a non-admin to "/".
    expect(hrefs("tenant-admin")).toContain("/admin/channels");
    expect(hrefs("member")).not.toContain("/admin/channels");
    expect(hrefs(null)).not.toContain("/admin/channels");
    expect(hrefs("someone-new")).not.toContain("/admin/channels");
  });

  it("offers Edge AI only when the build carries it", () => {
    expect(hrefs("member", true)).toContain("/edge");
    expect(hrefs("member", false)).not.toContain("/edge");
    expect(nextActionsFor("member").map((a) => a.href)).not.toContain("/edge");
  });

  it("gives a platform-admin the Guide and no build action", () => {
    expect(hrefs("platform-admin")).toEqual(["/help"]);
  });

  it("always ends on the written Guide, and never repeats an id", () => {
    for (const audience of ["member", "tenant-admin", "platform-admin", null]) {
      const list = nextActionsFor(audience, { edgeAvailable: true });
      expect(list[list.length - 1].href).toBe("/help");
      expect(new Set(list.map((a) => a.id)).size).toBe(list.length);
      for (const action of list) {
        expect(action.label.trim().length).toBeGreaterThan(0);
        expect(action.hint.trim().length).toBeGreaterThan(0);
      }
    }
  });

  it("only links to routes that exist in app/", () => {
    // A "what next" row that 404s is worse than no row.
    const all = nextActionsFor("tenant-admin", { edgeAvailable: true });
    for (const action of all) {
      expect(action.href).toMatch(/^\/[a-z0-9/-]*$/);
      expect(existsSync(`app${action.href}/page.tsx`), `${action.href} has no page.tsx`).toBe(true);
    }
  });

  it("is honest that the Playground needs an agent first", () => {
    const playground = nextActionsFor("member").find((a) => a.id === "playground");
    expect(playground?.hint).toMatch(/one agent first/i);
  });
});

describe("copyOrFallback / optionalTourCopy", () => {
  it("treats the dotted path, the bare key and emptiness as missing", () => {
    expect(copyOrFallback("tour.hostedNote", "hostedNote", "EN")).toBe("EN");
    expect(copyOrFallback("hostedNote", "hostedNote", "EN")).toBe("EN");
    expect(copyOrFallback("   ", "hostedNote", "EN")).toBe("EN");
    expect(copyOrFallback(null, "hostedNote", "EN")).toBe("EN");
    expect(copyOrFallback(undefined, "hostedNote", "EN")).toBe("EN");
    expect(copyOrFallback("other.hostedNote", "hostedNote", "EN", "other")).toBe("EN");
  });

  it("lets a real translation win", () => {
    expect(copyOrFallback("Übersetzt", "hostedNote", "EN")).toBe("Übersetzt");
  });

  it("never renders a key path, and never throws on a broken translator", () => {
    const identity = (key: string) => key;
    const dotted = (key: string) => `tour.${key}`;
    const broken = () => {
      throw new Error("no provider");
    };
    for (const key of Object.keys(OPTIONAL_TOUR_COPY) as Array<keyof typeof OPTIONAL_TOUR_COPY>) {
      for (const t of [identity, dotted, broken]) {
        const out = optionalTourCopy(t, key);
        expect(out).toBe(OPTIONAL_TOUR_COPY[key]);
        expect(out).not.toContain("tour.");
      }
      expect(optionalTourCopy(() => "traduit", key)).toBe("traduit");
    }
    expect(OPTIONAL_TOUR_COPY.hostedNote).toBe(HOSTED_MODEL_NOTE);
  });
});
