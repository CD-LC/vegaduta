import type { Metadata } from "next";
import StatusPageClient from "./StatusPageClient";
import { SITE_URL } from "../lib/site-url";
import JsonLd from "../components/JsonLd";

// ---------------------------------------------------------------------------
// Public /status page (Tier-4 public surface). Server shell carries metadata
// + JSON-LD; the live component grid is a client island polling
// GET {API_BASE}/api/public/status - a purpose-built permitAll endpoint that
// answers component names + status codes only (no versions, no tenant data).
// ---------------------------------------------------------------------------

const BASE = SITE_URL;
const PATH = "/status";

export const metadata: Metadata = {
  title: "System Status — VegaDūta",
  description:
    "Current status of the VegaDūta platform - API, database, cache, event bus, embeddings service and WhatsApp service, read from live probes every 30 seconds while the page is open.",
  alternates: { canonical: PATH },
  openGraph: {
    title: "System Status — VegaDūta",
    description:
      "Current status of the VegaDūta platform - API, database, cache, event bus, embeddings service and WhatsApp service.",
    url: PATH,
    type: "website",
  },
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "BreadcrumbList",
  itemListElement: [
    { "@type": "ListItem", position: 1, name: "Home", item: BASE },
    { "@type": "ListItem", position: 2, name: "Status", item: `${BASE}${PATH}` },
  ],
};

export default function PublicStatusPage() {
  return (
    <>
      <JsonLd data={jsonLd} />
      <StatusPageClient />
    </>
  );
}
