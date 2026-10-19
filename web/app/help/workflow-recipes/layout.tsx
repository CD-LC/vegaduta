import type { Metadata } from "next";
import type { ReactNode } from "react";
import { TOTAL_CATEGORIES } from "./recipes-index";

// Server-component metadata shell for /help/workflow-recipes (SEO
// foundations pass, 2026-07-25). page.tsx (added in the 2026-07-30
// structured-data audit) now exports its own, more specific metadata for
// this exact route, which Next.js's merge rules let win over this layout's
// for matching fields — so this shell is effectively a fallback today. Kept
// in sync anyway rather than left stale: the industry count below used to be
// a hardcoded "37+" (content-onpage SEO audit, 2026-07-30 — the corpus grew
// to 89 industries and the description was never updated), now the same
// TOTAL_CATEGORIES constant page.tsx and WorkflowRecipesClient already read.
export const metadata: Metadata = {
  title: "WhatsApp AI Agent Workflow Templates by Industry | VegaDūta",
  description: `Ready-to-use WhatsApp AI agent workflow recipes for AI business automation across ${TOTAL_CATEGORIES}+ industries — retail, healthcare, logistics, finance and more, with prompts, tools, and guardrails included.`,
  alternates: { canonical: "/help/workflow-recipes" },
  openGraph: {
    title: "WhatsApp AI Agent Workflow Templates by Industry | VegaDūta",
    description: `Ready-to-use WhatsApp AI agent workflow recipes for ${TOTAL_CATEGORIES}+ industries, with prompts, tools, and guardrails included.`,
    images: ["/og-image.png"],
  },
};

export default function WorkflowRecipesLayout({
  children,
}: {
  children: ReactNode;
}) {
  return children;
}
