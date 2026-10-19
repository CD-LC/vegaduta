import type { Metadata } from "next";
import { notFound } from "next/navigation";
import HubArticle from "../../components/marketing/HubArticle";
import { findHubPage } from "../../lib/seo-hub";
import { PLATFORM_TOPICS } from "../platform-data";

// ---------------------------------------------------------------------------
// SEO/AEO hub — /platform/[slug] (2026-08-14). Statically generated from
// platform-data.ts; anything else 404s. See lib/seo-hub.ts.
// ---------------------------------------------------------------------------

export const dynamicParams = false;

export function generateStaticParams(): { slug: string }[] {
  return PLATFORM_TOPICS.map((p) => ({ slug: p.slug }));
}

type Params = { params: Promise<{ slug: string }> };

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { slug } = await params;
  const page = findHubPage(PLATFORM_TOPICS, slug);
  if (!page) return {};
  const path = `/platform/${page.slug}`;
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

export default async function PlatformTopicPage({ params }: Params) {
  const { slug } = await params;
  const page = findHubPage(PLATFORM_TOPICS, slug);
  if (!page) notFound();
  return <HubArticle page={page} basePath="/platform" baseLabel="Platform" />;
}
