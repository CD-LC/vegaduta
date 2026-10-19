import type { Metadata } from "next";
import type { ReactNode } from "react";

// Server-component metadata shell for the /templates section. The public
// gallery (page.tsx) and detail ([id]/page.tsx) are server-rendered so they
// are crawlable; /templates/mine is an authed client screen that inherits
// these defaults harmlessly (authed routes aren't indexed). Per-page metadata
// (the gallery's own, and each template's generateMetadata) overrides these.
export const metadata: Metadata = {
  title: "Community Templates — Agents & Workflows | VegaDūta",
  description:
    "Browse agent and workflow templates shared by the VegaDūta community. Inspect each one, then import a private, editable copy into your workspace.",
  alternates: { canonical: "/templates" },
  openGraph: {
    title: "Community Templates — Agents & Workflows | VegaDūta",
    description:
      "Browse agent and workflow templates shared by the VegaDūta community. Import a private, editable copy in one click.",
    url: "/templates",
  },
};

export default function TemplatesLayout({ children }: { children: ReactNode }) {
  return children;
}
