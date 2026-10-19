// @vitest-environment jsdom
//
// Where a step's spotlight lands (2026-09-19). The rule under test: the
// data-tour marker always wins, a listed fallback is used only when there is
// no marker, and nothing in here can throw - it runs inside effects of a
// provider mounted at the root layout.

import { afterEach, describe, expect, it } from "vitest";
import { TARGET_FALLBACK_SELECTORS, queryTourTarget, tourTargetSelector } from "./targets";

afterEach(() => {
  document.body.innerHTML = "";
});

describe("tourTargetSelector", () => {
  it("is the bare marker for a target with no fallback", () => {
    expect(tourTargetSelector("nav-agents")).toBe('[data-tour="nav-agents"]');
  });

  it("lists the marker FIRST for a target that has one", () => {
    const selector = tourTargetSelector("nav-edge");
    expect(selector?.startsWith('[data-tour="nav-edge"]')).toBe(true);
    expect(selector).toContain(TARGET_FALLBACK_SELECTORS["nav-edge"]);
  });

  it("refuses anything that is not a plain identifier", () => {
    const loose = tourTargetSelector as (target: unknown) => string | null;
    for (const bad of [null, undefined, "", "  ", 'x"] , body', "a b", "a]", 7, {}]) {
      expect(loose(bad)).toBeNull();
    }
  });
});

describe("queryTourTarget", () => {
  it("finds a marked element", () => {
    document.body.innerHTML = '<a data-tour="nav-agents" href="/agents">Agents</a>';
    expect(queryTourTarget("nav-agents")?.getAttribute("href")).toBe("/agents");
  });

  it("falls back to the rail's own link when there is no marker", () => {
    document.body.innerHTML = '<nav data-tour="nav-rail"><a href="/edge">Edge AI</a></nav>';
    expect(queryTourTarget("nav-edge")?.textContent).toBe("Edge AI");
  });

  it("scopes the fallback to the rail - a body link to the same route never wins", () => {
    document.body.innerHTML = '<main><a href="/edge">try on-device</a></main>';
    expect(queryTourTarget("nav-edge")).toBeNull();
  });

  it("prefers the marker even when the fallback comes first in the document", () => {
    document.body.innerHTML =
      '<nav data-tour="nav-rail"><a href="/edge" id="fallback">Edge AI</a></nav>' +
      '<button data-tour="nav-edge" id="marked">Edge</button>';
    expect(queryTourTarget("nav-edge")?.id).toBe("marked");
  });

  it("returns null, never throws, for a missing element, a bad target or a hostile root", () => {
    expect(queryTourTarget("nav-agents")).toBeNull();
    expect(queryTourTarget(null)).toBeNull();
    expect(queryTourTarget('x"], body, [y="')).toBeNull();
    const hostile = {
      querySelector: () => {
        throw new DOMException("bad selector", "SyntaxError");
      },
    };
    expect(queryTourTarget("nav-edge", hostile)).toBeNull();
  });
});
