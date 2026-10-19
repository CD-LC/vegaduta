import type { Metadata } from "next";
import { notFound } from "next/navigation";
import HubArticle from "../../components/marketing/HubArticle";
import { findHubPage } from "../../lib/seo-hub";
import { AI_TOPICS } from "../ai-data";

// ---------------------------------------------------------------------------
// SEO/AEO hub — /ai/[slug] (2026-08-14). Statically generated from
// ai-data.ts; anything else 404s. The static sibling route
// ai/model-context-protocol/ (301 → /ai/mcp) takes precedence over this
// dynamic segment by Next's routing rules — keep "model-context-protocol"
// out of ai-data.ts slugs.
// ---------------------------------------------------------------------------

export const dynamicParams = false;

export function generateStaticParams(): { slug: string }[] {
  return AI_TOPICS.map((p) => ({ slug: p.slug }));
}

type Params = { params: Promise<{ slug: string }> };

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { slug } = await params;
  const page = findHubPage(AI_TOPICS, slug);
  if (!page) return {};
  const path = `/ai/${page.slug}`;
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

export default async function AiTopicPage({ params }: Params) {
  const { slug } = await params;
  const page = findHubPage(AI_TOPICS, slug);
  if (!page) notFound();
  return <HubArticle page={page} basePath="/ai" baseLabel="AI capabilities" />;
}
