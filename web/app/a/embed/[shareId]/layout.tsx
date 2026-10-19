import type { Metadata } from "next";
import type { ReactNode } from "react";

// Metadata shell for the widget's framed chat page (/a/embed/<shareId>) -
// page.tsx is "use client" and cannot export metadata itself, same
// pass-through-layout pattern as its siblings /a/[shareId]/layout.tsx and
// /agent/[shareId]/layout.tsx.
//
// DELIBERATELY STATIC, unlike both siblings' generateMetadata: those pages
// are links passed hand to hand on WhatsApp/Slack, where the unfurl card is
// the whole first impression. This URL only ever renders inside an iframe a
// loader script constructed - nobody shares it, nothing unfurls it - so a
// per-agent server-side meta fetch would spend a core round-trip per page
// load to decorate a card no human will ever see. The full-page viewer at
// /agent/<shareId> remains the shareable surface.
//
// robots noindex/nofollow for the same reason as the siblings, only more so:
// tenant-controlled content, minted and revoked freely, and additionally a
// UI fragment that is meaningless outside its host page's launcher.

export const metadata: Metadata = {
  title: "Chat — VegaDūta",
  robots: {
    index: false,
    follow: false,
  },
};

export default function WidgetEmbedLayout({ children }: { children: ReactNode }) {
  return children;
}
