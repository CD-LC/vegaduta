"use client";

import { useCallback, useContext, useEffect, useMemo, useRef, useState, Suspense } from "react";

import { AuthContext } from "./AuthProvider";
import { useAppChrome } from "./AppChromeProvider";
import { queryTourTarget } from "./tour/targets";
import { courtRegion, regionSteps } from "./tour/court";
import type { CourtRegionId } from "./tour/court";
import CourtMap from "./tour/CourtMap";
import FieldSpotlight from "./tour/FieldSpotlight";
import { resolveAccess, selectSteps } from "./tour/audience";
import type { TourAccess } from "./tour/audience";
import type { TourStep } from "./tour/types";
import { TOUR_STEPS } from "./tour/steps";
import {
  SECURITY_SEEN_KEY,
  SECURITY_STEP_ID,
  SESSION_END_KEY,
  STEP_KEY,
  STORAGE_KEY,
  clampIndex,
  clearStored,
  userScoped,
  writeSession,
  writeStored,
} from "./tour/storage";
import { TourContext, type TourContextValue, type TourStatus } from "./tour/context";
import { useHostedModelAvailable } from "./tour/useHostedModelAvailable";
import { useReopenHint } from "./tour/useReopenHint";
import { useTourArming } from "./tour/useTourArming";

// Re-exports: every existing import path of TourProvider keeps working.
export { TOUR_STEPS };
export type { TourStep };
export { isTourPending, tourEndedThisSession, userScoped } from "./tour/storage";
export { useTour } from "./tour/context";

// Product tour engine (state machine, arming, persistence, context). The step
// catalogue and its history live in ./tour/steps, the step type in
// ./tour/types, storage helpers in ./tour/storage, the context contract and
// useTour() in ./tour/context; arming, the hosted-model check and the reopen
// hint are hooks beside them.


export function TourProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<TourStatus>("idle");
  const [stepIndex, setStepIndex] = useState(0);
  const [courtOpen, setCourtOpen] = useState(false);
  // True while a Court region walk is the active run; see walkCourtRegion.
  const courtRunRef = useRef(false);
  // True while a catch-up walk (see SECURITY_STEP_ID) is the active run. Ref for
  // the callbacks, state for the context; set and cleared together.
  const catchUpRunRef = useRef(false);
  const [catchUp, setCatchUp] = useState(false);
  // Latest status, for the arming timeout (created once, on mount): the
  // catch-up must not land on a welcome, a walk or a completion screen that
  // some other path opened in the meantime.
  const statusRef = useRef<TourStatus>("idle");
  useEffect(() => {
    statusRef.current = status;
  }, [status]);
  const courtOpenRef = useRef(false);
  useEffect(() => {
    courtOpenRef.current = courtOpen;
  }, [courtOpen]);
  const endCatchUp = useCallback(() => {
    if (!catchUpRunRef.current) return;
    catchUpRunRef.current = false;
    setCatchUp(false);
  }, []);

  // Roles, read via useContext(AuthContext) and NOT via useAuth().
  //
  // This is the same exemption AppChromeProvider takes, for the same reason
  // and it matters more here: useAuth() hard-throws when there is no
  // AuthContext.Provider above it, and on a PUBLIC route AuthProvider renders
  // `children` with no provider at all (by design — see its own comment on
  // the exported context). TourProvider is mounted at the root layout, above
  // the public/authenticated split, so calling useAuth() here would crash
  // every marketing page. useContext resolves to `null` there, which is
  // exactly "no session" — and a visitor with no session never reaches the
  // nav rail, so the tour never arms for them anyway.
  const auth = useContext(AuthContext) as { isAdmin?: boolean; isPlatformAdmin?: boolean } | null;
  const isAdmin = !!auth?.isAdmin;
  const isPlatformAdmin = !!auth?.isPlatformAdmin;

  // Entitlements, from AppChromeProvider's ONE shared GET /api/tenant/settings
  // for the session. Deliberately not a fetch of our own: the tour is the
  // lowest-value consumer of that response in the app and must not add a
  // round-trip to first login. useAppChrome() returns the module DEFAULT
  // (tenantSettings null) outside the provider and never throws, so this is
  // safe on public routes too. Null is a legitimate steady state, not just a
  // loading one — a platform-admin may have no tenant at all
  // (AuthProvider.tsx:622).
  const { tenantSettings } = useAppChrome();

  const access = useMemo(
    () => resolveAccess({ isAdmin, isPlatformAdmin, tenantSettings }),
    [isAdmin, isPlatformAdmin, tenantSettings]
  );

  // WHERE THE DOM REACHABILITY CHECK RUNS, AND WHY IT RUNS THERE.
  //
  // Not at module scope, not in a render, not in a useMemo: the nav rail
  // mounts asynchronously (AppShell is wrapped per-page and the whole
  // authenticated shell paints after Keycloak settles), so a query that runs
  // early sees an empty body, finds none of the nav-* markers, and rule 4
  // drops every anchored step — the entire tour, leaving only the floor.
  // The check therefore runs exactly once per tour RUN, inside resolveSteps()
  // below, and resolveSteps() is only ever called from a point where the
  // shell is known to be on screen:
  //
  //   - the arming timeout, which fires ARM_DELAY_MS after the
  //     MutationObserver below has seen [data-tour="nav-rail"] appear (this is
  //     the signal the provider already waited on for the welcome; the
  //     selection rides the same trigger rather than inventing a second one),
  //     and then waits further, up to SELECTION_WAIT_MS, for tenantSettings —
  //     see canCommitSelection();
  //   - startTour(), reached only by pressing the welcome modal's primary,
  //     which cannot be on screen before that observer fired;
  //   - replayTour(), from the Help page, which IS the shell;
  //   - goToStep(), only if nothing has resolved yet (it cannot be: the
  //     overlay that calls it only renders once a step is showing).
  //
  // One caveat this cannot fix and does not pretend to: a step whose target
  // lives on another ROUTE is judged by whether its marker is in the DOM at
  // the moment the tour starts. Every anchored step here targets the nav
  // rail or the Help link, both of which are present on every console route,
  // so the check is route-independent in practice. An anchor that only
  // exists deep inside one page (data-tour="create-agent", say) would need a
  // different rule, not a different call site.
  const isTargetPresent = useCallback((target: string | null) => {
    if (!target) return true;
    if (typeof document === "undefined") return false;
    // queryTourTarget, not a bare [data-tour] query (2026-09-19): a target may
    // resolve through an existing element when no marker has been added for it
    // yet - see ./tour/targets.ts. It also never throws.
    return !!queryTourTarget(target);
  }, []);

  // The list before any tour has started: audience- and entitlement-filtered,
  // but with reachability asserted true, because at this point the DOM is not
  // a trustworthy source. Nothing walks this list — it exists so totalSteps
  // is a sane number for anything that reads the context early, and so the
  // first render after resolveSteps() is a narrowing rather than a jump.
  const previewSteps = useMemo(() => selectSteps(TOUR_STEPS, access, () => true), [access]);

  const [selectedSteps, setSelectedSteps] = useState<TourStep[] | null>(null);
  const steps = selectedSteps ?? previewSteps;

  // Two refs, two jobs, both for reading fresh values inside callbacks and
  // inside the arming timeout (which is created once, on mount, and would
  // otherwise close over the first render's `access` — tenantSettings is
  // fetched asynchronously and routinely lands AFTER the 900ms timer).
  //   accessRef  — the latest resolved access, for resolveSteps().
  //   stepsRef   — the effective list, for nextStep/goToStep bounds.
  // `resolvedRef` records whether this run's DOM check has happened yet.

  const accessRef = useRef<TourAccess>(access);
  const stepsRef = useRef<TourStep[]>(previewSteps);
  const resolvedRef = useRef(false);
  useEffect(() => {
    accessRef.current = access;
  }, [access]);
  useEffect(() => {
    stepsRef.current = steps;
  }, [steps]);

  // Latest tenantSettings, for the same reason accessRef exists: the arming
  // timeout is created once and would otherwise close over the first render's
  // value. Read ONLY by canCommitSelection() — every other consumer wants the
  // flattened `access`, not the raw response.
  const settingsRef = useRef<unknown>(tenantSettings);
  useEffect(() => {
    settingsRef.current = tenantSettings;
  }, [tenantSettings]);

  // MAY WE COMMIT A STEP LIST YET? (the entitlement gate audience.ts asks for
  // in as many words: "Do not commit to a step list until tenantSettings has
  // resolved for those two audiences".)
  //
  // Selection happens ONCE per tour run and is never recomputed when access
  // changes later — nothing calls setSelectedSteps outside resolveSteps(). That
  // is fine on every path where a human action starts the tour, because by then
  // the shell has been on screen long enough for AppChromeProvider's single
  // fetch to land. It is NOT fine on the RESUME path, which is the one path
  // with no human in it: a stored index > 0 sends the tour straight to "active"
  // from the arming timeout, and if tenantSettings has not landed by then
  // resolveAccess() reports experienceMode null, rule 3 never runs, and a SIMPLE
  // workspace is walked through the full ADVANCED list — the third of the three
  // failures audience.ts's own header says the module exists to remove.
  //
  // platform-admin is deliberately NOT gated. That audience reads no
  // entitlement (its two steps carry no `requires` and both are `simple`), and
  // a platform-admin may legitimately have NO TENANT AT ALL
  // (AuthProvider.tsx:622) — tenantSettings is then null forever and gating
  // would hang their tour permanently rather than delay it.
  //
  // And the wait is BOUNDED even for the two gated audiences, because null is
  // also what AppChromeProvider stores when its best-effort fetch FAILS
  // (AppChromeProvider.tsx:77 — `.catch(() => setTenantSettings(null))`). There
  // is no settled/loading flag to read, so an unconditional wait would turn one
  // failed request into "this visitor never gets a tour". After
  // SELECTION_WAIT_MS the arming path gives up and selects on what it has,
  // which is the pre-existing behaviour, not a new degradation.
  const canCommitSelection = useCallback(() => {
    if (accessRef.current.audience === "platform-admin") return true;
    return settingsRef.current !== null && settingsRef.current !== undefined;
  }, []);

  // Resolve the list for ONE run. An empty result is not committed: selectSteps
  // returns [] to mean "run no tour at all" (audience.ts's rule 5), and pinning
  // that into state would leave every later reader — totalSteps, the resume
  // clamp — reporting 0 for the rest of the page load, including for a replay
  // that would resolve perfectly well. Callers read the return value; see the
  // empty-list handling at each call site.
  const resolveSteps = useCallback((): TourStep[] => {
    const next = selectSteps(TOUR_STEPS, accessRef.current, isTargetPresent);
    if (next.length === 0) return next;
    stepsRef.current = next;
    resolvedRef.current = true;
    setSelectedSteps(next);
    return next;
  }, [isTargetPresent]);

  // Resolve for an EXPLICIT request (the welcome's primary, a replay). Tries
  // the normal, reachability-checked list first; if that is empty it selects
  // again with every anchor asserted present, so rules 1-3 (audience,
  // entitlement, SIMPLE) still hold and only rule 4 is waived. See startTour
  // for why that is safe and why the arming path deliberately does NOT do it.
  const resolveRequestedSteps = useCallback((): TourStep[] => {
    const reachable = resolveSteps();
    if (reachable.length > 0) return reachable;
    const unanchored = selectSteps(TOUR_STEPS, accessRef.current, () => true);
    if (unanchored.length === 0) return unanchored;
    stepsRef.current = unanchored;
    resolvedRef.current = true;
    setSelectedSteps(unanchored);
    return unanchored;
  }, [resolveSteps]);

  const hostedModelAvailable = useHostedModelAvailable(status, access.audience);

  useTourArming({
    statusRef,
    courtOpenRef,
    courtRunRef,
    catchUpRunRef,
    accessRef,
    stepsRef,
    resolvedRef,
    setCatchUp,
    setSelectedSteps,
    setStepIndex,
    setStatus,
    canCommitSelection,
    resolveSteps,
    isTargetPresent,
  });

  // Persist progress, but ONLY while the tour is actually running. Writing on
  // every stepIndex change regardless of status would re-create the key right
  // after skip/finish cleared it (those set status first, and this effect
  // runs afterwards) — which is exactly the "tour I skipped came back on
  // reload" bug the status guard prevents.
  useEffect(() => {
    // The catch-up is not the first-run tour either: a resume index written
    // for a decided visitor would be progress through a tour they never left.
    if (status !== "active" || courtRunRef.current || catchUpRunRef.current) return;
    writeStored(userScoped(STEP_KEY), String(stepIndex));
  }, [status, stepIndex]);

  // A Court region walk (or the catch-up) swapped the selected list for its own
  // subset. Once it is over, forget the subset so the next real start/replay
  // re-resolves the full list instead of walking three steps as if they were
  // the tour.
  useEffect(() => {
    if (status === "active" || (!courtRunRef.current && !catchUpRunRef.current)) return;
    courtRunRef.current = false;
    endCatchUp();
    resolvedRef.current = false;
    setSelectedSteps(null);
  }, [status, endCatchUp]);

  // A full or Court walk that puts the security card on screen has shown it:
  // record that, so the catch-up never repeats it. The catch-up itself records
  // only on its exits (nextStep / skipTour), never on display.
  const currentStepId = status === "active" ? (steps[stepIndex]?.id ?? null) : null;
  useEffect(() => {
    if (currentStepId !== SECURITY_STEP_ID || catchUpRunRef.current) return;
    writeStored(userScoped(SECURITY_SEEN_KEY), "1");
  }, [currentStepId]);

  // Keeps stepIndex inside the selected list. This used to claim it caught a
  // late tenantSettings turning out to say SIMPLE and shrinking the list under
  // a running tour. It cannot: selection is committed ONCE per run inside
  // resolveSteps(), and nothing re-resolves when `access` changes — that
  // no-recompute is precisely why the arming path now gates on tenantSettings
  // instead (canCommitSelection). `previewSteps` can still move with access,
  // but only while selectedSteps is null, and no path reaches "active" with
  // selectedSteps null.
  //
  // What it is really for, and why it stays:
  //   - the restored resume index, clamped against a list that may be shorter
  //     than the one it was written against (a tenant-admin at step 8 who
  //     returns as a member);
  //   - a re-resolution while "active" — replayTour() and an unresolved
  //     goToStep() — landing a shorter list under the current index.
  // Both of those also set the index themselves, so this is a belt-and-braces
  // guard rather than the mechanism; keep it anyway, because the failure it
  // prevents (currentStep going null mid-walk, i.e. the tour visibly
  // vanishing) is far worse than the tour ending one step early.
  useEffect(() => {
    if (status !== "active") return;
    setStepIndex((i) => clampIndex(i, steps.length));
  }, [status, steps.length]);

  // Empty result: close the hero without recording a decision. The visitor
  // pressed "Take the tour" and there is nothing to walk, so "active" would be
  // a card-less dead state and writing vd_tour_status would record a choice
  // they did not make. Leaving the flag pending re-offers the tour on a later
  // load — which is the correct outcome for someone who asked for one and got
  // nothing. (Same reasoning as the arming path; see the note there.)
  //
  // 2026-09-19 - that reasoning still governs the ARMING path, where no human
  // asked for anything. It no longer governs this one. Someone who pressed
  // "Take the tour" and watched the modal close with nothing behind it has met
  // a silent no-op in their first minute in the product, and "it will be
  // offered again on a later load" is not something they can know. So an
  // explicit request falls back to resolveRequestedSteps(): the audience- and
  // entitlement-filtered list WITHOUT the reachability rule. That is safe now
  // in a way it was not when rule 4 was written - TourOverlay centres the card
  // over a scrim for any step whose anchor it cannot find, so an unreachable
  // anchor costs a spotlight, never a stuck or blank tour. Only if even that
  // list is empty (no step is tagged for this audience at all) does the hero
  // close without recording a decision.
  const startTour = useCallback(() => {
    endCatchUp();
    const list = resolveRequestedSteps();
    if (list.length === 0) {
      setStatus("idle");
      return;
    }
    setStepIndex(0);
    setStatus("active");
  }, [resolveRequestedSteps, endCatchUp]);

  const { reopenHintVisible, dismissReopenHint, showReopenHint } = useReopenHint();

  const dismissWelcome = useCallback(() => {
    writeStored(userScoped(STORAGE_KEY), "skipped");
    clearStored(userScoped(STEP_KEY));
    setStatus("idle");
    // The person most likely to want the tour back is the one who closed it
    // before it began - and until 2026-09-19 nothing ever told them it could
    // be reopened, or from where.
    showReopenHint();
  }, [showReopenHint]);

  const nextStep = useCallback(() => {
    setStepIndex((i) => {
      if (i + 1 >= stepsRef.current.length) {
        // End of the catch-up: it was one card, not the tour - no completion
        // screen, and vd_tour_status keeps the decision the visitor made.
        if (catchUpRunRef.current) {
          writeStored(userScoped(SECURITY_SEEN_KEY), "1");
          setStatus("idle");
          return i;
        }
        // End of a Court region: back to the map, not the first-run completion.
        if (courtRunRef.current) {
          setStatus("idle");
          setCourtOpen(true);
          return i;
        }
        setStatus("completed");
        writeStored(userScoped(STORAGE_KEY), "done");
        clearStored(userScoped(STEP_KEY));
        // The full walk carries the security step, so its catch-up is owed to
        // nobody who finished it (also covers a jump straight to the end).
        writeStored(userScoped(SECURITY_SEEN_KEY), "1");
        return i;
      }
      return i + 1;
    });
  }, []);

  const prevStep = useCallback(() => {
    setStepIndex((i) => Math.max(0, i - 1));
  }, []);

  // Both exits mark the session, not just the happy path: someone who skips
  // at step 3 is at least as annoyed by AgentsGuide opening ten seconds later
  // as someone who finished. dismissWelcome deliberately does NOT set it —
  // dismissing the hero means the tour never ran, so there is nothing for a
  // later modal to be redundant with.
  const skipTour = useCallback(() => {
    // Dismissing the catch-up (Skip, the close button, Escape) is seeing it:
    // record that, and nothing else - no decision, no session flag, no
    // "reopen" note for a tour this visitor already dealt with.
    if (catchUpRunRef.current) {
      writeStored(userScoped(SECURITY_SEEN_KEY), "1");
      setStatus("idle");
      return;
    }
    if (courtRunRef.current) {
      setStatus("idle");
      setCourtOpen(true);
      return;
    }
    writeStored(userScoped(STORAGE_KEY), "skipped");
    clearStored(userScoped(STEP_KEY));
    writeSession(SESSION_END_KEY, "1");
    setStatus("idle");
    // Skipped half-way: same reasoning as dismissWelcome. finishTour does NOT
    // show it - the completion modal it closes already carries the same line.
    showReopenHint();
  }, [showReopenHint]);

  const finishTour = useCallback(() => {
    // Only the completion screen calls this, which the catch-up never reaches;
    // guarded anyway so it can never overwrite the visitor's recorded choice.
    if (catchUpRunRef.current) {
      writeStored(userScoped(SECURITY_SEEN_KEY), "1");
      setStatus("idle");
      return;
    }
    writeStored(userScoped(STORAGE_KEY), "done");
    writeStored(userScoped(SECURITY_SEEN_KEY), "1");
    clearStored(userScoped(STEP_KEY));
    writeSession(SESSION_END_KEY, "1");
    setStatus("idle");
  }, []);

  const goToStep = useCallback(
    (index: number) => {
      // Resolve only if nothing has yet — this is normally reached from the
      // overlay's own progress dots, i.e. from a tour that is already walking
      // a resolved list, and re-running the DOM check there could silently
      // change the list out from under the index the visitor just clicked.
      const list = resolvedRef.current ? stepsRef.current : resolveSteps();
      // Nothing to jump to — leave the tour where it is rather than entering
      // "active" with no card. Unreachable from the overlay (its dots only
      // exist once a step is showing), so this is the defensive arm of the
      // same empty-list contract the arming path implements.
      if (list.length === 0) return;
      setStepIndex(clampIndex(index, list.length));
      setStatus("active");
    },
    [resolveSteps]
  );

  // Replay ALWAYS re-resolves. It is the one entry point that can be minutes
  // or days after the first run — the plan may have changed, the comp grant
  // may have lapsed, an admin may have flipped the workspace to SIMPLE — and
  // it is invoked from a page where the shell is unambiguously mounted.
  //
  // Empty result: stay idle. A replay that resolves to nothing has nothing to
  // replay, and the Help page keeps its entry point — pressing it again after
  // whatever dropped the anchors has settled works.
  //
  // 2026-09-19: "stay idle" is now the LAST resort, not the first. Replay is an
  // explicit request, so it takes the same no-reachability fallback startTour
  // does (see there), and it reports whether anything started so the "?" panel
  // can say so rather than appear dead.
  const replayTour = useCallback((): boolean => {
    const list = resolveRequestedSteps();
    if (list.length === 0) return false;
    // A replay from the "?" panel during the catch-up becomes the full tour.
    endCatchUp();
    dismissReopenHint();
    setStepIndex(0);
    setStatus("active");
    return true;
  }, [resolveRequestedSteps, dismissReopenHint, endCatchUp]);

  // The Court. Region steps are the visitor's own audience/entitlement-filtered
  // list narrowed to the region - reachability waived, as for any explicit
  // request (the overlay centres a card whose anchor is missing).
  const courtStopCount = useCallback(
    (id: CourtRegionId): number => {
      const region = courtRegion(id);
      if (!region) return 0;
      return regionSteps(
        region,
        selectSteps(TOUR_STEPS, access, () => true)
      ).length;
    },
    [access]
  );
  const openCourt = useCallback(() => {
    // The map replaces a pending welcome or a running walk; neither records a decision.
    if (!courtRunRef.current) setStatus((s) => (s === "welcome" || s === "active" ? "idle" : s));
    setCourtOpen(true);
  }, []);
  const closeCourt = useCallback(() => setCourtOpen(false), []);
  const walkCourtRegion = useCallback(
    (id: CourtRegionId): boolean => {
      const region = courtRegion(id);
      if (!region) return false;
      const list = regionSteps(
        region,
        selectSteps(TOUR_STEPS, accessRef.current, () => true)
      );
      if (list.length === 0) return false;
      endCatchUp();
      courtRunRef.current = true;
      stepsRef.current = list;
      resolvedRef.current = true;
      setSelectedSteps(list);
      setStepIndex(0);
      setCourtOpen(false);
      setStatus("active");
      return true;
    },
    [endCatchUp]
  );

  const currentStep = status === "active" ? (steps[stepIndex] ?? null) : null;

  const value = useMemo<TourContextValue>(
    () => ({
      status,
      stepIndex,
      currentStep,
      steps,
      totalSteps: steps.length,
      audience: access.audience,
      access,
      startTour,
      dismissWelcome,
      nextStep,
      prevStep,
      skipTour,
      finishTour,
      goToStep,
      replayTour,
      hostedModelAvailable,
      reopenHintVisible,
      dismissReopenHint,
      courtOpen,
      openCourt,
      closeCourt,
      walkCourtRegion,
      courtStopCount,
      catchUp,
    }),
    [
      status,
      stepIndex,
      currentStep,
      steps,
      access,
      startTour,
      dismissWelcome,
      nextStep,
      prevStep,
      skipTour,
      finishTour,
      goToStep,
      replayTour,
      hostedModelAvailable,
      reopenHintVisible,
      dismissReopenHint,
      courtOpen,
      openCourt,
      closeCourt,
      walkCourtRegion,
      courtStopCount,
      catchUp,
    ]
  );

  return (
    <TourContext.Provider value={value}>
      {children}
      {/* ?tour=field:<id> deep links. useSearchParams needs a Suspense boundary
          in a root-layout component, and this must never block first paint. */}
      <Suspense fallback={null}>
        <FieldSpotlight />
      </Suspense>
      <CourtMap />
    </TourContext.Provider>
  );
}

