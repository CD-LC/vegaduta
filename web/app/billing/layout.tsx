import type { Metadata } from "next";
import type { ReactNode } from "react";

// Server-component metadata shell for /billing — the public pricing page
// (SEO foundations pass, 2026-07-25). page.tsx is "use client" and cannot
// export metadata itself; this pass-through layout carries the route's SEO
// tags and renders children unchanged.
export const metadata: Metadata = {
  title: "Pricing — Simple Plans, Start Free | VegaDūta",
  description:
    "VegaDūta pricing: start free, upgrade when you're ready. Simple monthly plans for AI agents, WhatsApp channels, and knowledge bases — built for India.",
  alternates: { canonical: "/billing" },
  openGraph: {
    title: "Pricing — Simple Plans, Start Free | VegaDūta",
    description:
      "Start free, upgrade when you're ready. Simple monthly plans for AI agents, WhatsApp channels, and knowledge bases.",
    images: ["/og-image.png"],
  },
};

export default function BillingLayout({ children }: { children: ReactNode }) {
  return children;
}
