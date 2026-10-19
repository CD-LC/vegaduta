import EnterpriseLanding from "../components/enterprise-landing/EnterpriseLanding";

// ---------------------------------------------------------------------------
// /enterprise — B2B lead generation landing for Agentic AI implementation
// services. Optimized for Google Ads → enterprise decision-makers.
// Server component that exports metadata; all content and interactivity
// live in the EnterpriseLanding client tree.
// ---------------------------------------------------------------------------

const TITLE = "Agentic AI for Enterprise Teams — VegaDūta";
const DESCRIPTION =
  "AI agents with approval gates, audit logs, budgets, tenant isolation, SSO and SCIM built in. Tell us the process and we build the first agent with you.";

export async function generateMetadata() {
  return {
    title: TITLE,
    description: DESCRIPTION,
    alternates: { canonical: "/enterprise" },
    openGraph: {
      title: TITLE,
      description: DESCRIPTION,
      url: "/enterprise",
      type: "website",
      images: ["/og-image.png"],
    },
  };
}

export default function EnterprisePage() {
  return <EnterpriseLanding />;
}
