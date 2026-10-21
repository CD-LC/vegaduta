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
  /** True when the Academy teaches this surface. Rendered as a "Learn this
   * in the Academy" link to the configured Academy origin (academyHref()) —
   * plain link, no lesson ids: the Academy is a private deployable and the
   * OSS console never names its internal content ids. Distinct from `next`:
   * next stays inside the console, learn leaves for the Academy (new tab). */
  learnInAcademy?: boolean;
  /** Only shown to workspace admins (the page itself is admin-only). */
  adminOnly?: boolean;
  /** Only meaningful when on-device AI is built into this deployment. */
  edgeOnly?: boolean;
};

export type PageGuideEntry = { match: RegExp; guide: PageGuide };
