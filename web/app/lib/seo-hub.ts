// ---------------------------------------------------------------------------
// SEO/AEO hub — shared content types (2026-08-14).
//
// One type for every page in the four public documentation hubs
// (/integrations, /ai, /platform, /tutorials). Each hub keeps its copy in a
// sibling *-data.ts module (the compare-data.ts convention: claims live in
// data, not JSX) and renders through components/marketing/HubArticle.tsx.
//
// The shape is deliberately opinionated for answer-engine optimization:
// - intro[0] must answer the page's core question in plain declarative
//   sentences (AI search engines quote the first paragraph);
// - faqs back a FAQPage JSON-LD block AND render as visible content — never
//   markup-only FAQs (Google treats invisible FAQ markup as spam);
// - steps (tutorials) back a HowTo JSON-LD block the same way.
//
// No-fiction rule applies to every string in these modules: claims must map
// to code that exists on main. See each data module's header for its
// grounding notes.
// ---------------------------------------------------------------------------

export type HubLink = { href: string; label: string };

export type HubFaq = { q: string; a: string };

export type HubSection = {
  heading: string;
  body: string;
  bullets?: string[];
};

export type HubStep = { name: string; text: string };

/** An outbound source backing a claim — a spec, a paper, a vendor's own docs. */
export type HubCitation = { label: string; href: string };

// Dates (GEO pass, 2026-09-19). Until now no hub page carried a date anywhere —
// not visibly, not in JSON-LD — so neither a reader nor a retrieval system
// could tell a current page from a stale one, and freshness is one of the few
// signals an answer engine can actually check.
//
// These are the real dates, read from `git log` on each hub's *-data.ts, not
// the day this field was added. `updated` means "the content of this hub last
// changed": BUMP IT IN THE SAME COMMIT THAT EDITS THE DATA MODULE, and never
// bump it without a content change — a date that moves on its own is a
// fabricated freshness signal, which is exactly the kind of thing the
// no-fiction rule exists to stop. A page edited on its own schedule can set
// HubPage.updated to override its hub's date.
export const HUB_DATES: Record<string, { published: string; updated: string }> = {
  "/ai": { published: "2026-08-15", updated: "2026-10-03" },
  "/platform": { published: "2026-08-15", updated: "2026-10-03" },
  "/integrations": { published: "2026-08-15", updated: "2026-10-03" },
  "/tutorials": { published: "2026-08-15", updated: "2026-10-03" },
};

export type HubPage = {
  /** URL segment under the hub base path. */
  slug: string;
  /** Short display name — index cards, breadcrumbs, related-link labels. */
  name: string;
  /** <title> — keyword-led, ≤ 60 chars where possible. */
  metaTitle: string;
  /** Meta description — ~150–160 chars, plain answer to the search intent. */
  metaDescription: string;
  h1: string;
  /** Eyebrow line above the h1, e.g. "Integrations · Messaging channels". */
  kicker: string;
  /** One-sentence blurb for the hub index card. */
  cardBlurb: string;
  /** 1–2 paragraphs. The first must answer the page's question directly. */
  intro: string[];
  sections: HubSection[];
  /** Tutorials only — renders numbered steps + HowTo JSON-LD. */
  steps?: HubStep[];
  faqs: HubFaq[];
  /** Cross-links (internal hub pages and funnel pages). */
  related: HubLink[];
  /**
   * 2–4 standalone sentences rendered as an "In short" box under the h1.
   * Each must make sense lifted out of the page with no surrounding context —
   * that is how an answer engine quotes it. Same no-fiction rule as the rest.
   */
  keyTakeaways?: string[];
  /**
   * Primary sources for the page's claims about things OUTSIDE this product
   * (a protocol spec, a paper, a vendor's pricing page). Rendered as a visible
   * "Sources" list and as `citation` in the JSON-LD. Cited, verifiable
   * statements are the single largest measured lift in the GEO literature
   * (Aggarwal et al., KDD 2024) — and an uncited one is just a claim.
   */
  citations?: HubCitation[];
  /** ISO date overriding the hub-level HUB_DATES.updated for this page only. */
  updated?: string;
};

export function findHubPage(pages: HubPage[], slug: string): HubPage | undefined {
  return pages.find((p) => p.slug === slug);
}
