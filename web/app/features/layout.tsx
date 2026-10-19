import type { Metadata } from "next";
import type { ReactNode } from "react";

// Server-component metadata shell for /features (SEO foundations pass,
// 2026-07-25). page.tsx is "use client" and cannot export metadata itself;
// this pass-through layout carries the route's SEO tags and renders
// children unchanged.
export const metadata: Metadata = {
  title: "Plans & Features — Free, Premium, Unlimited | VegaDūta",
  description:
    "Compare VegaDūta plans for AI business automation: WhatsApp AI agents, knowledge bases, BYOK models, and white-label options across Free, Premium, and Unlimited tiers.",
  alternates: { canonical: "/features" },
  openGraph: {
    title: "Plans & Features — Free, Premium, Unlimited | VegaDūta",
    description:
      "Compare what each VegaDūta tier unlocks: WhatsApp AI agents, knowledge bases, BYOK models, and white-labeling.",
    images: ["/og-image.png"],
  },
};

export default function FeaturesLayout({ children }: { children: ReactNode }) {
  return children;
}
