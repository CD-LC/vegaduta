// The product tour's contract, not its artwork.
//
// The tour is copy driven entirely by string keys, rendered through a t() that
// returns the DOTTED PATH when a key is missing. That is a silent failure: a
// typo'd or forgotten key ships as literal "tour.agentsBody" on the first
// screen a new tenant ever sees, in all 30 locales at once, and nothing in the
// build catches it. So the highest-value test here is the boring one - every
// key every step needs actually exists in en.json - and it is deliberately
// written to fail on a MISSING key rather than on reworded English, which
// product may change at any time.
//
// The rest lock in behaviour that has regressed before: a step whose target
// element disappears must still render an advanceable card (v1 rendered
// nothing and dead-ended the tour), the three status branches must each render
// their own surface, keyboard control must work, and goToStep must clamp
// instead of walking off the end of the list.
//
// Second round (2026-09, completeness review). The rebuild that took the tour
// from 3 steps to 9 shipped several regressions this suite did not see, so it
// also guards, by defect:
//   D1  the price/prerequisite facts ("free", "hosted model", "no API key")
//       that v1 stated in its opening line and the rebuild silently dropped.
//       Matched as CONCEPTS, never as a sentence - product may reword, product
//       may not delete. Round 3 split step 0's copy per PLAN, so the guard now
//       runs over the variant a visitor with an unknown or free plan actually
//       lands on, and additionally proves the variant chain can never end on a
//       key nobody wrote.
//   D3  the in-code `title`/`body`/`points` mirror on each step, which drifted
//       out of sync with the en.json keys that actually render. That mirror is
//       documentation, and documentation nothing checks is just a rumour.
//   D8  "Show me" used to navigate AND advance in the same tick, so the one
//       control that promises "look at this" guaranteed you looked at the next
//       step's card instead.
//   D9  the dialog's accessible name and the progress rail's label, which used
//       to duplicate the aria-live step counter.
//   D13 succession: the tour's completion CTA routes to /agents and only THEN
//       finishes, so AgentsGuide's own first-run modal opened on the very next
//       frame. Its guard is now tourEndedThisSession().
//
// Third round (2026-09-12, AUDIENCE). The nine steps were identical for every
// visitor: a member who cannot open /admin/channels was walked through
// Channels and bounced off it by the step's own "Show me", and a
// platform-admin - who may have no tenant at all - was walked through building
// an agent in a workspace that is not theirs. TOUR_STEPS became a tagged
// CATALOGUE, and what a visitor walks is selectSteps(catalogue, access, isInDom)
// from ./audience.ts.
//
// That module is pure, synchronous and DOM-free except for one injected
// callback, which makes it the most testable thing in the feature and the
// place a mistake is cheapest to catch. So it is tested rule by rule, against a
// synthetic table (so a copy edit to the real catalogue cannot make a RULE test
// pass or fail for the wrong reason) and then again against the real catalogue
// (so a step added without an `audiences` tag cannot quietly reach everyone).
//
// Two things this round is deliberately strict about:
//   A1  a step's copy must resolve for EVERY audience it is tagged for, through
//       the same variantKeys() chain the overlay uses. A stem with no keys at
//       all cannot be rescued by a fallback, and renders "tour.<stem>Title" on
//       screen - which is the exact failure mode the suite's oldest test exists
//       to prevent, reintroduced through a new door.
//   A2  the counter, the rail and the walk must all be offsets into the
//       SELECTED list. A member seeing "Step 2 of 9" while their tour is six
//       steps long is the audience split leaking a number from the catalogue.

//
// Fourth round (2026-09-19, FIRST LOGIN AS A CONSUMER). Founder feedback: the
// tour "is not explaining to consumers completely on landing, first login". A
// new signup is a tenant-admin in SIMPLE mode inside a complimentary grant, and
// the audit found four holes for exactly that person, each guarded below:
//   C1  step 0 keyed "hosted model, no API key" off the PLAN NAME, so a grant
//       holder (plan reads as paid) never saw it. The claim now follows the
//       server's own answer and is silent when the answer is unknown.
//   C2  nothing said where on-device / Edge AI lives. The "edge" step is the
//       first INLINE step (English in code until en.json keys exist) and the
//       first with a real `requires` gate (the build-time edge flag).
//   C3  an explicit "Take the tour" / replay that selected nothing was a silent
//       no-op. It now falls back to a walk of centred cards.
//   C4  a visitor who skipped was never told the tour can be reopened, and the
//       "?" button offered nothing but the walk they had just abandoned.
//
// Layout: ./tourTestMocks holds the vi.mock wiring and per-test reset,
// ./tourContractData the transcribed contract tables; this module holds the
// helpers and re-exports all three so each suite imports one place.
import { mock, push, setTour, TestResizeObserver, type TourContextShape } from "./tourTestMocks";
import {
  AUDIENCE_ILLUSTRATIONS,
  CHROME_KEYS,
  DEFAULT_SETTINGS,
  EXPECTED,
  ILLUSTRATION_NAMES,
  NEW_SIGNUP,
  REMOVED_KEYS,
  SHELL_TARGETS,
  SUFFIXES,
} from "./tourContractData";
import { expect, vi } from "vitest";
import { act, render } from "@testing-library/react";
import { readdirSync, readFileSync } from "node:fs";

import TourOverlay, { audienceVariantToken, planVariantToken } from "./TourOverlay";
import { resolveAccess, selectSteps, type TourAccess, type TourAudience, type TourStepLike } from "./audience";
import { TOUR_STEPS, TourProvider, useTour, type TourStep } from "../TourProvider";
import { AuthContext } from "../AuthProvider";
// Imported for the succession tests only (D13). It reads the same
// TourProvider module the mock wraps, so isTourPending() and
// tourEndedThisSession() are the REAL implementations reading real storage.

/** Every .tsx under `dir`, read once. Paths are relative to vitest's cwd
 * (web/), matching SecretsPanel.test.tsx. */
function readTsxSources(dir: string): Array<{ path: string; text: string }> {
  const out: Array<{ path: string; text: string }> = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = `${dir}/${entry.name}`;
    if (entry.isDirectory()) {
      if (entry.name === "node_modules" || entry.name.startsWith(".")) continue;
      out.push(...readTsxSources(full));
    } else if (entry.name.endsWith(".tsx") && !entry.name.endsWith(".test.tsx")) {
      out.push({ path: full, text: readFileSync(full, "utf8") });
    }
  }
  return out;
}

/* ------------------------------------------------------------------ */
/* en.json, read from disk once                                        */
/* ------------------------------------------------------------------ */

/** Read rather than imported: this also asserts en.json is still parseable
 * after whatever concurrent edit last touched it (the 30-locale pipeline and
 * several agents write this tree). Path is relative to vitest's cwd (web/),
 * matching SecretsPanel.test.tsx. */
const ALL_MESSAGES = JSON.parse(readFileSync("messages/en.json", "utf8")) as Record<string, Record<string, unknown>>;
const TOUR_MESSAGES = ALL_MESSAGES.tour;
mock.messages = ALL_MESSAGES;

/** The key `variantKeys` would land on, or null if the whole chain is missing.
 * Missing is what the overlay cannot rescue: every entry in the chain is a
 * KEY, so if none of them exists the visitor sees a dotted path on screen. */
function firstExistingKey(keys: string[]): string | null {
  for (const key of keys) {
    const value = TOUR_MESSAGES?.[key];
    if (typeof value === "string" && value.trim().length > 0) return key;
  }
  return null;
}

/** The variants the overlay passes for a step's copy: audience first (the
 * narrower bucket), then plan, with "Free" as the tail default for a plan that
 * never resolved. Mirrors TourOverlay's `stepVariants`. */
function stepVariantsFor(audience: TourAudience, plan: string | null): Array<string | null> {
  return [audienceVariantToken(audience), planVariantToken(plan) ?? "Free"];
}

/* ------------------------------------------------------------------ */
/* selectSteps fixtures                                                */
/* ------------------------------------------------------------------ */

// A SYNTHETIC table, deliberately. Testing the rules against the real
// catalogue would mean a copy edit could make a rule test pass (or fail) for a
// reason that has nothing to do with the rule - and the real catalogue happens
// not to use `requires` at all today, so rule 2 would be untested entirely.
// The real catalogue gets its own suite below.

type FakeStep = TourStepLike & { id: string };

function fake(id: string, audiences: TourAudience[], extra: Partial<FakeStep> = {}): FakeStep {
  return { id, target: null, audiences, ...extra };
}

const ALL: TourAudience[] = ["member", "tenant-admin", "platform-admin"];

function accessFor(
  audience: TourAudience,
  tenantSettings: Record<string, unknown> | null = { experienceMode: "ADVANCED" }
): TourAccess {
  return resolveAccess({
    isAdmin: audience === "tenant-admin",
    isPlatformAdmin: audience === "platform-admin",
    tenantSettings,
  });
}

const PRESENT = () => true;
const ABSENT = () => false;

function ids(steps: Array<{ id: string }>): string[] {
  return steps.map((s) => s.id);
}

/* ------------------------------------------------------------------ */
/* The real catalogue, per audience                                    */
/* ------------------------------------------------------------------ */

/** What this audience actually walks, with every anchor on screen. */
function walk(
  audience: TourAudience,
  tenantSettings: Record<string, unknown> | null = {
    plan: "UNLIMITED",
    experienceMode: "ADVANCED",
  }
): string[] {
  return ids(selectSteps(TOUR_STEPS, accessFor(audience, tenantSettings), PRESENT));
}

/* ------------------------------------------------------------------ */
/* Overlay render fixtures                                             */
/* ------------------------------------------------------------------ */

function stepById(id: string): TourStep {
  const step = TOUR_STEPS.find((s) => s.id === id);
  if (!step) throw new Error(`no step "${id}" in TOUR_STEPS`);
  return step;
}

/** What the card's heading reads under the identity t(): the key for a keyed
 * step, and the step's own English for an INLINE one (the identity t() echoes
 * the key, which is exactly what "missing" looks like to the overlay). */
function headingOf(id: string): string {
  const step = stepById(id);
  return step.inline ? step.title : `${step.titleKey}Title`;
}

/** A fixed active context pinned to ONE step. Addressed by id rather than by
 * index into TOUR_STEPS: the catalogue's indices are no longer the walk's
 * indices, and a test that says "step 1" now means a different step to every
 * audience. `total` is the SELECTED list's length, which is the number the
 * overlay is supposed to count against. */
function activeContext(id: string, opts: { index?: number; total?: number } = {}): Partial<TourContextShape> {
  const total = opts.total ?? 9;
  const index = opts.index ?? 1;
  return {
    status: "active",
    stepIndex: index,
    currentStep: stepById(id),
    totalSteps: total,
    nextStep: vi.fn(),
    prevStep: vi.fn(),
    skipTour: vi.fn(),
    finishTour: vi.fn(),
    goToStep: vi.fn(),
  };
}

/* ------------------------------------------------------------------ */
/* Integration against the real provider                               */
/* ------------------------------------------------------------------ */

function Harness() {
  const tour = useTour();
  return (
    <div>
      <span data-testid="status">{tour.status}</span>
      <span data-testid="index">{tour.stepIndex}</span>
      <span data-testid="total">{tour.totalSteps}</span>
      <span data-testid="audience">{tour.audience}</span>
      <span data-testid="ids">{tour.steps.map((s) => s.id).join(",")}</span>
      <span data-testid="catchup">{String(tour.catchUp)}</span>
      <button type="button" data-testid="start" onClick={tour.startTour}>
        start
      </button>
      <button type="button" data-testid="replay" onClick={() => tour.replayTour()}>
        replay
      </button>
      <button type="button" data-testid="dismiss" onClick={tour.dismissWelcome}>
        dismiss
      </button>
      <button type="button" data-testid="jump-3" onClick={() => tour.goToStep(3)}>
        jump3
      </button>
      <button type="button" data-testid="jump-high" onClick={() => tour.goToStep(99)}>
        jumpHigh
      </button>
      <button type="button" data-testid="jump-low" onClick={() => tour.goToStep(-5)}>
        jumpLow
      </button>
      <button type="button" data-testid="jump-nan" onClick={() => tour.goToStep(Number.NaN)}>
        jumpNaN
      </button>
    </div>
  );
}

function FakeShell({ omit = [] as string[] }) {
  return (
    <div>
      {SHELL_TARGETS.filter((t) => !omit.includes(t)).map((t) => (
        <span key={t} data-tour={t} data-testid={`anchor-${t}`} />
      ))}
    </div>
  );
}

function renderTour(
  opts: {
    isAdmin?: boolean;
    isPlatformAdmin?: boolean;
    tenantSettings?: Record<string, unknown> | null;
    omit?: string[];
  } = {}
) {
  const { isAdmin = true, isPlatformAdmin = false, omit = [] } = opts;
  mock.tenantSettings = "tenantSettings" in opts ? opts.tenantSettings : { ...DEFAULT_SETTINGS };
  return render(
    // createContext(null) on the auth side, so the cast is the honest way to
    // hand it the two fields TourProvider reads. useContext, not useAuth():
    // the provider is mounted above the public/authenticated split.
    <AuthContext.Provider value={{ isAdmin, isPlatformAdmin } as unknown as null}>
      <TourProvider>
        <FakeShell omit={omit} />
        <Harness />
        <TourOverlay />
      </TourProvider>
    </AuthContext.Provider>
  );
}

/** The ids a given audience walks with the whole shell on screen - computed
 * from the same function the provider calls, so these tests assert the
 * PROVIDER wires it up, not that selectSteps works (which is tested above). */
const ADMIN_WALK = walk("tenant-admin");

/* ------------------------------------------------------------------ */
/* Round 4 (2026-09-19) - first login as a CONSUMER                    */
/* ------------------------------------------------------------------ */

/** Let the provider's availability promise settle inside act(). */
async function settle() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

/** Nothing the tour renders may intercept a click once it is not on screen.
 * Every full-viewport layer that can take pointer events is either a
 * [data-tour-blocker] or NOT pointer-events-none; assert there is neither. */
function expectNothingBlocksTheApp() {
  expect(document.body.querySelector("[data-tour-blocker]"), "a click blocker outlived the tour").toBeNull();
  for (const layer of Array.from(document.body.querySelectorAll('[class*="inset-0"]'))) {
    expect(
      layer.getAttribute("class") ?? "",
      "a full-viewport tour layer can still take clicks after the tour ended"
    ).toContain("pointer-events-none");
  }
}

export {
  ABSENT,
  ADMIN_WALK,
  ALL,
  ALL_MESSAGES,
  AUDIENCE_ILLUSTRATIONS,
  CHROME_KEYS,
  DEFAULT_SETTINGS,
  EXPECTED,
  FakeShell,
  Harness,
  ILLUSTRATION_NAMES,
  NEW_SIGNUP,
  PRESENT,
  REMOVED_KEYS,
  SHELL_TARGETS,
  SUFFIXES,
  TOUR_MESSAGES,
  TestResizeObserver,
  accessFor,
  activeContext,
  expectNothingBlocksTheApp,
  fake,
  firstExistingKey,
  headingOf,
  ids,
  mock,
  push,
  readTsxSources,
  renderTour,
  setTour,
  settle,
  stepById,
  stepVariantsFor,
  walk,
};
export type { FakeStep, TourContextShape };
