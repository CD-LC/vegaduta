import type { Metadata } from "next";
import { notFound } from "next/navigation";
import HubArticle from "../../components/marketing/HubArticle";
import { findHubPage } from "../../lib/seo-hub";
import { INTEGRATIONS } from "../integrations-data";

// ---------------------------------------------------------------------------
// SEO/AEO hub — /integrations/[slug] (2026-08-14). Statically generated, one
// page per entry in integrations-data.ts; anything else 404s. Same posture
// as compare/[slug]: fully server-rendered, standalone marketing chrome, no
// AppShell/auth. Shared renderer: components/marketing/HubArticle.tsx.
// ---------------------------------------------------------------------------

export const dynamicParams = false;

export function generateStaticParams(): { slug: string }[] {
  return INTEGRATIONS.map((p) => ({ slug: p.slug }));
}

type Params = { params: Promise<{ slug: string }> };

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { slug } = await params;
  const page = findHubPage(INTEGRATIONS, slug);
  if (!page) return {};
  const path = `/integrations/${page.slug}`;
  return {
    title: page.metaTitle,
    description: page.metaDescription,
    alternates: { canonical: path },
    openGraph: {
      title: page.metaTitle,
      description: page.metaDescription,
      url: path,
      type: "article",
      images: ["/og-image.png"],
    },
  };
}

export default async function IntegrationPage({ params }: Params) {
  const { slug } = await params;
  const page = findHubPage(INTEGRATIONS, slug);
  if (!page) notFound();
  return <HubArticle page={page} basePath="/integrations" baseLabel="Integrations" />;
}
