import type { Metadata } from "next";
import type { ReactNode } from "react";

// Server-component metadata shell for /learn, same pattern as help/layout.tsx:
// page.tsx is "use client" and cannot export metadata itself, so this
// pass-through layout carries the route's tags and renders children unchanged.
//
// noindex/nofollow, unlike /help: /learn is NOT in AuthProvider's
// ANONYMOUS_RENDER_ROUTES, so a crawler without a session never sees the
// content — it would only index a sign-in bounce. The public, indexable
// entry points to the curriculum are the Academy's own pages and the landing
// page's Academy links.
export const metadata: Metadata = {
  title: "Learn — the VegaDūta Academy in your console",
  description:
    "Find the Academy lesson for the console screen you are on, and browse the whole curriculum: every level and what it makes you able to do.",
  robots: { index: false, follow: false },
};

export default function LearnLayout({ children }: { children: ReactNode }) {
  return children;
}
