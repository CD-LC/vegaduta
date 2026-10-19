import type { Metadata } from "next";
import LibraryChrome from "../../components/library/LibraryChrome";
import TemplateDetailView from "../../components/templates/TemplateDetailView";
import { fetchCommunityTemplateServer } from "../../components/templates/serverFetch";

// PUBLIC read-only detail for one approved template. Server component: it
// best-effort fetches the template on the server (crawlable design + real
// per-template <title>/description) and hands it to the client view, which
// owns the Import action and self-heals if the not-yet-live backend was
// unreachable during SSR.
export async function generateMetadata({
  params,
}: {
  params: Promise<{ id: string }>;
}): Promise<Metadata> {
  const { id } = await params;
  const detail = await fetchCommunityTemplateServer(id);
  if (!detail) {
    // Backend unreachable or not-found during SSR - a generic, still-valid
    // title. The client view renders the proper "couldn't load" state.
    return {
      title: "Community Template | VegaDūta",
      description: "Inspect and import a community-shared agent or workflow template.",
    };
  }
  const title = `${detail.title} — Community Template | VegaDūta`;
  return {
    title,
    description: detail.summary,
    alternates: { canonical: `/templates/${id}` },
    openGraph: { title, description: detail.summary, url: `/templates/${id}` },
  };
}

export default async function TemplateDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const initial = await fetchCommunityTemplateServer(id);

  return (
    <LibraryChrome eyebrowKey="eyebrowTemplates">
      <TemplateDetailView id={id} initial={initial} />
    </LibraryChrome>
  );
}
