import type { Metadata } from "next";
import HubIndex from "../components/marketing/HubIndex";
import { PLATFORM_TOPICS } from "./platform-data";

// ---------------------------------------------------------------------------
// SEO/AEO hub — /platform index (2026-08-14). One card per platform-topic
// page in platform-data.ts; see lib/seo-hub.ts for the architecture.
// ---------------------------------------------------------------------------

export const metadata: Metadata = {
  title: "Platform — Workflow Automation, APIs, Security | VegaDūta",
  description:
    "The VegaDūta platform layer: Temporal-backed workflow automation, a plain-language agent builder, webhooks, developer APIs, tenant isolation and observability.",
  alternates: { canonical: "/platform" },
  openGraph: {
    title: "Platform — Workflow Automation, APIs, Security | VegaDūta",
    description:
      "Temporal-backed workflow automation, a plain-language agent builder, webhooks, developer APIs, tenant isolation and observability.",
    url: "/platform",
    type: "website",
    images: ["/og-image.png"],
  },
};

export default function PlatformIndexPage() {
  return (
    <HubIndex
      pages={PLATFORM_TOPICS}
      basePath="/platform"
      label="Platform"
      h1="The platform under your AI workforce"
      intro={[
        "Agents are only as dependable as the platform they run on. VegaDūta pairs its agents with durable Temporal-backed workflows, a builder that works in plain language, real APIs, schema-per-tenant isolation, and tracing on every LLM call.",
        "These pages document each platform capability as it is actually built — including what is not built yet.",
      ]}
    />
  );
}
