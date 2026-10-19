// The seam for showing a learner's real Academy progress on /learn.
//
// STATE OF THIS TODAY: there is no endpoint. The Academy's own
// GET /api/curriculum/tree (vegaduta-webapp/src/server.ts) is scoped to an
// Academy session cookie on a different origin and sends no CORS headers, so
// the console's browser cannot read it, and `core` does not proxy it. Nothing
// in web/app/lib/api.ts fetches Academy data.
//
// Rather than fake a number, /learn renders its whole curriculum map without
// any progress at all, and the progress strip is wired through this one
// loader seam. `resolveLearnProgressLoader()` returns null until someone adds
// the client method, and the strip renders nothing when it is null — there is
// no placeholder, no "0 XP", no skeleton that never resolves.
//
// TO WIRE IT UP (integrator / core owner), two edits and no change to this
// file's consumers:
//   1. core: expose GET /api/learn/progress on the authenticated console API,
//      resolving the caller's Keycloak subject to an Academy learner and
//      returning the LearnProgressSnapshot shape below (or 204 when the
//      Keycloak user has never signed in to the Academy).
//   2. web/app/lib/api.ts: add
//        getLearnProgress(): Promise<LearnProgressSnapshot | null>
//      then change resolveLearnProgressLoader() below to
//        return () => api.getLearnProgress();
//
// Keep the shape additive if core returns more: LearnProgressStrip reads only
// the fields declared here and ignores anything extra.

/** What the console needs to decorate the curriculum map. */
export interface LearnProgressSnapshot {
  /** Cumulative XP as the Academy counts it. */
  xpTotal: number;
  /** Consecutive-day streak. */
  streakDays: number;
  /** Academy content-unit ids this learner has completed, e.g. ["l0-1", …]. */
  completedLessonIds: readonly string[];
  /** Where the learner is now, or null if they have not started. */
  currentLessonId: string | null;
  /** The Academy's certification tier for this learner, e.g. "none". */
  certificationTier: string | null;
}

/**
 * A progress fetch. Resolving to `null` means "this Keycloak user has no
 * Academy learner yet" — a legitimate, non-error outcome that the strip
 * renders as an invitation to start, not as a failure.
 */
export type LearnProgressLoader = () => Promise<LearnProgressSnapshot | null>;

/**
 * Returns the loader, or null when no backend exists for it yet.
 *
 * Deliberately a function and not a constant: when the endpoint lands, the
 * change is a one-line return here, and nothing in the page or the strip has
 * to learn about api.ts.
 */
export function resolveLearnProgressLoader(): LearnProgressLoader | null {
  return null;
}

/** How many of a level's lessons this learner has finished. Level membership
 * is read off the content-unit id prefix ("l3-7" → level 3), which is how
 * seed.ts names every unit; an id that does not match that shape is ignored
 * rather than mis-attributed. */
export function completedInLevel(snapshot: LearnProgressSnapshot, level: number): number {
  const prefix = `l${level}-`;
  return snapshot.completedLessonIds.filter((id) => id.startsWith(prefix)).length;
}
