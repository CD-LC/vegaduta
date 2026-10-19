"use client";

import { useEffect, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { isLanguagePromptPending } from "../../lib/firstRunGate";
import { overlayHold } from "../../lib/overlayGate";
import { selectSteps, type TourAccess } from "./audience";
import type { TourStep } from "./types";
import { TOUR_STEPS } from "./steps";
import type { TourStatus } from "./context";
import {
  ARM_DELAY_MS,
  BUSY_POLL_MS,
  BUSY_WAIT_MS,
  SECURITY_SEEN_KEY,
  SECURITY_STEP_ID,
  SELECTION_POLL_MS,
  SELECTION_WAIT_MS,
  STEP_KEY,
  STORAGE_KEY,
  clampIndex,
  readStored,
  userScoped,
} from "./storage";

export interface TourArmingDeps {
  statusRef: MutableRefObject<TourStatus>;
  courtOpenRef: MutableRefObject<boolean>;
  courtRunRef: MutableRefObject<boolean>;
  catchUpRunRef: MutableRefObject<boolean>;
  accessRef: MutableRefObject<TourAccess>;
  stepsRef: MutableRefObject<TourStep[]>;
  resolvedRef: MutableRefObject<boolean>;
  setCatchUp: Dispatch<SetStateAction<boolean>>;
  setSelectedSteps: Dispatch<SetStateAction<TourStep[] | null>>;
  setStepIndex: Dispatch<SetStateAction<number>>;
  setStatus: Dispatch<SetStateAction<TourStatus>>;
  canCommitSelection: () => boolean;
  resolveSteps: () => TourStep[];
  isTargetPresent: (target: string | null) => boolean;
}

/**
 * Arms the tour once the console shell is on screen: the first-run welcome,
 * a resume of a mid-tour reload, or (for a visitor who already decided) the
 * security catch-up. Refs and setters come from TourProvider and are stable.
 */
export function useTourArming(deps: TourArmingDeps) {
  const {
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
  } = deps;
  // Deliberately NOT gated via useAuth() — see TourProvider's roles block. The nav
  // rail's data-tour="nav-rail" marker only ever renders inside AppShell,
  // i.e. only on authenticated console pages, so waiting for it in the DOM is
  // a reliable, hook-free proxy for "this is a logged-in visitor looking at
  // the real product", checked via MutationObserver since first paint can
  // race this effect on a cold load.
  //
  // The 900ms-after-nav-rail arming is unchanged by the v2 and v3 rebuilds,
  // and both the resume path and the audience selection ride on exactly the
  // same trigger: a restored tour appears in the same places, at the same
  // moment, under the same "nav rail exists" condition as the welcome it
  // stands in for. Nothing auto-fires anywhere it did not before.
  useEffect(() => {
    const seen = readStored(userScoped(STORAGE_KEY));
    // A decided visitor gets, at most, the catch-up (see SECURITY_STEP_ID),
    // armed on the same trigger and the same gates as the first-run welcome.
    const catchUpMode = !!seen;
    if (catchUpMode && readStored(userScoped(SECURITY_SEEN_KEY))) return undefined;

    // Resume: a stored index only exists if this browser was mid-tour and the
    // page reloaded before any decision was recorded. Index 0 resumes to the
    // welcome modal (one click to re-show, versus silently dropping the hero
    // from a barely-begun first visit); only a step > 0 skips past it. The
    // parse is NOT clamped here: the bound is the length of a list that does
    // not exist until resolveSteps() runs, so clamping happens in the timeout.
    const storedStep = readStored(userScoped(STEP_KEY));
    const storedIndex = storedStep === null ? 0 : Number.parseInt(storedStep, 10);

    let timer: number | undefined;
    let armed = false;
    let giveUpAt = 0;
    let languageGiveUpAt = 0;
    let busyGiveUpAt = 0;

    // The catch-up's commit. Eligibility is the REAL selection (rules 1-4:
    // audience, entitlement, SIMPLE, reachability) run over the whole
    // catalogue, then narrowed to the security step - so a platform-admin, or
    // a shell whose TwoFactorButton is not painted (or not painted YET: the
    // AppShell-remount-on-navigation case the first-run comment below
    // describes), simply gets nothing. Nothing is recorded on that path, so the
    // next full load tries again; no fallback to an unanchored card, because
    // nobody asked for this one (same reasoning as the arming path's rule 4).
    const commitCatchUp = () => {
      if (readStored(userScoped(SECURITY_SEEN_KEY))) return;
      if (statusRef.current !== "idle" || courtOpenRef.current || courtRunRef.current) return;
      const step = selectSteps(TOUR_STEPS, accessRef.current, isTargetPresent).find((s) => s.id === SECURITY_STEP_ID);
      if (!step) return;
      const list = [step];
      catchUpRunRef.current = true;
      setCatchUp(true);
      stepsRef.current = list;
      resolvedRef.current = true;
      setSelectedSteps(list);
      setStepIndex(0);
      setStatus((s) => (s === "idle" ? "active" : s));
    };

    const commit = () => {
      // SE-18: never land a modal on a deep link to an editing surface or on
      // someone mid-keystroke / mid-turn (see lib/overlayGate).
      const hold = overlayHold(window.location.pathname);
      if (hold === "surface") return;
      if (hold === "busy") {
        if (Date.now() < busyGiveUpAt) timer = window.setTimeout(commit, BUSY_POLL_MS);
        return;
      }
      // First-run ordering (lib/firstRunGate): the language prompt answers
      // first, so its choice localises the tour and the two never stack. Capped
      // so a stuck prompt can never cost a visitor their tour.
      if (isLanguagePromptPending() && Date.now() < languageGiveUpAt) {
        timer = window.setTimeout(commit, SELECTION_POLL_MS);
        return;
      }
      // Entitlement gate (see canCommitSelection). Re-check on a short poll
      // rather than re-running this effect on tenantSettings, which would
      // rebuild the observer and restart the 900ms beat on every fetch.
      if (!canCommitSelection() && Date.now() < giveUpAt) {
        timer = window.setTimeout(commit, SELECTION_POLL_MS);
        return;
      }

      if (catchUpMode) {
        commitCatchUp();
        return;
      }

      const list = resolveSteps();

      // selectSteps returned "run no tour at all" - usually transiently (rule 4
      // asks the DOM, and AppShell remounts on navigation). Do NOT arm and do
      // NOT write vd_tour_status: nothing was shown, so the next full load
      // re-arms against a settled DOM. Do NOT fall back to TOUR_STEPS: that
      // re-introduces the unreachable-spotlight bug rule 4 exists to kill.
      if (list.length === 0) return;

      const resumeAt = clampIndex(storedIndex, list.length);
      // `storedIndex > 0` and not `resumeAt > 0`: the question is whether
      // this visitor had genuinely started walking, which is a fact about
      // what they did, not about how long today's list happens to be.
      // Clamping only decides WHERE they land.
      if (storedIndex > 0) {
        setStepIndex(resumeAt);
        setStatus((s) => (s === "idle" ? "active" : s));
        return;
      }
      setStatus((s) => (s === "idle" ? "welcome" : s));
    };

    const armWelcome = () => {
      if (armed) return;
      armed = true;
      giveUpAt = Date.now() + ARM_DELAY_MS + SELECTION_WAIT_MS;
      languageGiveUpAt = Date.now() + 60_000;
      busyGiveUpAt = Date.now() + BUSY_WAIT_MS;
      timer = window.setTimeout(commit, ARM_DELAY_MS);
    };
    if (document.querySelector('[data-tour="nav-rail"]')) {
      armWelcome();
      return () => window.clearTimeout(timer);
    }
    const observer = new MutationObserver(() => {
      if (document.querySelector('[data-tour="nav-rail"]')) {
        armWelcome();
        observer.disconnect();
      }
    });
    observer.observe(document.body, { childList: true, subtree: true });
    return () => {
      observer.disconnect();
      window.clearTimeout(timer);
    };
  }, [
    canCommitSelection,
    resolveSteps,
    isTargetPresent,
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
  ]);
}
