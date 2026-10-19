import type { Metadata } from "next";
import { localizedRouteMetadata } from "../lib/seo-i18n";
import type { ReactNode } from "react";

// Server-component metadata shell for /signup (SEO foundations pass,
// 2026-07-25). page.tsx is "use client" and cannot export metadata itself;
// this pass-through layout carries the route's SEO tags and renders
// children unchanged. Also inherited by /signup/complete, which robots.ts
// disallows from crawling anyway.
// generateMetadata, not a static export (multilingual SEO, 2026-09-19): this
// route also serves "/<locale>/signup", and each language version needs its own
// title/description, a canonical pointing at ITSELF, and the shared hreflang
// cluster. The copy lives in messages/*.json under seoMeta.signup (en.json is
// the English source).
export async function generateMetadata(): Promise<Metadata> {
  return localizedRouteMetadata("signup", "/signup");
}

export default function SignupLayout({ children }: { children: ReactNode }) {
  return children;
}
