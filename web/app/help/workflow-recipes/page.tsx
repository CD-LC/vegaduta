import type { Metadata } from "next";
import WorkflowRecipesClient from "./WorkflowRecipesClient";
import { INDUSTRY_INDEX, TOTAL_CATEGORIES, TOTAL_RECIPES } from "./recipes-index";
import { SITE_URL } from "../../lib/site-url";
import JsonLd from "../../components/JsonLd";

// ---------------------------------------------------------------------------
// Server shell for the public /help/workflow-recipes index (structured-data
// audit, 2026-07-30). The page used to be entirely "use client"
// (WorkflowRecipesClient now) with no `metadata` export and no JSON-LD — the
// hub linking all 89 already-optimized per-industry pages
// (help/workflow-recipes/[industry]/page.tsx, each of which already carries
// its own JSON-LD). Same split docs/page.tsx and billing/page.tsx use: a
// server component supplies metadata + JSON-LD, the interactive
// search/filter/lazy-load chrome stays client-side underneath.
//
// ItemList below reads INDUSTRY_INDEX — metadata only (id/name), the exact
// same lightweight module the client component already imports for search,
// so this costs no extra data fetch or bundle weight.
// ---------------------------------------------------------------------------

const BASE = SITE_URL;
const PATH = "/help/workflow-recipes";

const TITLE = `Industry Workflow Recipes — ${TOTAL_RECIPES}+ Buildable WhatsApp AI Agent Automations`;
const DESCRIPTION = `Real, buildable WhatsApp AI agent workflow recipes for AI business automation across ${TOTAL_CATEGORIES}+ industries — each one maps 1:1 to VegaDūta's workflow nodes, channels and connectors, with guardrails, budgets and human approvals built in.`;

export const metadata: Metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: PATH },
  openGraph: {
    title: TITLE,
    description: DESCRIPTION,
    url: PATH,
    type: "website",
  },
};

const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "Home", item: BASE },
        { "@type": "ListItem", position: 2, name: "Workflow recipes", item: `${BASE}${PATH}` },
      ],
    },
    {
      "@type": "ItemList",
      itemListElement: INDUSTRY_INDEX.map((ind, i) => ({
        "@type": "ListItem",
        position: i + 1,
        name: ind.name,
        url: `${BASE}${PATH}/${ind.id}`,
      })),
    },
  ],
};

export default function WorkflowRecipesPage() {
  return (
    <>
      <JsonLd data={jsonLd} />
      <WorkflowRecipesClient />
    </>
  );
}
