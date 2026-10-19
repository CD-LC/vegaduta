import type { Metadata } from "next";
import { notFound } from "next/navigation";
import HubArticle from "../../components/marketing/HubArticle";
import { findHubPage } from "../../lib/seo-hub";
import { TUTORIALS } from "../tutorials-data";

// ---------------------------------------------------------------------------
// SEO/AEO hub — /tutorials/[slug] (2026-08-14). Statically generated from
// tutorials-data.ts; anything else 404s. Tutorial entries carry `steps`, so
// HubArticle renders numbered steps + HowTo JSON-LD. See lib/seo-hub.ts.
// ---------------------------------------------------------------------------

export const dynamicParams = false;

export function generateStaticParams(): { slug: string }[] {
  return TUTORIALS.map((p) => ({ slug: p.slug }));
}

type Params = { params: Promise<{ slug: string }> };

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { slug } = await params;
  const page = findHubPage(TUTORIALS, slug);
  if (!page) return {};
  const path = `/tutorials/${page.slug}`;
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

export default async function TutorialPage({ params }: Params) {
  const { slug } = await params;
  const page = findHubPage(TUTORIALS, slug);
  if (!page) notFound();
  return <HubArticle page={page} basePath="/tutorials" baseLabel="Tutorials" />;
}
