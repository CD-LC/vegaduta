import type { Metadata } from "next";
import type { ReactNode } from "react";

// Server-component metadata shell for /docs (i18n/hreflang audit SEO pass,
// 2026-07-30). page.tsx is "use client" and cannot export metadata itself;
// this pass-through layout carries the route's SEO tags and renders
// children unchanged — same pattern as /try/layout.tsx, /help/layout.tsx,
// etc. This is the public (logged-out) twin of the in-console /help/docs
// route, which stays disallowed/noindex — see help/docs/layout.tsx.
export const metadata: Metadata = {
  title: "VegaDūta Help & Docs",
  description:
    "Everything the platform can do and how to use it — from your first agent to multi-step workflows, knowledge grounding, tools, channels, and plans.",
  alternates: { canonical: "/docs" },
  openGraph: {
    title: "VegaDūta Help & Docs",
    description:
      "Everything the platform can do and how to use it — from your first agent to multi-step workflows, knowledge grounding, tools, channels, and plans.",
    images: ["/og-image.png"],
  },
};

export default function DocsLayout({ children }: { children: ReactNode }) {
  return children;
}
