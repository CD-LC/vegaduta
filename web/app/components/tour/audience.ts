// Who is this tour FOR, and which of its steps is this particular person
// actually allowed to see?
//
// Until this module existed the post-login tour was one fixed nine-step walk
// shown to everybody: the same nine steps for a brand-new B2C member on a Free
// workspace, for the tenant-admin who owns the place, and for a cross-tenant
// platform-admin who may not have a tenant at all. Three concrete failures came
// out of that:
//
//   1. The channels step's "Show me" sent a non-admin to /admin/channels, which
//      bounces them straight back out. The tour was pointing at a door the
//      person holding it cannot open.
//   2. Steps pitched capabilities the tenant's plan does not carry. A tour that
//      sells you a feature you cannot reach is worse than silence - it is the
//      same class of bug as the ChannelWall lane1/lane2 contradiction on the
//      landing page, where shipped copy asserted an entitlement the backend did
//      not grant.
//   3. A workspace in SIMPLE experience mode - which exists precisely to hide
//      the advanced surface - was still walked through workflows and tools.
//
// This module is the whole decision, and it is deliberately PURE: no React, no
// document, no fetch. Roles arrive from useAuth() (AuthProvider.tsx:608-614),
// entitlements arrive from the tenantSettings that AppChromeProvider ALREADY
// fetches once per session (AppChromeProvider.tsx:77 - do not add a second
// getTenantSettings() call on every login), and DOM reachability arrives as a
// callback the caller supplies. That keeps the interesting logic unit-testable
// without a renderer, which is the only reason the five rules below can be
// asserted directly rather than inferred from a screenshot.
//
// Field provenance, verified rather than remembered:
//   TenantSettingsResponse.java:61-81 - accountType, experienceMode, plan,
//   usePlatformLlm, byokAllowed, whatsappAllowed, whiteLabel, maxKbCollections,
//   liveVisionAllowed, brandDisplayName, brandLogoUrl.
//   TenantPlan.java     = FREE | PREMIUM | UNLIMITED
//   AccountType.java    = B2B | B2C | FAMILY
//   ExperienceMode.java = SIMPLE | ADVANCED

/** The three tours. See resolveAudience() for why these are the cut lines. */
export type TourAudience = "platform-admin" | "tenant-admin" | "member";

/** Iterable form of TourAudience, so a test or a step table can loop the set
 *  without re-typing the union and silently missing a future fourth value. */
export const TOUR_AUDIENCES: readonly TourAudience[] = [
  "platform-admin",
  "tenant-admin",
  "member",
] as const;

/**
 * Everything the tour is allowed to know about the viewer. Deliberately a flat,
 * serialisable snapshot rather than a live handle on tenantSettings: a step's
 * `requires` predicate must be a pure function of this object so it can be
 * tested without a provider tree, and so a step can never reach past this
 * surface into some richer object and start depending on a field nobody
 * declared here.
 *
 * `plan` / `accountType` / `experienceMode` are `string | null`, NOT the Java
 * enum unions, on purpose. The backend can add a plan tier tomorrow; a union
 * type here would force this file to be edited before that tier could render at
 * all, and a narrowing cast would quietly map the unknown tier onto a known one
 * - i.e. show FREE copy to someone who paid. `null` means "not known", which is
 * a real and common state (see resolveAccess).
 */
export type TourAccess = {
  audience: TourAudience;
  /** TenantPlan, upper-cased, or null when unknown. Never coerced to a known
   *  tier - step copy MUST handle null (say something true without a plan name)
   *  rather than assuming FREE. */
  plan: string | null;
  /** AccountType (B2B | B2C | FAMILY), upper-cased, or null when unknown. */
  accountType: string | null;
  /** ExperienceMode (SIMPLE | ADVANCED), upper-cased, or null when unknown.
   *  Anything that is not exactly SIMPLE is treated as the full experience -
   *  see EXPERIENCE_MODE_SIMPLE. */
  experienceMode: string | null;
  whatsappAllowed: boolean;
  byokAllowed: boolean;
  liveVisionAllowed: boolean;
};

/**
 * The subset of a tour step this module reads. TourProvider's TourStep
 * (TourProvider.tsx:51) is a superset of it - titleKey, the copy mirrors,
 * placement, illustration and href are none of this module's business.
 *
 * `audiences` is REQUIRED in the type so that a step added without one is a
 * compile error at the step table, not a silent "shown to everyone" at runtime.
 * At runtime a missing/empty/non-array value is nevertheless treated as "every
 * audience": a build that somehow ships a malformed step should degrade to
 * showing it, never to an empty tour (see the degrade-direction note on
 * resolveAudience).
 */
export type TourStepLike = {
  id: string;
  /** data-tour="<target>" anchor, or null for a centered hero step with no
   *  anchor at all. A null-target step is immune to rule 4 - there is no
   *  element to fail to find. */
  target: string | null;
  /** Which tours this step belongs to. Rule 1. */
  audiences: readonly TourAudience[];
  /** Rule 2. Return false when this tenant's plan/entitlements do not carry
   *  the thing the step describes. Must be pure and must not throw; a thrown
   *  predicate is treated as `false` (see passesEntitlement). */
  requires?: (access: TourAccess) => boolean;
  /** Rule 3. `true` marks a step that survives SIMPLE experience mode. Absent
   *  or false means "advanced surface - hide it in SIMPLE". */
  simple?: boolean;
};

/**
 * The step id that rule 5 protects as the tour's exit. If a tour has no step
 * with this id, the LAST step of that audience's set is protected instead - the
 * point of the rule is that a person is never stranded mid-walk with no pointer
 * to where help lives, and for a bespoke audience set "the last step" is the
 * closest honest equivalent.
 */
export const HELP_STEP_ID = "help";

/**
 * Rule 5's floor. Three is not arbitrary: a two-step tour is an intro and an
 * outro with no middle - it costs a modal, an overlay and a keypress to say
 * nothing. Below this we would rather run no tour at all, which is what an
 * empty return means to the caller.
 *
 * Deliberately NOT applied to an audience whose DESIGNED set is already this
 * short or shorter (the platform-admin walk is 3-4 steps by design and may
 * honestly be one). The floor guards against attrition - rules 2-4 whittling a
 * nine-step walk down to a stub - not against a deliberately short tour.
 */
export const MIN_TOUR_STEPS = 3;

/**
 * The one ExperienceMode value that narrows the tour. Compared exactly (after
 * trim + upper-case) so that an unrecognised mode string from a newer backend
 * falls through to the FULL tour rather than to the reduced one: hiding the
 * product from someone because we did not recognise a string is the worse of
 * the two failures.
 *
 * Note there is deliberately NO numeric SIMPLE-mode step cap in this module.
 * SIMPLE selection is driven by the per-step `simple: true` flag, which is an
 * editorial decision made once at the step table where the copy lives, rather
 * than a "first N steps" truncation that would silently change which steps get
 * shown every time someone reorders that table.
 */
export const EXPERIENCE_MODE_SIMPLE = "SIMPLE";

/**
 * Which tour this person gets.
 *
 * Precedence is platform-admin > tenant-admin > member, and the two admin flags
 * are genuinely independent: AuthProvider.tsx:608-614 computes isAdmin from
 * ADMIN_ROLE and isPlatformAdmin from PLATFORM_ADMIN_ROLE, and the comment
 * there is explicit that holding one does not confer the other. A person CAN
 * hold both. When they do they get the platform-admin tour: the platform-admin
 * role is the rarer and more specific signal, and someone who operates the
 * platform does not need a nine-step "here is how you build your first agent"
 * walkthrough. (If that judgement ever proves wrong it is one line here, not a
 * redesign - swap the order of the two checks.)
 *
 * Degrade direction, which differs by what is unknown and is worth stating once
 * because the rest of this file follows it:
 *   - unknown IDENTITY or unknown ENTITLEMENT -> assume the NARROWER thing.
 *     Never show admin-shaped content to someone whose roles we could not read;
 *     never pitch a capability we could not confirm.
 *   - unknown PRESENTATION PREFERENCE (experienceMode) -> assume the FULLER
 *     thing. A preference we failed to read is not a reason to hide the
 *     product.
 */
export function resolveAudience(opts: { isAdmin: boolean; isPlatformAdmin: boolean }): TourAudience {
  if (!opts || typeof opts !== "object") return "member";
  if (opts.isPlatformAdmin === true) return "platform-admin";
  if (opts.isAdmin === true) return "tenant-admin";
  return "member";
}

/**
 * Trim + upper-case a string-ish enum value, or null. Never maps an unknown
 * value onto a known one - see the TourAccess doc for why that matters.
 */
function normalizeEnumValue(value: any): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed.toUpperCase();
}

/**
 * Flatten roles + tenantSettings into the snapshot the step table reads.
 *
 * `tenantSettings` is whatever AppChromeProvider currently holds. It is `null`
 * in two quite different situations and this function cannot tell them apart:
 *
 *   (a) still loading - AppChromeProvider.tsx:74-79 initialises the state to
 *       null and fills it after a best-effort fetch that never blocks render,
 *       and also resets it to null if that fetch fails; and
 *   (b) there is no tenant - AuthProvider.tsx:620-622 documents that a
 *       platform-admin is tenant-less BY DESIGN and is never routed through the
 *       OnboardingGate.
 *
 * In BOTH cases every boolean entitlement comes back `false` and every enum
 * comes back `null`. That is the conservative reading, and it is the right one:
 * the alternative is a tour that promises WhatsApp or BYOK or Live Vision to a
 * workspace we have not actually confirmed carries them, which is exactly the
 * failure this round exists to remove. WhatsApp in particular is free on every
 * plan today (V225__free_plan_whatsapp.sql) - that is still no reason to
 * hardcode `true` here, because this snapshot's job is to report what the
 * backend said, not what we believe the backend's policy to be.
 *
 * The consequence for the caller: a member or tenant-admin tour selected while
 * tenantSettings is still null will be missing its entitlement-gated steps. Do
 * not commit to a step list until tenantSettings has resolved for those two
 * audiences (the platform-admin set does not read entitlements, so it is safe
 * to select immediately). Rule 5's floor means the worst case is a short tour,
 * never a broken one - but short-because-still-loading is a degradation worth
 * avoiding rather than tolerating.
 */
export function resolveAccess(opts: {
  isAdmin: boolean;
  isPlatformAdmin: boolean;
  tenantSettings: any | null;
}): TourAccess {
  const audience = resolveAudience(opts);
  const raw =
    opts && opts.tenantSettings && typeof opts.tenantSettings === "object"
      ? opts.tenantSettings
      : null;
  return {
    audience,
    plan: normalizeEnumValue(raw?.plan),
    accountType: normalizeEnumValue(raw?.accountType),
    experienceMode: normalizeEnumValue(raw?.experienceMode),
    // Strict `=== true`, not truthiness: a backend that ever serialises these
    // as the strings "false"/"0" must not read as an entitlement.
    whatsappAllowed: raw?.whatsappAllowed === true,
    byokAllowed: raw?.byokAllowed === true,
    liveVisionAllowed: raw?.liveVisionAllowed === true,
  };
}

/** Rule 1. An undeclared/empty/malformed `audiences` means "every audience" -
 *  degrade to showing the step, never to an empty tour. */
function passesAudience(step: TourStepLike, audience: TourAudience): boolean {
  if (!step || typeof step !== "object") return false;
  const declared: any = (step as any).audiences;
  if (!Array.isArray(declared) || declared.length === 0) return true;
  return declared.some((value: any) => value === audience);
}

/** Rule 2. A predicate that throws is a bug in the step table, not an
 *  entitlement signal - but the safe reading of "we could not evaluate whether
 *  this tenant has the feature" is still "do not pitch the feature", so a throw
 *  drops the step. Rule 5 stops that from emptying the tour. */
function passesEntitlement(step: TourStepLike, access: TourAccess): boolean {
  const requires: any = (step as any).requires;
  if (typeof requires !== "function") return true;
  try {
    return Boolean(requires(access));
  } catch {
    return false;
  }
}

/** Rule 3. Only exactly-SIMPLE narrows; every other value (ADVANCED, null, or a
 *  mode this build has never heard of) keeps the full set. */
function passesExperienceMode(step: TourStepLike, access: TourAccess): boolean {
  if (access.experienceMode !== EXPERIENCE_MODE_SIMPLE) return true;
  return (step as any)?.simple === true;
}

/** Normalised anchor: null, or a non-empty trimmed target string. */
function anchorOf(step: TourStepLike): string | null {
  const target: any = (step as any)?.target;
  if (typeof target !== "string") return null;
  const trimmed = target.trim();
  return trimmed === "" ? null : trimmed;
}

/**
 * Rule 4 - the reachability guarantee, and most of the reason this round
 * exists. A step whose anchor is not in the DOM is a step whose spotlight lands
 * on nothing and whose "Show me" sends the person somewhere they will be
 * bounced out of. That is how the channels step came to walk non-admins into
 * /admin/channels.
 *
 * No callback at all means the caller has no reachability information to give,
 * so the rule simply does not run (fullest tour). A callback that THROWS for a
 * specific target means that target's state is unknowable, which is not the
 * same thing - that step is dropped.
 */
function passesReachability(
  step: TourStepLike,
  isTargetPresent: (target: string | null) => boolean,
): boolean {
  const anchor = anchorOf(step);
  if (anchor === null) return true;
  if (typeof isTargetPresent !== "function") return true;
  try {
    return Boolean(isTargetPresent(anchor));
  } catch {
    return false;
  }
}

/**
 * Apply the five rules, in order, and return the steps this person should
 * actually be walked through. The order of the result always matches the order
 * of the input - selection never reshuffles a tour.
 *
 *   1. audience       - keep steps whose `audiences` includes access.audience.
 *   2. entitlement    - drop steps whose `requires(access)` is false.
 *   3. experienceMode - in SIMPLE, keep only steps marked `simple: true`.
 *   4. reachability   - drop steps whose anchor is not present.
 *   5. floor          - protect the opening step and the help step, and refuse
 *                       to emit a stub.
 *
 * Rule 5 in detail, because it is the one with judgement in it:
 *
 *   - The two protected steps are computed AFTER rule 1, from this audience's
 *     own set - "the first step" means the first step of the tour this person
 *     is getting, not steps[0] of some other audience's table. Otherwise the
 *     platform-admin tour would be force-fed the tenant "start" step.
 *   - Protection exempts a step from rules 2 and 3 (an intro and an exit are
 *     not feature pitches, so no entitlement or mode should silence them). It
 *     does NOT exempt a step from rule 4. Force-keeping a step whose anchor is
 *     missing would re-create the exact bug rule 4 exists to kill, and "always
 *     keep the help step" cannot reasonably mean "always keep a broken step".
 *     In practice the opening step has target null and is therefore immune
 *     anyway, and help-link is rendered for every member (AppShell.tsx:404's
 *     NAV_TOUR_TARGETS entry is not role-gated), so this exemption boundary is
 *     theoretical today and defensive tomorrow.
 *   - Top-up, when the survivors fall below the floor, draws ONLY from steps
 *     dropped by rule 3. A rule-3 drop is a presentation preference and can be
 *     walked back to avoid a stub; a rule-2 drop is a claim that would be false
 *     and a rule-4 drop is a spotlight that would be broken. Neither of those
 *     is ever reclaimed to pad a count.
 *   - An audience whose designed set is already <= MIN_TOUR_STEPS is returned
 *     as-is. A three-step platform-admin walk, or the single honest step that
 *     says the platform console lives in web-admin rather than here, is
 *     intentional, and the floor must not delete it for being short.
 *   - If a LARGER designed set still cannot reach the floor, the result is the
 *     empty array. That is the caller's signal to run no tour at all, and it is
 *     the deliberate reading of "a one-step tour is worse than no tour".
 */
export function selectSteps<T extends TourStepLike>(
  steps: T[],
  access: TourAccess,
  isTargetPresent: (target: string | null) => boolean,
): T[] {
  if (!Array.isArray(steps) || steps.length === 0) return [];

  // A missing/garbage access object degrades to the narrowest identity with no
  // confirmed entitlements, rather than throwing inside a render path.
  const safeAccess: TourAccess =
    access && typeof access === "object" && typeof (access as any).audience === "string"
      ? access
      : resolveAccess({ isAdmin: false, isPlatformAdmin: false, tenantSettings: null });

  // RULE 1 - audience. Everything below operates on this list, so that "first
  // step", "help step" and the floor all mean this audience's tour.
  const designed = steps.filter((step) => passesAudience(step, safeAccess.audience));
  if (designed.length === 0) return [];

  // Rule 5's protected pair, identified by object identity so a duplicated id
  // in the table cannot protect the wrong element.
  const protectedSteps = new Set<T>();
  protectedSteps.add(designed[0]);
  const helpStep =
    designed.find((step) => (step as any)?.id === HELP_STEP_ID) ?? designed[designed.length - 1];
  if (helpStep) protectedSteps.add(helpStep);

  const kept = new Set<T>();
  // Steps rule 3 removed, in input order - the only pool the floor may reclaim
  // from. Rule-2 and rule-4 drops are intentionally not recorded: nothing in
  // this module is allowed to bring them back.
  const experienceModeDrops: T[] = [];

  for (const step of designed) {
    const isProtected = protectedSteps.has(step);
    if (!isProtected && !passesEntitlement(step, safeAccess)) continue; // RULE 2
    if (!isProtected && !passesExperienceMode(step, safeAccess)) {      // RULE 3
      experienceModeDrops.push(step);
      continue;
    }
    if (!passesReachability(step, isTargetPresent)) continue;           // RULE 4
    kept.add(step);
  }

  // RULE 5 - floor.
  if (designed.length <= MIN_TOUR_STEPS) {
    // Deliberately short tour. There is no attrition here to defend against.
    return designed.filter((step) => kept.has(step));
  }

  if (kept.size < MIN_TOUR_STEPS) {
    for (const step of experienceModeDrops) {
      if (kept.size >= MIN_TOUR_STEPS) break;
      if (!passesReachability(step, isTargetPresent)) continue;
      kept.add(step);
    }
  }

  if (kept.size < MIN_TOUR_STEPS) return [];

  // Set membership plus a filter over `designed`, rather than push order, so
  // reclaimed steps land back in their designed positions instead of being
  // appended after the help step.
  return designed.filter((step) => kept.has(step));
}
