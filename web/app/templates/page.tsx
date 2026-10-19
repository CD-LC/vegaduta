import Link from "next/link";
import { LibraryBig, UserRound } from "lucide-react";
import LibraryChrome from "../components/library/LibraryChrome";
import TemplatesGallery from "../components/templates/TemplatesGallery";
import type { GalleryFilters } from "../components/templates/TemplatesGallery";
import { fetchCommunityTemplatesServer, parseKind } from "../components/templates/serverFetch";
import { Button } from "../components/ui/button";

const PAGE_SIZE = 12;

// PUBLIC gallery of APPROVED community templates. Server component so the
// first page of results is real HTML for crawlers (no client-reveal gating -
// the academy crawler-bug lesson). It best-effort fetches on the server and
// hands the result to the client gallery, which re-fetches as filters change.
// When the (parallel, not-yet-live) backend is unreachable the server fetch
// returns null and the client renders its own loading/empty/error UI.
export default async function TemplatesGalleryPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const sp = await searchParams;
  const first = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v);

  const initialFilters: GalleryFilters = {
    kind: parseKind(sp.kind),
    category: first(sp.category) || "all",
    tag: first(sp.tag) || null,
    page: Math.max(1, Number.parseInt(first(sp.page) || "1", 10) || 1),
  };

  const initialResult = await fetchCommunityTemplatesServer(initialFilters, PAGE_SIZE);

  return (
    <LibraryChrome eyebrowKey="eyebrowTemplates">
      <header className="mb-8 flex flex-col gap-4 border-b border-border pb-8 sm:flex-row sm:items-end sm:justify-between">
        <div className="space-y-2">
          <p className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.14em] text-primary">
            <LibraryBig className="size-4" aria-hidden="true" />
            Community templates
          </p>
          <h1 className="max-w-2xl text-2xl font-semibold tracking-tight text-foreground sm:text-4xl">
            Start from what the community has built
          </h1>
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground sm:text-base">
            Agent and workflow templates shared by VegaDūta members and reviewed before they appear here.
            Inspect any one in full, then import a private, editable copy into your own workspace.
          </p>
        </div>
        <Button asChild variant="outline" size="sm" className="shrink-0">
          <Link href="/templates/mine">
            <UserRound size={15} strokeWidth={2} aria-hidden="true" /> My templates
          </Link>
        </Button>
      </header>

      <TemplatesGallery initialResult={initialResult} initialFilters={initialFilters} />
    </LibraryChrome>
  );
}
