// Shapes of the per-page guides (see pageGuides.ts).

export type PageGuideLink = { label: string; href: string };

export type PageGuide = {
  /** Stable id; also the i18n key segment and the dismissal key. */
  id: string;
  /** Short heading, e.g. "Create your first agent". */
  title: string;
  /** One sentence: what this page is for. */
  purpose: string;
  /** The least a person must do here, in order. At most four. */
  steps: string[];
  /** Optional one-liner that removes a common worry. */
  reassurance?: string;
  /** Where to go once the steps are done. */
  next?: PageGuideLink;
  /** Academy lesson id (e.g. "l3-3") that teaches this surface. Rendered as a
   * "Learn this in the Academy" link resolved through academyLessonUrl() —
   * never a hardcoded hostname, and the helper stamps console attribution.
   * Distinct from `next`: next stays inside the console, learn leaves for the
   * Academy (new tab). Only ids that exist in the Academy's seed are valid. */
  learnLessonId?: string;
  /** Only shown to workspace admins (the page itself is admin-only). */
  adminOnly?: boolean;
  /** Only meaningful when on-device AI is built into this deployment. */
  edgeOnly?: boolean;
};

export type PageGuideEntry = { match: RegExp; guide: PageGuide };
