import { redirect } from "next/navigation";

// Merged landing (2026-07-28, direct feedback: "merge explore and landing
// page"): /explore used to render the SAME page as the logged-out root "/"
// (DemoLanding) verbatim - kept alive rather than deleted because it's a
// PUBLIC_ROUTES entry with inbound links in the wild. Byte-for-byte duplicate
// content across two indexable URLs is a real SEO/IA problem though (2026-07-29
// design audit) - a 301 here gets the same outcome for anyone with an old
// /explore link (they land on the identical content) without two URLs
// competing for the same ranking or reading as "nothing happened" when
// someone's linked both interchangeably.
export default function ExplorePage() {
  redirect("/");
}
