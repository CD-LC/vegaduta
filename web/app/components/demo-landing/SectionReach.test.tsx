import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, cleanup } from "@testing-library/react";
import SectionReach from "./SectionReach";

// SectionReach decides what "a visitor saw this band" means, and every number
// on the admin funnel screen is downstream of that decision. The rules worth
// pinning are the ones that would silently produce a WRONG-BUT-PLAUSIBLE
// funnel rather than an obvious break:
//
//   - fires only after the dwell, so a fast scroll to the footer doesn't
//     report that the visitor read all 18 bands (a flat, flattering curve);
//   - fires for bands TALLER than the viewport, which can never be 50%
//     visible and would otherwise report 0% reach for the biggest sections;
//   - fires at most once per session, so a client-side remount can't push a
//     reach rate above 100%.
//
// jsdom has no IntersectionObserver and no layout, so the observer is stubbed
// and entries are delivered by hand. That is the point: this test is about the
// decision logic, not about whether Chrome can compute a rectangle.

const ORIGINAL_FETCH = globalThis.fetch;

let callbacks: Array<(entries: any[]) => void> = [];
let disconnectCount = 0;

class StubIntersectionObserver {
  constructor(cb: (entries: any[]) => void) {
    callbacks.push(cb);
  }
  observe() {}
  unobserve() {}
  disconnect() {
    disconnectCount++;
  }
  takeRecords(): any[] {
    return [];
  }
}

/** Deliver an entry to every live observer. */
function emit(entry: { isIntersecting: boolean; ratio: number; height: number }) {
  const payload = [
    {
      isIntersecting: entry.isIntersecting,
      intersectionRatio: entry.ratio,
      intersectionRect: { height: entry.height },
    },
  ];
  for (const cb of callbacks) cb(payload);
}

function beaconedEvents(): string[] {
  return (globalThis.fetch as any).mock.calls.map((c: any[]) => JSON.parse(c[1].body).event);
}

beforeEach(() => {
  callbacks = [];
  disconnectCount = 0;
  vi.useFakeTimers();
  vi.stubGlobal("IntersectionObserver", StubIntersectionObserver as any);
  vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: true } as any)));
  window.sessionStorage.clear();
  // 800px window: a band covering >=400px of it counts as seen even at a low
  // intersection ratio. This is the tall-band rule.
  Object.defineProperty(window, "innerHeight", { value: 800, configurable: true, writable: true });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  globalThis.fetch = ORIGINAL_FETCH;
});

describe("SectionReach", () => {
  it("does not fire before the dwell has elapsed", () => {
    render(
      <SectionReach section="pricing">
        <div>band</div>
      </SectionReach>
    );
    emit({ isIntersecting: true, ratio: 0.9, height: 700 });
    vi.advanceTimersByTime(900);
    expect(beaconedEvents()).toEqual([]);
  });

  it("fires sec_<section> once the band has held the bar for the full dwell", () => {
    render(
      <SectionReach section="pricing">
        <div>band</div>
      </SectionReach>
    );
    emit({ isIntersecting: true, ratio: 0.9, height: 700 });
    vi.advanceTimersByTime(1000);
    expect(beaconedEvents()).toEqual(["sec_pricing"]);
  });

  it("does NOT fire when the visitor scrolls past before the dwell completes", () => {
    // The fast-scroll-to-footer case. If this ever regresses, every band
    // reports near-100% reach and the drop-off curve goes flat.
    render(
      <SectionReach section="faq">
        <div>band</div>
      </SectionReach>
    );
    emit({ isIntersecting: true, ratio: 0.9, height: 700 });
    vi.advanceTimersByTime(400);
    emit({ isIntersecting: false, ratio: 0, height: 0 });
    vi.advanceTimersByTime(5000);
    expect(beaconedEvents()).toEqual([]);
  });

  it("fires for a band taller than the viewport, which can never reach 50% ratio", () => {
    // A band 3x the window height tops out around ratio 0.33. Requiring
    // ratio >= 0.5 alone would report 0% reach for the largest sections on
    // the page - the most alarming possible artefact.
    render(
      <SectionReach section="channels">
        <div>tall band</div>
      </SectionReach>
    );
    emit({ isIntersecting: true, ratio: 0.3, height: 600 }); // 600 >= 800 * 0.5
    vi.advanceTimersByTime(1000);
    expect(beaconedEvents()).toEqual(["sec_channels"]);
  });

  it("ignores a band that is barely on screen", () => {
    render(
      <SectionReach section="closing">
        <div>band</div>
      </SectionReach>
    );
    emit({ isIntersecting: true, ratio: 0.1, height: 80 }); // below both bars
    vi.advanceTimersByTime(5000);
    expect(beaconedEvents()).toEqual([]);
  });

  it("counts a section at most once per browser session", () => {
    const { unmount } = render(
      <SectionReach section="hero">
        <div>band</div>
      </SectionReach>
    );
    emit({ isIntersecting: true, ratio: 0.9, height: 700 });
    vi.advanceTimersByTime(1000);
    unmount();

    // Client-side nav back to the landing remounts the band.
    render(
      <SectionReach section="hero">
        <div>band</div>
      </SectionReach>
    );
    emit({ isIntersecting: true, ratio: 0.9, height: 700 });
    vi.advanceTimersByTime(1000);

    expect(beaconedEvents()).toEqual(["sec_hero"]);
  });

  it("stops observing once the reach is recorded", () => {
    render(
      <SectionReach section="proof">
        <div>band</div>
      </SectionReach>
    );
    emit({ isIntersecting: true, ratio: 0.9, height: 700 });
    vi.advanceTimersByTime(1000);
    expect(disconnectCount).toBeGreaterThan(0);
  });

  it("renders its children and survives a browser with no IntersectionObserver", () => {
    vi.stubGlobal("IntersectionObserver", undefined as any);
    const { getByText } = render(
      <SectionReach section="pricing">
        <div>band content</div>
      </SectionReach>
    );
    expect(getByText("band content")).toBeInTheDocument();
    expect(beaconedEvents()).toEqual([]);
  });
});
