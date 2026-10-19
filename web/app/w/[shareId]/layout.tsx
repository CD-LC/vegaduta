import type { Metadata } from "next";
import type { ReactNode } from "react";

// Metadata shell for the public workflow run page (/w/<shareId>) — the same
// pass-through-layout pattern as /a/<shareId>/layout.tsx, and the same
// robots posture for the same reason: these are USER-GENERATED pages minted
// in unbounded numbers with a 3-run life. noindex/nofollow, generic card.
//
// Unlike /a, there is deliberately NO per-workflow unfurl lookup here: the
// public GET for a workflow returns its creator-defined form, and a
// workflow's title is anonymous-visitor text with a lifetime measured in
// runs, not days — a generic card is the honest one.

export const metadata: Metadata = {
  title: "Run this AI workflow — VegaDūta",
  description:
    "Someone built this AI workflow in a free VegaDūta sandbox. Run it once from your phone — no app, no signup.",
  robots: {
    index: false,
    follow: false,
  },
};

export default function WorkflowRunLayout({ children }: { children: ReactNode }) {
  return children;
}
