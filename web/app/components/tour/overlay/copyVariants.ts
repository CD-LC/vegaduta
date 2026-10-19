import { useCallback } from "react";


/* ------------------------------------------------------------------ */
/* Copy variants                                                       */
/* ------------------------------------------------------------------ */

/** The namespace this file's `t` is scoped to. Needed literally because the
 * i18n helper's missing-key sentinel is the full dotted path (see below). */
export const TOUR_NS = "tour";

/**
 * The subset of the provider's resolved access this component reads.
 *
 * Deliberately a structural, all-optional shape instead of importing the
 * provider's type: the tour must degrade to its generic copy, not fail to
 * compile or throw, if the shape it is handed is older or newer than this file
 * expects. Everything below treats "not a string" as "unknown", and unknown
 * always lands on the base key.
 */
export type TourAccessLike = {
  audience?: string | null;
  plan?: string | null;
  experienceMode?: string | null;
};

/**
 * Pulls the resolved audience/plan out of the tour context WITHOUT assuming
 * which of the two plausible shapes the provider exposes - a nested
 * `access` object (G1's `TourAccess`) or the fields hoisted onto the context
 * itself. Anything else resolves to nulls, which is the "render the generic
 * copy" path.
 *
 * Note what this does NOT do: it does not fetch, and it does not call
 * `useAuth()` or `useAppChrome()` itself. Access is resolved exactly once, in
 * the provider, from the tenantSettings AppChromeProvider has already fetched.
 * `useAuth()` in particular would be a real bug here - this overlay is mounted
 * at the root layout, above the authenticated/public split, and that hook
 * hard-throws on a public route (same reason TourProvider avoids it).
 */
export function readTourAccess(tour: unknown): TourAccessLike {
  const ctx = tour as { access?: unknown; audience?: unknown; plan?: unknown } | null | undefined;
  const nested = ctx?.access;
  const source = (nested && typeof nested === "object" ? nested : ctx) as TourAccessLike | undefined;
  return {
    audience: typeof source?.audience === "string" ? source.audience : null,
    plan: typeof source?.plan === "string" ? source.plan : null,
    experienceMode: typeof source?.experienceMode === "string" ? source.experienceMode : null,
  };
}

/**
 * The copy variant TOKENS en.json actually uses, mapped from the enum values
 * the provider resolves. Deliberately explicit lookup tables rather than a
 * "TitleCase whatever you were handed" transform:
 *
 *  - the tokens are NOT the enum values. There is no `startPremiumTitle` or
 *    `startUnlimitedTitle` - the copy splits on the only distinction the
 *    wording cares about, "is this workspace paying or not", so PREMIUM and
 *    UNLIMITED share one variant. A tenant inside the complimentary grant is
 *    UNLIMITED today and FREE the day it lapses, and each side gets the line
 *    that is true of it. That is the whole point of this round: the old single
 *    hardcoded pricing sentence on step 0 was guaranteed to go stale for
 *    somebody, the way the landing page's ChannelWall lanes did.
 *  - an unrecognised value contributes NO key at all, so a provider that grows
 *    a new plan or audience degrades to generic copy instead of asking for a
 *    key nobody wrote and rendering a raw dotted path on screen.
 */
export const AUDIENCE_VARIANT: Record<string, string> = {
  "platform-admin": "Platform",
  "tenant-admin": "Admin",
  member: "Member",
};

export const PLAN_VARIANT: Record<string, string> = {
  FREE: "Free",
  PREMIUM: "Paid",
  UNLIMITED: "Paid",
};

/** `"platform-admin"` -> `"Platform"`; anything unknown -> null (generic copy). */
export function audienceVariantToken(audience: string | null | undefined): string | null {
  if (!audience) return null;
  return AUDIENCE_VARIANT[audience.trim().toLowerCase()] ?? null;
}

/** `"UNLIMITED"` -> `"Paid"`; anything unknown -> null (generic copy). */
export function planVariantToken(plan: string | null | undefined): string | null {
  if (!plan) return null;
  return PLAN_VARIANT[plan.trim().toUpperCase()] ?? null;
}

/**
 * Candidate keys for one string, most specific first, always ending on the
 * un-varied key.
 *
 * Both shapes en.json uses are generated, in this order per variant:
 *   1. INFIX  - `start` + `Free` + `Title` -> `startFreeTitle`. This is the
 *      convention for the step copy and the completion copy
 *      (`completedMemberBody`, `startPlatformPoint1`).
 *   2. SUFFIX - `welcomeBody` + `Platform` -> `welcomeBodyPlatform`, which is
 *      how the welcome hero's variant is spelled.
 * Generating both, rather than picking one, is what lets this file survive a
 * copy edit that respells a key in the other shape - the miss costs one object
 * lookup and the reader never sees it.
 *
 * Variants are tried in the order given, so the caller's first entry wins:
 * audience before plan, because who you are is the narrower bucket and a
 * platform-admin's copy must not be overridden by their (possibly absent)
 * tenant's plan.
 */
export function variantKeys(stem: string, suffix: string, variants: Array<string | null | undefined>): string[] {
  const keys: string[] = [];
  const push = (key: string) => {
    if (!keys.includes(key)) keys.push(key);
  };
  for (const variant of variants) {
    if (!variant) continue;
    push(`${stem}${variant}${suffix}`);
    push(`${stem}${suffix}${variant}`);
  }
  push(`${stem}${suffix}`);
  return keys;
}

export type Translate = (key: string, values?: Record<string, string | number>) => string;

/**
 * `tv("start", "Title", ["Platform", "Free"])` -> the first of
 * `startPlatformTitle` / `startTitlePlatform` / `startFreeTitle` /
 * `startTitleFree` / `startTitle` that actually exists in the messages file.
 *
 * This is the ONLY thing in here that knows a variant might be missing, and it
 * is deliberately not the `t("x") || "Fallback"` idiom that is banned in this
 * codebase (that one hard-codes an untranslated English string in the
 * component, so it renders English to a Japanese reader and hides the missing
 * key forever). The fallback here is another KEY - one every locale already
 * carries - so an unwritten variant renders real, translated copy rather than a
 * raw `tour.startPlatformTitle` path on screen. Callers that have no un-varied
 * key to fall back on (the completion modal: `completedTitle` was retired when
 * the per-audience wording landed) pass a DEFAULT VARIANT as their last entry,
 * so the chain still ends on a string that exists.
 *
 * Detecting "missing" without reading the messages file: i18n.tsx returns the
 * full dotted path (`tour.startPlatformTitle`) when a key is absent from both
 * the active locale AND English. A bare echo of the key counts as missing too,
 * because the identity `t` the unit tests inject returns exactly that - so the
 * fallback chain is exercised under test the same way it runs in the browser.
 * No real translation is ever equal to its own key.
 */
export function useVariantCopy(t: Translate) {
  return useCallback(
    (
      stem: string,
      suffix: string,
      variants: Array<string | null | undefined>,
      values?: Record<string, string | number>
    ): string => {
      const keys = variantKeys(stem, suffix, variants);
      for (const key of keys) {
        const value = t(key, values);
        if (value === key || value === `${TOUR_NS}.${key}`) continue;
        return value;
      }
      // Every candidate missing, the un-varied key included: hand back whatever
      // `t` makes of that one, which is exactly what every other string in this
      // file would render for a missing key. Nothing invented locally.
      return t(`${stem}${suffix}`, values);
    },
    [t]
  );
}

/* ------------------------------------------------------------------ */
/* Overlay                                                             */
/* ------------------------------------------------------------------ */

