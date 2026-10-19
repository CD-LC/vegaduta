import type { Metadata } from "next";
import type { ReactNode } from "react";

// /forms/<token> is a capability-style link to one workflow's hosted form: it must never be indexed (the token is
// the whole credential, and the page has no content worth a search result). noindex/nofollow lives here, in a
// server layout, because the page itself is a client component and cannot export metadata - the same arrangement
// as a/[shareId]/layout.tsx.
export const metadata: Metadata = {
  title: "Form",
  robots: { index: false, follow: false },
};

export default function FormsTokenLayout({ children }: { children: ReactNode }) {
  return children;
}
