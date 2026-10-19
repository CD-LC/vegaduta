import type { Metadata } from "next";
import type { ReactNode } from "react";

// /explore is a permanent redirect to "/" (see explore/page.tsx) — it renders
// no page of its own, so the SEO metadata this layout used to declare
// (title, description, OG card and a self-referencing canonical "/explore")
// described a page that does not exist and advertised a redirect target as
// canonical (SEO audit, 2026-09-09). The layout stays only to say the
// opposite, explicitly: don't index this URL. Anyone with an old /explore
// link still lands on the identical landing content via the redirect.
export const metadata: Metadata = {
  robots: { index: false, follow: true },
};

export default function ExploreLayout({ children }: { children: ReactNode }) {
  return children;
}
