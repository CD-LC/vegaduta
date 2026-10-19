import type { Metadata } from "next";
import DocsPageClient from "./DocsPageClient";
import { DOCS_TITLE, DOCS_SUBTITLE, DOC_TOPICS } from "../help/docs-content";
import { SITE_URL } from "../lib/site-url";
import JsonLd from "../components/JsonLd";

// ---------------------------------------------------------------------------
// Server shell for the public /docs page (structured-data audit, 2026-07-30).
// The page used to be entirely "use client" (DocsPageClient.tsx now) with no
// `metadata` export and no JSON-LD at all — one of 34/55 pages missing both.
// Same split whatsapp-cost-calculator/page.tsx already uses: a server
// component supplies metadata + JSON-LD, the interactive chrome (auth.login
// onClick, DocsView) stays client-side underneath it.
//
// FAQPage JSON-LD is sourced from the real "Troubleshooting & FAQ" topic in
// docs-content.tsx (id: "troubleshooting") — the same Q&A pairs rendered
// on-page via DocsView, not invented copy.
// ---------------------------------------------------------------------------

const BASE = SITE_URL;
const PATH = "/docs";

export const metadata: Metadata = {
  title: DOCS_TITLE,
  description: DOCS_SUBTITLE,
  alternates: { canonical: PATH },
  openGraph: {
    title: DOCS_TITLE,
    description: DOCS_SUBTITLE,
    url: PATH,
    type: "website",
  },
};

const troubleshooting = DOC_TOPICS.find((t) => t.id === "troubleshooting");
const faqEntries = (troubleshooting?.sections ?? []).filter(
  (s) => !!s.heading && !!s.paras && s.paras.length > 0
);

const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "Home", item: BASE },
        { "@type": "ListItem", position: 2, name: "Docs", item: `${BASE}${PATH}` },
      ],
    },
    ...(faqEntries.length > 0
      ? [
          {
            "@type": "FAQPage",
            mainEntity: faqEntries.map((s) => ({
              "@type": "Question",
              name: s.heading!,
              acceptedAnswer: { "@type": "Answer", text: s.paras!.join(" ") },
            })),
          },
        ]
      : []),
  ],
};

export default function PublicDocsPage() {
  return (
    <>
      <JsonLd data={jsonLd} />
      <DocsPageClient />
    </>
  );
}
