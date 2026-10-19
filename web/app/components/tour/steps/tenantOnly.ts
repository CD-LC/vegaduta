import type { TourStep } from "../types";
import type { TourAudience } from "../audience";

/** Every audience EXCEPT platform-admin. Used only by `tenantOnly` below. */
type TenantOnlyAudience = Exclude<TourAudience, "platform-admin">;

/**
 * KEY-COLLISION FENCE. Identity at runtime; a compile error at the tagging
 * site, which is the only place the mistake can be made.
 *
 * TourOverlay builds every rendered string as stem + variant + suffix
 * (`variantKeys`, TourOverlay.tsx:700), and the audience variant token for a
 * platform-admin is literally "Platform" (AUDIENCE_VARIANT, :658). So the
 * "start" step's stem "start" plus "Platform" plus "Title" is
 * `startPlatformTitle` — which is NOT a variant of the start step at all. It is
 * the shipped base copy of the platform-workspace step below, whose own stem is
 * "startPlatform". Tag "start" for platform-admin and a platform-admin is walked
 * through two cards rendering the same four strings, with no compile error, no
 * missing key, and nothing for tour.test.tsx to catch — every key involved
 * exists and every mirror still matches.
 *
 * The clean fix is a rename, and it cannot be made here: en.json carries no
 * un-varied `startTitle` (only the startFree, startPaid and startPlatform
 * sets), so either
 * stem can only be renamed together with eight new keys in a file this session
 * does not own. Until that lands, this function makes the one reachable
 * collision a tsc failure instead of a silent duplicate — adding
 * "platform-admin" to the wrapped step below stops compiling.
 *
 * Wrap any future step whose stem is a prefix of another step's stem the same
 * way, and check the suffix against AUDIENCE_VARIANT / PLAN_VARIANT
 * ("Platform", "Admin", "Member", "Free", "Paid") before adding it.
 */
export function tenantOnly(step: TourStep & { audiences: TenantOnlyAudience[] }): TourStep {
  return step;
}
