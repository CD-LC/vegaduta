"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { usePathname, useRouter } from "next/navigation";
import { AnimatePresence, motion } from "motion/react";
import { useTour, type TourStep } from "../TourProvider";
import { CARD_W, DOCK_BREAKPOINT, computePosition } from "./overlay/geometry";
import { useTourTarget, useViewport, useDialogFocus } from "./overlay/hooks";
import { CloseButton, IllustrationBand, Points, ProgressRail, Spotlight } from "./overlay/parts";
import WelcomeModal from "./overlay/WelcomeModal";
import CompletionModal from "./overlay/CompletionModal";
import ReopenHint from "./overlay/ReopenHint";
import { useTourKeyboard } from "./overlay/useTourKeyboard";
import {
  TOUR_NS,
  audienceVariantToken,
  planVariantToken,
  readTourAccess,
  useVariantCopy,
  variantKeys,
} from "./overlay/copyVariants";
import { copyOrFallback, hostedModelClaimAllowed, optionalTourCopy } from "./consumerGuide";
import { isPersonalEdition } from "../../lib/edition";
import { useTranslations } from "../../lib/i18n";
import { useReducedMotionSafe } from "../../lib/useReducedMotionSafe";
import { Button } from "../ui/button";

// Re-exported: tests and other callers import these from "./TourOverlay".
export { audienceVariantToken, planVariantToken, variantKeys };

// Product tour chrome: welcome hero -> the spotlit steps the provider selected
// for THIS user -> completion screen.
//
// Step count is no longer a constant (round 3, audience-aware tour): the
// provider filters TOUR_STEPS by who the visitor is (platform-admin /
// tenant-admin / member), what their plan entitles them to, whether they are in
// SIMPLE or ADVANCED mode, and whether the step's target is even reachable on
// their nav. So a member really does see "Step 2 of 6". Everything in here that
// counts steps reads `tour.totalSteps` / `tour.stepIndex` and nothing imports
// TOUR_STEPS - see the step counter, the progress rail and `isLast` below.
//
// EVERYTHING HERE IS PORTALED INTO document.body. This is not cosmetic: an
// ancestor carrying `backdrop-filter` creates a new containing block for
// `position: fixed` descendants, which silently confines a "cover the whole
// viewport" fixed layer to that ancestor's box. That exact bug shipped once
// already - the old OnboardingTour was mounted inside AppShell's
// `.ax-topbar` (backdrop-blur-xl, still there today at AppShell.tsx's sticky
// header) and its centred briefing rendered squashed into the 60px topbar
// strip instead of the viewport ("replay the welcome briefing is not seen
// properly, it should display at the center of the page"). Portaling to
// document.body is the fix, and it also makes this component immune to
// wherever a future refactor decides to mount it. Same reason the spotlight
// SVG is a SIBLING of the card and not its parent: if the dimmer ever grows a
// blur, a descendant card would inherit the same containing-block trap.
//
// Copy lives in en.json's `tour` namespace (funnel audit, 2026-09) so the
// 30-locale pipeline covers the very first thing a new tenant reads
// post-login; TOUR_STEPS keeps the English source as an in-code reference.
// Some of that copy now has per-audience / per-plan VARIANTS; see the
// "Copy variants" section below for the key convention and the fallback.

/** Card geometry + the viewport margins placement clamps against. */
export default function TourOverlay() {
  const tour = useTour();
  const router = useRouter();
  const t = useTranslations("tour");
  const tv = useVariantCopy(t);
  const reduced = useReducedMotionSafe();
  const step = tour.currentStep;
  const viewport = useViewport();

  // Who this visitor is and what their workspace can reach, resolved once in
  // the provider. Used only where it makes the page say something TRUER, never
  // to show off that we know:
  //
  //  - the plan-keyed step-0 copy. Step 0 is the one card that makes a PRICING
  //    claim ("Free to start, hosted model included" / "No API key, no card to
  //    start"). Every new tenant currently lands inside a complimentary
  //    UNLIMITED grant and reverts to FREE when it lapses, so a single
  //    hardcoded sentence there is guaranteed to be false for somebody. Keying
  //    it off `plan` is how the same card stays honest on both sides of that
  //    boundary - the same failure that produced the ChannelWall lane1/lane2
  //    contradiction on the landing page.
  //  - the audience-keyed completion copy, and the welcome hero's one-line
  //    pitch (a platform-admin is not about to build their first agent).
  //  - one behavioural difference: the completion modal does not offer a
  //    platform-admin the "Open Build Studio" / agent buttons (see below).
  //
  // NOT used for an audience badge or any other "look, we know who you are"
  // ornament: the audience already decides which steps exist, which is where a
  // reader actually feels it. The length promise next to it is keyed on the
  // step COUNT rather than the audience, for the same reason - the count is
  // what the sentence is actually about.
  const access = useMemo(() => readTourAccess(tour), [tour]);
  const audience = audienceVariantToken(access.audience);
  const plan = planVariantToken(access.plan);
  // Audience first, then plan. The `?? "Free"` tail is a real fallback, not a
  // guess dressed up as one: step 0's un-varied `startTitle` was retired when
  // the per-plan wording landed, so a workspace whose plan never resolved (the
  // settings fetch is best-effort and can come back null) needs SOME variant to
  // land on. Free is the honest default there - it is the claim the product
  // makes to everyone at the door, and it never over-promises to a tenant whose
  // plan we could not read.
  // A personal install goes first: its own wording (`channelsPersonalBody`) wins where one exists, so the
  // Channels step names the channels a personal install has instead of Slack and a "free plan". With no
  // such key, or on lite/full (the entry is null and skipped), the chain is exactly what it was.
  const stepVariants = useMemo(
    () => [isPersonalEdition() ? "Personal" : null, audience, plan ?? "Free"],
    [audience, plan]
  );
  const audienceVariants = useMemo(() => [audience], [audience]);
  // Same reasoning for the completion modal, which has no un-varied key either:
  // Admin is the wording every visitor got before this round, so an unresolved
  // audience lands exactly where it used to.
  const completionVariants = useMemo(() => [audience, "Admin"], [audience]);

  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const { rect, label: targetLabel } = useTourTarget(tour.status === "active" ? (step?.target ?? null) : null, reduced);

  const cardRef = useRef<HTMLDivElement | null>(null);
  const modalRef = useRef<HTMLDivElement | null>(null);
  const [cardSize, setCardSize] = useState({ w: CARD_W, h: 0 });
  const titleId = useId();
  const bodyId = useId();
  const referentId = useId();

  // The card's height varies with copy length, and placement has to clamp
  // against the real number or a long step runs off the bottom edge.
  useEffect(() => {
    const node = cardRef.current;
    if (!node || typeof ResizeObserver === "undefined") return undefined;
    const ro = new ResizeObserver(() => {
      const r = node.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) setCardSize({ w: r.width, h: r.height });
    });
    ro.observe(node);
    return () => ro.disconnect();
  }, [tour.status, step?.id]);

  const docked = viewport.w > 0 && viewport.w < DOCK_BREAKPOINT;

  const position = useMemo(() => {
    if (!rect || docked || !step || viewport.w === 0) return null;
    return computePosition(rect, step.placement, cardSize.w, cardSize.h, viewport.w, viewport.h);
  }, [rect, docked, step, cardSize.w, cardSize.h, viewport.w, viewport.h]);

  const isActive = tour.status === "active" && !!step;
  const isWelcome = tour.status === "welcome";
  // A workspace admin gets the richer welcome (who you are, invite your team,
  // start building) in the same slot - see AdminWelcomeBody.
  const isAdminWelcome = tour.audience === "tenant-admin";
  const pathname = usePathname();
  const startBuilding = () => {
    tour.dismissWelcome();
    if (pathname !== "/build") router.push("/build");
  };
  const isCompleted = tour.status === "completed";
  const isLast = tour.stepIndex + 1 === tour.totalSteps;

  useDialogFocus(isActive && !tour.catchUp, cardRef, step?.id ?? "");
  useDialogFocus(isWelcome || isCompleted, modalRef, tour.status);

  useTourKeyboard(tour, { isWelcome, isCompleted, isActive });

  // "Show me" navigates and LEAVES YOU ON THIS STEP. It used to call
  // router.push(step.href) and tour.nextStep() in the same tick, so asking to
  // be shown Agents landed you on /agents while the card had already repainted
  // to "Workflows put steps in order" and the spotlight had jumped away - the
  // one control whose entire promise is "look at this" was the one control
  // that guaranteed you could not. Staying put is the right half to keep: the
  // target poll in useTourTarget re-finds the step's element on the new route,
  // so the light lands on it, the copy still matches, and advancing stays the
  // user's decision (Next / Enter / arrow key) rather than a side effect.
  const showMe = useCallback(() => {
    if (step?.href) router.push(step.href);
  }, [router, step]);

  const openBuildStudio = useCallback(() => {
    router.push("/build");
    tour.finishTour();
  }, [router, tour]);

  // INLINE STEPS (2026-09-19). A step flagged `inline` has no en.json keys yet
  // (see TourStep.inline for why). The key is still asked for FIRST, through
  // the same variant chain as every other step, so a translated string wins the
  // moment it exists; only a missing key falls back to the step's own English.
  // A step WITHOUT the flag never takes the fallback - for those a missing key
  // must stay loud (tour.test.tsx fails on it), not be papered over here.
  const stepCopy = useCallback(
    (
      current: TourStep,
      suffix: "Title" | "Body" | "Point1" | "Point2",
      variants: Array<string | null | undefined>,
      values: Record<string, string | number>
    ): string => {
      const translated = tv(current.titleKey, suffix, variants, values);
      if (!current.inline) return translated;
      const fallback =
        suffix === "Title"
          ? current.title
          : suffix === "Body"
            ? current.body
            : suffix === "Point1"
              ? (current.points?.[0] ?? "")
              : (current.points?.[1] ?? "");
      return copyOrFallback(translated, `${current.titleKey}${suffix}`, fallback, TOUR_NS);
    },
    [tv]
  );

  const goAndFinish = useCallback(
    (href: string) => {
      router.push(href);
      tour.finishTour();
    },
    [router, tour]
  );

  if (!mounted) return null;

  const fade = reduced
    ? { duration: 0 }
    : { duration: 0.18, ease: [0.16, 1, 0.3, 1] as [number, number, number, number] };
  const cardShell = "overflow-hidden border border-border bg-card text-card-foreground shadow-[var(--shadow-modal)]";

  // Offered to every step string so copy can name the real length of THIS
  // user's tour - "{total} short stops" survives a member's 6-step walk, where
  // the literal "Eight short stops" the 9-step version shipped with does not.
  // A string with no placeholder is returned untouched, so this is free for the
  // other steps.
  const stepCopyValues = { current: tour.stepIndex + 1, total: tour.totalSteps };

  // The completion modal's primary action is "Open Build Studio" -> /build. A
  // platform-admin operates the platform and may legitimately have NO tenant at
  // all, so that button is either meaningless or a dead end for them - the same
  // class of bug as the channels step's "Show me" bouncing a non-admin off
  // /admin/channels. Their completion screen keeps only the dismiss button,
  // promoted to the primary style so the modal still has one obvious way out.
  // Unknown audience (no resolved access) keeps the existing behaviour.
  const showCreateAgentCta = audience !== "Platform";

  // Only on the opening step, only for a tenant audience, only when true.
  const showHostedNote =
    step?.id === "start" &&
    audience !== "Platform" &&
    hostedModelClaimAllowed({
      plan: access.plan,
      hostedAvailable: (tour as { hostedModelAvailable?: boolean | null }).hostedModelAvailable,
    });

  return createPortal(
    <>
      {/* ---------------- Click blockers ---------------- */}
      {/* WHAT BLOCKS THE APP IS NEVER AN ANIMATED NODE (2026-09-19).
          The three dimmers below live inside <AnimatePresence>, which keeps a
          node MOUNTED until its exit animation reports completion. They used
          to be the click-catchers too, so for the length of every fade-out -
          and for as long as that fade-out failed to complete (a backgrounded
          tab pauses rAF; a throw inside the animation loop never resumes it) -
          an invisible full-viewport layer sat over a logged-in user's app and
          ate every click. A tour that has ENDED must not be able to do that:
          it is an outage for that user with nothing on screen to explain it.

          So the dimmers are now pointer-events-none paint, and blocking is
          done by these plain divs, which are outside AnimatePresence and
          therefore leave the DOM in the same commit the status changes. Same
          stacking slot as the dimmer they pair with (below the cards at 1152),
          same click behaviour the dimmers had: the two modal backdrops
          dismiss, the step scrim deliberately does not (see its own note). */}
      {isWelcome && (
        <div
          data-tour-blocker="welcome"
          className="fixed inset-0 z-[1150]"
          onClick={tour.dismissWelcome}
          aria-hidden="true"
        />
      )}
      {isActive && !rect && !tour.catchUp && (
        <div data-tour-blocker="step" className="fixed inset-0 z-[1150]" aria-hidden="true" />
      )}
      {isCompleted && (
        <div
          data-tour-blocker="completed"
          className="fixed inset-0 z-[1150]"
          onClick={tour.finishTour}
          aria-hidden="true"
        />
      )}

      {/* ---------------- Welcome hero ---------------- */}
      <WelcomeModal
        open={tour.status === "welcome"}
        isAdminWelcome={isAdminWelcome}
        totalSteps={tour.totalSteps}
        reduced={reduced}
        fade={fade}
        cardShell={cardShell}
        modalRef={modalRef}
        titleId={titleId}
        bodyId={bodyId}
        t={t}
        tv={tv}
        audienceVariants={audienceVariants}
        onStart={tour.startTour}
        onBuild={startBuilding}
        onDismiss={tour.dismissWelcome}
      />

      {/* ---------------- Spotlit step ---------------- */}
      {/* The card renders even when the target element can't be found
          (rect === null) - centred, over a plain scrim, without a spotlight -
          instead of rendering
          nothing. v1 shipped the opposite and a step whose target wasn't in
          the DOM left the user with an invisible, un-advanceable tour after
          clicking "Take the tour". It still matters: several nav rows are
          absent for non-admin users, the rail is parked off-screen below
          `lg`, and step 0 has no target at all by design. Keeping the card
          (with Skip/Back/Next) always reachable makes a missing target degrade
          gracefully rather than dead-end the whole tour. */}
      <AnimatePresence>
        {isActive && rect && !tour.catchUp && <Spotlight key="spotlight" rect={rect} reduced={reduced} />}
        {/* No rect -> no spotlight, and until now no dimmer either: the card
            declared role="dialog" aria-modal="true" and ran a Tab focus trap
            over a page that was fully bright and fully tappable. That is a lie
            in both directions - sighted users see no modal, assistive tech is
            told the rest of the page does not exist. This scrim makes the
            modal state honest for every step that has no anchor (step 0 by
            design, admin-only nav rows a member cannot see, a target that
            never mounts). It intentionally does NOT dismiss on click: losing
            an onboarding tour to a stray tap is worse than having to use the
            card's own Skip/close, and Escape skips from anywhere. The spotlit
            case keeps its pointer-events:none SVG so the highlighted control
            stays usable - only the no-target case blocks. */}
        {isActive && !rect && !tour.catchUp && (
          <motion.div
            key="tour-scrim"
            // Paint only - see "Click blockers" above for who catches clicks.
            className="pointer-events-none fixed inset-0 z-[1150] bg-black/55"
            initial={reduced ? false : { opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={fade}
            aria-hidden="true"
          />
        )}
      </AnimatePresence>

      {isActive && step && (
        // The dialog element itself is deliberately NOT keyed on the step and
        // NOT wrapped in AnimatePresence. It has to survive every step change,
        // because useDialogFocus refocuses `cardRef.current` whenever the step
        // id changes - with an AnimatePresence mode="wait" swap, the node the
        // ref points at during that render is the OUTGOING card (the incoming
        // one only mounts once the exit animation finishes), so focus landed on
        // a card that was on its way out and the new one was never announced.
        // One stable dialog + a keyed, remounting body gives the same
        // step-to-step transition without the focus/aria-live hole.
        <motion.div
          ref={cardRef}
          role="dialog"
          aria-modal={tour.catchUp ? "false" : "true"}
          // The dialog NODE survives every step change (see below), so its
          // accessible name has to be the stable one - `dialogLabel`, which
          // was sitting unused in en.json - and not the current step's title.
          // The per-step title, body and referent ride in aria-describedby, so
          // they are still read out when focus lands on the refreshed card.
          aria-label={t("dialogLabel")}
          aria-describedby={targetLabel ? `${titleId} ${bodyId} ${referentId}` : `${titleId} ${bodyId}`}
          tabIndex={-1}
          initial={reduced ? false : { opacity: 0, y: 6, scale: 0.985 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={fade}
          className={
            docked
              ? `fixed inset-x-0 bottom-0 z-[1152] w-full rounded-t-2xl outline-none ${cardShell}`
              : tour.catchUp
                ? // The two-factor catch-up is a note, not a modal (SE-18): bottom
                  // right, clear of the top-bar toolbars it points at.
                  `fixed bottom-4 right-4 z-[1152] w-[360px] max-w-[calc(100vw-24px)] rounded-xl outline-none ${cardShell}`
                : position
                  ? `fixed z-[1152] w-[360px] max-w-[calc(100vw-24px)] rounded-xl outline-none ${cardShell}`
                  : // Centred fallback (no resolved target): `inset-0 m-auto h-fit`
                    // centres a fixed box without a transform, which motion would
                    // rebuild every frame and discard.
                    `fixed inset-0 z-[1152] m-auto h-fit w-[360px] max-w-[calc(100vw-24px)] rounded-xl outline-none ${cardShell}`
          }
          style={
            docked
              ? // maxHeight + overflowY (2026-09-19): a bottom sheet taller than
                // a short viewport (a phone in landscape is ~360px high; the
                // illustration alone is 160-180px) pushed its own top - the
                // close button and the title - off the top of the screen with
                // no way to scroll to them. INLINE on purpose: `cardShell`
                // carries `overflow-hidden`, and a utility class would lose to
                // it or win over it by Tailwind's emission order, not by what
                // is written here (IllustrationBand's docblock records the same
                // trap). `vh` rather than `dvh` so an older Safari does not
                // drop the whole declaration.
                {
                  paddingBottom: "env(safe-area-inset-bottom, 0px)",
                  maxHeight: "92vh",
                  overflowY: "auto",
                }
              : position && !tour.catchUp
                ? {
                    top: position.top,
                    left: position.left,
                    maxHeight: "85vh",
                    overflowY: "auto",
                  }
                : {
                    maxHeight: "85vh",
                    overflowY: "auto",
                  }
          }
        >
          <CloseButton label={t("closeLabel")} onClick={tour.skipTour} />
          {/* Keyed on the step: React remounts it, so initial -> animate replays
              as a fresh entrance on every step without an exit-then-enter gap
              that would collapse the card's height between steps. */}
          <motion.div
            key={step.id}
            initial={reduced ? false : { opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={fade}
          >
            <IllustrationBand name={step.illustration} />
          </motion.div>
          <div className="p-4">
            {/* This counter is the live region, so its NODE has to persist
                across steps - a live region that is inserted at the same moment
                its text appears is usually not announced at all (screen readers
                only report mutations to regions that were already there). That
                is why it sits here, in the stable part of the card, and not
                inside the keyed block below. */}
            <div className="flex items-center justify-between gap-2">
              <p
                aria-live="polite"
                className="text-[11px] font-medium uppercase tracking-[0.14em] text-muted-foreground"
              >
                {/* One-off catch-up card (the 2FA step shown once to visitors who
                    finished the tour before it existed): no "Step 1 of 1". */}
                {tour.catchUp
                  ? t("catchUpEyebrow")
                  : t("stepCounter", { current: tour.stepIndex + 1, total: tour.totalSteps })}
              </p>
              {/* The step's textual referent. Without it a spotlit step has NO
                  name for what it is pointing at: the illustrations are
                  aria-hidden by design, and `aria-modal="true"` removes the
                  spotlit nav row itself from the accessibility tree, so an
                  assistive-tech user got nine cards describing an element they
                  were never told about. It is deliberately the target
                  element's OWN text (read off the DOM in useTourTarget), not a
                  second copy of the nav label in en.json - one string, already
                  translated by the app, that cannot drift from what the light
                  is actually sitting on. Visible rather than sr-only because
                  the same ambiguity exists for sighted users on a small
                  viewport, where the rail may be scrolled or freshly opened.
                  Absent when no target resolved (step 0 has none). */}
              {targetLabel && (
                <span
                  id={referentId}
                  className="flex min-w-0 items-center gap-1.5 rounded-full border border-border px-2 py-0.5 text-[11px] font-medium text-muted-foreground"
                >
                  <span className="size-1.5 shrink-0 rounded-full bg-primary" aria-hidden="true" />
                  <span className="truncate">{targetLabel}</span>
                </span>
              )}
            </div>
            <motion.div
              key={step.id}
              initial={reduced ? false : { opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={fade}
            >
              {/* Variant-resolved, un-varied-key fallback (see useVariantCopy).
                  In practice only the opening step has variants written for it -
                  it is the one card that makes a pricing claim - but the
                  resolution is applied to every step's four strings rather than
                  special-cased to step 0, so a later plan- or audience-specific
                  line needs an en.json key and nothing else. A step with no
                  variant key renders exactly what it rendered before, whether
                  its stem is `start` (one step, resolved here) or `startFree`
                  (three steps, resolved by the provider's own selection) - both
                  spellings land on the same string. */}
              <h3 id={titleId} className="mt-1 text-base font-semibold tracking-tight text-foreground">
                {stepCopy(step, "Title", stepVariants, stepCopyValues)}
              </h3>
              <p id={bodyId} className="mt-1 text-[13px] leading-relaxed text-muted-foreground">
                {stepCopy(step, "Body", stepVariants, stepCopyValues)}
              </p>
              <Points
                one={stepCopy(step, "Point1", stepVariants, stepCopyValues)}
                two={stepCopy(step, "Point2", stepVariants, stepCopyValues)}
              />
              {/* The hosted-model fact (2026-09-19). Step 0's translated copy is
                  keyed on the PLAN NAME, and a brand-new signup holds a
                  complimentary grant, so they read the `startPaid*` wording -
                  which never says a hosted model is on or that no API key is
                  needed. This line says it whenever the SERVER confirms it (or
                  the plan is positively FREE) and says nothing otherwise; see
                  hostedModelClaimAllowed. Inside the keyed block so it enters
                  and leaves with the step, and inside aria-describedby's body
                  sibling order so a screen reader hears it after the bullets. */}
              {showHostedNote && (
                <p
                  data-testid="tour-hosted-note"
                  className="mt-3 rounded-lg border border-border px-3 py-2 text-[12.5px] leading-snug text-foreground"
                  style={{ background: "color-mix(in srgb, var(--primary) 8%, transparent)" }}
                >
                  {optionalTourCopy(t, "hostedNote")}
                </p>
              )}
            </motion.div>

            <div className="mt-4 border-t border-border pt-3">
              {!tour.catchUp && (
                <ProgressRail
                  total={tour.totalSteps}
                  index={tour.stepIndex}
                  onJump={tour.goToStep}
                  // `progress` ("{current} / {total}"), not `stepCounter`: the
                  // rail used to carry the same sentence as the aria-live
                  // counter six lines up, so the position was announced twice in
                  // two different voices. This is the short form, and it is what
                  // the (previously unused) key was minted for.
                  label={t("progress", { current: tour.stepIndex + 1, total: tour.totalSteps })}
                  jumpLabel={(n) => t("jumpToStep", { n })}
                />
              )}
              <div className={`mt-3 flex items-center gap-2 ${tour.catchUp ? "justify-end" : "justify-between"}`}>
                {!tour.catchUp && (
                  <Button onClick={tour.skipTour} variant="ghost" size="sm">
                    {t("skip")}
                  </Button>
                )}
                <div className="flex items-center gap-1.5">
                  {step.href && (
                    <Button onClick={showMe} variant="outline" size="sm">
                      {t("showMe")}
                    </Button>
                  )}
                  {tour.stepIndex > 0 && (
                    <Button onClick={tour.prevStep} variant="ghost" size="sm">
                      {t("back")}
                    </Button>
                  )}
                  <Button onClick={tour.nextStep} size="sm">
                    {tour.catchUp ? t("gotIt") : isLast ? t("finish") : t("next")}
                  </Button>
                </div>
              </div>
            </div>
          </div>
        </motion.div>
      )}

      {/* ---------------- Completion ---------------- */}
      <CompletionModal
        open={tour.status === "completed"}
        showCreateAgentCta={showCreateAgentCta}
        completionVariants={completionVariants}
        reduced={reduced}
        fade={fade}
        cardShell={cardShell}
        modalRef={modalRef}
        titleId={titleId}
        bodyId={bodyId}
        t={t}
        tv={tv}
        audienceVariants={audienceVariants}
        onOpenBuildStudio={openBuildStudio}
        onOpenAgents={() => goAndFinish("/agents")}
        onFinish={tour.finishTour}
      />

      {/* ---------------- "You can reopen this" note (see ./overlay/ReopenHint) ---------------- */}
      {tour.status === "idle" && (tour as { reopenHintVisible?: boolean }).reopenHintVisible === true && (
        <ReopenHint t={t} onDismiss={() => (tour as { dismissReopenHint?: () => void }).dismissReopenHint?.()} />
      )}
    </>,
    document.body
  );
}
