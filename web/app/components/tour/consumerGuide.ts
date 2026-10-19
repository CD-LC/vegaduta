// "What should I do next?" - the always-available answer, plus the two facts
// the first-login tour has to land for a brand-new CONSUMER (2026-09-19).
//
// WHY THIS FILE EXISTS. Founder feedback: "look at the tour guide - still it's
// not explaining to consumers completely on landing, first login." The audit
// behind that found the mechanism sound (audience split, reachability rule,
// centred fallback, resume, replay) and four CONTENT holes:
//
//   1. A new signup is a tenant-admin in SIMPLE mode holding a complimentary
//      grant, so tenantSettings reports a PAID plan and step 0 rendered the
//      `startPaid*` wording - which never says a hosted model is included or
//      that no API key is needed. The one person that sentence was written for
//      was the one person who never saw it. Same root cause as the
//      "No active LLM provider yet" banner fixed today in lib/providerGate.ts:
//      the client inferred "hosted pool" from the plan name and missed the
//      grant. The tour now asks the server (GET /api/providers/availability).
//   2. Nothing said where on-device / Edge AI lives.
//   3. The tour ended on ONE button ("Create an agent"). There was no short
//      list of good first moves, and none at all once the tour was closed.
//   4. Someone who skipped half-way was never told how to get the tour back.
//      The "?" button existed; nothing pointed at it.
//
// WHY PLAIN STRINGS AND NOT t(). en.json is written by the locale pipeline and
// by several agents at once, and a key that does not exist renders its raw
// dotted path to the user. These strings are therefore the FALLBACK of a
// key-first lookup (see `copyOrFallback` below): the day a `tour.<key>` lands
// in en.json the translated string wins with no code change, and until then
// the reader sees plain English rather than "tour.hostedNote".
//
// NO FICTION. Every line below was checked against the code that backs it:
//   - hosted model / no key  -> ProviderAvailabilityResponse.platformHostedAvailable
//                               and TenantLimits#usesPlatformPool (FREE always).
//   - agent-first building   -> /build opens on the Agent builder surface;
//                               apps remain the outcome agents power.
//   - Ask AI drafts          -> AskAiHelper on the workbench's instructions field
//                               (mirrors shipped tour.agentsPoint2).
//   - Playground needs an    -> playground.noAgentsText: "Create an agent first".
//     agent first
//   - WhatsApp/Telegram/email-> migration V225 + tour.channelsPoint1.
//     on the Free plan
//   - Edge AI                -> shipped edge.subtitle; the step is dropped when
//                               NEXT_PUBLIC_EDGE_AGENTS is off (edgeEnabled()).
// Deliberately NOT mentioned: the browser-local /code assistant and anything
// else on PlatformLedger.tsx's do-not-claim list.
//
// Pure: no React, no DOM, no fetch - so every rule here is unit-tested.

import type { TourAudience } from "./audience";

/** Shown on the opening step ONLY when `hostedModelClaimAllowed` says so. */
export const HOSTED_MODEL_NOTE =
  "A hosted AI model is already switched on for this workspace. You do not need an API key or a provider to build and test your first agent or app.";

/** Completion modal + the skip toast: how to get the tour back. */
export const REOPEN_HINT = "You can reopen this tour anytime from the ? button at the top of the screen.";

/** Heading of the topbar "?" panel. */
export const NEXT_ACTIONS_TITLE = "What should I do next?";

/** The "?" panel's replay row. */
export const REPLAY_TOUR_LABEL = "Replay the product tour";

/** Shown in the "?" panel when replay could not start (see TourReopenButton). */
export const REPLAY_UNAVAILABLE =
  "The tour could not start on this screen. Open the Guide for a written walkthrough instead.";

/** Secondary button on the completion modal. */
export const CUSTOM_AGENT_CTA = "Customize an agent";

/**
 * May the tour say "a hosted model is included, no API key needed" to THIS
 * visitor?
 *
 * Yes when the server said so (platformHostedAvailable - covers FREE, the
 * usePlatformLlm opt-in AND a live complimentary grant), or when the plan is
 * positively FREE (core routes every FREE tenant to the hosted pool regardless
 * of any flag, so this holds even if the availability call failed).
 *
 * Otherwise NO, including when nothing is known: a paid workspace past its
 * grant with the hosted toggle off genuinely needs a provider key, and telling
 * that admin "no key needed" on a replay would be false. Silence is the honest
 * degrade - the step still renders, it just does not make the claim.
 */
export function hostedModelClaimAllowed(input: {
  plan: string | null | undefined;
  hostedAvailable: boolean | null | undefined;
}): boolean {
  if (input?.hostedAvailable === true) return true;
  return typeof input?.plan === "string" && input.plan.trim().toUpperCase() === "FREE";
}

export type NextAction = {
  id: string;
  label: string;
  /** One plain sentence: what happens when you press it. */
  hint: string;
  href: string;
};

const BUILD_AGENT: NextAction = {
  id: "build",
  label: "Build a custom agent",
  hint: "Describe the job, review the blueprint, then create the intelligence that powers everything else.",
  href: "/build",
};

const DESCRIBE_AGENT: NextAction = {
  id: "describe",
  label: "Tune a custom agent",
  hint: "Set its instructions, model, knowledge, tools and guardrails before anyone meets it.",
  href: "/agents",
};

const CONNECT_WORKFLOW: NextAction = {
  id: "workflow",
  label: "Connect a workflow",
  hint: "Arrange triggers, agents, approvals and outputs into one repeatable journey.",
  href: "/workflows",
};

const TEST_PRIVATELY: NextAction = {
  id: "playground",
  label: "Test it privately in the Playground",
  hint: "Chat with your agent before anyone else can. You need one agent first.",
  href: "/playground",
};

const CONNECT_CHANNEL: NextAction = {
  id: "channel",
  label: "Connect a channel",
  hint: "WhatsApp, Telegram and email are on the Free plan. Pick which agent answers on each.",
  // /admin/channels redirects a non-admin to "/", so this row is tenant-admin
  // only - the same dead end the tour's own Channels step was closed against.
  href: "/admin/channels",
};

const ON_DEVICE: NextAction = {
  id: "edge",
  label: "Run AI on this device",
  hint: "Edge AI downloads a model to your device. The page tells you if this device cannot run one.",
  href: "/edge",
};

const WRITTEN_GUIDE: NextAction = {
  id: "guide",
  label: "Read the Guide",
  hint: "A written walkthrough of every screen, with worked examples.",
  href: "/help",
};

/**
 * The ordered list for one audience. Order is the order a first-timer should
 * try them in. A platform-admin gets only the Guide: they may have no tenant
 * to build in (AuthProvider's tenant-less note), so every build action would
 * be a dead end for them.
 *
 * `edgeAvailable` is passed in rather than read from process.env here so this
 * module stays pure; the caller hands it lib/edge/capabilities#edgeEnabled().
 * Unknown audience degrades to the member list - the narrower one, per the
 * degrade-direction rule in ./audience.ts.
 */
export function nextActionsFor(
  audience: TourAudience | string | null | undefined,
  opts: { edgeAvailable?: boolean } = {},
): NextAction[] {
  if (audience === "platform-admin") return [WRITTEN_GUIDE];
  const list: NextAction[] = [BUILD_AGENT, DESCRIBE_AGENT, CONNECT_WORKFLOW, TEST_PRIVATELY];
  if (audience === "tenant-admin") list.push(CONNECT_CHANNEL);
  if (opts.edgeAvailable === true) list.push(ON_DEVICE);
  list.push(WRITTEN_GUIDE);
  return list;
}

/**
 * The tour chrome strings that have NO en.json key yet, by the key they will
 * get. One table so that (a) the components ask for them through
 * `optionalTourCopy` and never as a literal t("...") call - tour.test.tsx
 * asserts every literal key in TourOverlay exists, and that guard must keep
 * meaning "this key is REQUIRED" - and (b) whoever adds them to en.json has a
 * single list to copy from.
 */
export const OPTIONAL_TOUR_COPY = {
  hostedNote: HOSTED_MODEL_NOTE,
  reopenHint: REOPEN_HINT,
  completedAgentCta: CUSTOM_AGENT_CTA,
  nextTitle: NEXT_ACTIONS_TITLE,
  replayLabel: REPLAY_TOUR_LABEL,
  replayUnavailable: REPLAY_UNAVAILABLE,
} as const;

export type OptionalTourKey = keyof typeof OPTIONAL_TOUR_COPY;

/** `t` is the `tour`-namespace translator. Translated string if the key
 * exists, the English above if it does not. Never throws: a translator that
 * does is treated as a missing key. */
export function optionalTourCopy(t: (key: string) => string, key: OptionalTourKey): string {
  let translated: string | null = null;
  try {
    translated = t(key);
  } catch {
    translated = null;
  }
  return copyOrFallback(translated, key, OPTIONAL_TOUR_COPY[key]);
}

/**
 * Key-first copy: the translated string when `tour.<key>` exists, otherwise
 * the English fallback. `translated` is whatever t(key) returned; lib/i18n
 * answers a missing key with its dotted path ("tour.hostedNote"), and the
 * identity t() the unit tests inject answers with the bare key - both count as
 * missing, and no real translation is ever equal to its own key.
 *
 * This is NOT the banned `t("x") || "Fallback"` idiom: that one is always
 * truthy (so the fallback is dead code) and hides a key that was SUPPOSED to
 * exist. Here the key is known not to exist yet, and the fallback is the only
 * thing standing between the reader and a raw key path.
 */
export function copyOrFallback(
  translated: string | null | undefined,
  key: string,
  fallback: string,
  namespace = "tour",
): string {
  if (typeof translated !== "string") return fallback;
  const value = translated.trim();
  if (value === "" || value === key || value === `${namespace}.${key}`) return fallback;
  return translated;
}
