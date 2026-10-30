"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useRouter } from "next/navigation";
import { AlertCircle, LayoutGrid, RefreshCw, Search, X } from "lucide-react";
import { api } from "../../lib/api";
import type { CommunityTemplateKind, CommunityTemplatePage, CommunityTemplateSummary } from "../../lib/api";
import { Card, CardContent } from "../ui/card";
import { Skeleton } from "../ui/skeleton";
import { Button } from "../ui/button";
import PaginationControls from "../PaginationControls";
import TemplateCard from "./TemplateCard";
import { TEMPLATE_CATEGORIES, TEMPLATE_KINDS } from "./categories";
import { useTranslations } from "../../lib/i18n";

const PAGE_SIZE = 12;
const GALLERY_PAGE_SIZES = [12, 24, 48];

export interface GalleryFilters {
  kind: CommunityTemplateKind | "all";
  category: string | "all";
  tag: string | null;
  page: number;
}

// The gallery client. Seeded with whatever the server component managed to
// fetch (real HTML for crawlers when the API is live) and re-fetches on the
// client as filters/pages change. Every fetch degrades gracefully: skeletons
// while loading, a friendly empty state, and a retryable error card if the
// (not-yet-live) backend is unreachable.
export default function TemplatesGallery({
  initialResult,
  initialFilters,
}: {
  initialResult: CommunityTemplatePage | null;
  initialFilters: GalleryFilters;
}) {
  const tHC = useTranslations("hcComponentsTemplatesTemplatesGallery");
  const router = useRouter();
  const [filters, setFilters] = useState<GalleryFilters>(initialFilters);
  const [result, setResult] = useState<CommunityTemplatePage | null>(initialResult);
  // If the server already handed us matching data, start "ready"; otherwise load.
  const [status, setStatus] = useState<"loading" | "ready" | "error">(initialResult ? "ready" : "loading");
  const [error, setError] = useState<string | null>(null);
  // Skip the very first fetch when the server already provided initial data for
  // these exact filters - avoids a redundant round-trip / flash on mount.
  const [pageSize, setPageSize] = useState(PAGE_SIZE);
  const skipNextFetch = useRef<boolean>(!!initialResult);
  const reqId = useRef(0);

  const load = useCallback(
    async (f: GalleryFilters) => {
      const myReq = ++reqId.current;
      setStatus("loading");
      setError(null);
      try {
        const res = await api.listGalleryTemplates({
          kind: f.kind === "all" ? undefined : f.kind,
          category: f.category === "all" ? undefined : f.category,
          tag: f.tag ?? undefined,
          page: f.page,
          pageSize,
        });
        if (myReq !== reqId.current) return; // a newer request superseded this one
        setResult(res);
        setStatus("ready");
      } catch (e: any) {
        if (myReq !== reqId.current) return;
        setError(e?.message || "Couldn't load templates. Please try again.");
        setStatus("error");
      }
    },
    [pageSize]
  );

  useEffect(() => {
    if (skipNextFetch.current) {
      skipNextFetch.current = false;
      return;
    }
    void load(filters);
    // Reflect filters in the URL for shareable/back-navigable gallery links.
    const qs = buildQuery(filters);
    router.replace(`/templates${qs ? `?${qs}` : ""}`, { scroll: false });
  }, [filters, load, router]);

  function update(patch: Partial<GalleryFilters>) {
    setFilters((prev) => {
      const next = { ...prev, ...patch };
      // Any filter change (other than paging itself) resets to page 1.
      if (!("page" in patch)) next.page = 1;
      return next;
    });
  }

  const items: CommunityTemplateSummary[] = useMemo(() => result?.items ?? [], [result]);
  // Heuristic, not a real count - the API has no total. See
  // CommunityTemplatePage's comment in lib/api.ts.
  const hasMore = result?.hasMore ?? false;
  const currentPage = result?.page ?? filters.page;

  // Tag facets derived from the currently loaded page - honest (only tags that
  // actually exist on returned templates), no invented taxonomy.
  const tagFacets = useMemo(() => {
    const seen = new Set<string>();
    for (const t of items) for (const tag of t.tags || []) seen.add(tag);
    if (filters.tag) seen.add(filters.tag);
    return Array.from(seen).sort().slice(0, 16);
  }, [items, filters.tag]);

  return (
    <div className="space-y-8">
      {/* ---- Filters ------------------------------------------------- */}
      <div className="space-y-4">
        {/* Kind */}
        <div className="flex flex-wrap items-center gap-2">
          <FilterButton active={filters.kind === "all"} onClick={() => update({ kind: "all" })}>
            {tHC("s7")}
          </FilterButton>
          {TEMPLATE_KINDS.map((k) => {
            const Icon = k.icon;
            return (
              <FilterButton key={k.id} active={filters.kind === k.id} onClick={() => update({ kind: k.id })}>
                <Icon size={13} strokeWidth={2} aria-hidden="true" /> {k.label}
              </FilterButton>
            );
          })}
        </div>

        {/* Category */}
        <div className="flex flex-wrap items-center gap-2">
          <FilterButton active={filters.category === "all"} onClick={() => update({ category: "all" })}>
            <LayoutGrid size={13} strokeWidth={2} aria-hidden="true" /> {tHC("s6")}
          </FilterButton>
          {TEMPLATE_CATEGORIES.map((c) => (
            <FilterButton key={c.id} active={filters.category === c.id} onClick={() => update({ category: c.id })}>
              <span className="cat-tint inline-flex" style={{ ["--cat" as string]: c.cat }} aria-hidden="true">
                <c.icon size={13} strokeWidth={2} />
              </span>
              {c.label}
            </FilterButton>
          ))}
        </div>

        {/* Tags (only when the loaded results actually carry tags) */}
        {tagFacets.length > 0 && (
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs font-medium text-muted-foreground">Tags:</span>
            {filters.tag && (
              <FilterButton active onClick={() => update({ tag: null })}>
                {filters.tag} <X size={12} strokeWidth={2} aria-hidden="true" />
              </FilterButton>
            )}
            {tagFacets
              .filter((t) => t !== filters.tag)
              .map((tag) => (
                <FilterButton key={tag} active={false} onClick={() => update({ tag })}>
                  {tag}
                </FilterButton>
              ))}
          </div>
        )}
      </div>

      {/* ---- Results ------------------------------------------------- */}
      {status === "loading" && <GallerySkeleton />}

      {status === "error" && (
        <Card elevation="base" className="items-center gap-3 py-12 text-center">
          <CardContent className="flex flex-col items-center gap-3">
            <span className="cat-chip cat-chip-lg" style={{ ["--cat" as string]: "var(--state-error)" }}>
              <AlertCircle size={18} strokeWidth={2} aria-hidden="true" />
            </span>
            <p className="text-sm font-medium text-foreground">{tHC("s5")}</p>
            <p className="max-w-sm text-sm text-muted-foreground">{error}</p>
            <Button variant="outline" size="sm" onClick={() => load(filters)}>
              <RefreshCw size={14} strokeWidth={2} aria-hidden="true" /> {tHC("s4")}
            </Button>
          </CardContent>
        </Card>
      )}

      {status === "ready" && items.length === 0 && (
        <Card elevation="base" className="items-center gap-3 py-12 text-center">
          <CardContent className="flex flex-col items-center gap-3">
            <span className="cat-chip cat-chip-lg" style={{ ["--cat" as string]: "var(--cat-assistant)" }}>
              <Search size={18} strokeWidth={2} aria-hidden="true" />
            </span>
            <p className="text-sm font-medium text-foreground">{tHC("s3")}</p>
            <p className="max-w-sm text-sm text-muted-foreground">{tHC("s2")}</p>
            {(filters.kind !== "all" || filters.category !== "all" || filters.tag) && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => setFilters({ kind: "all", category: "all", tag: null, page: 1 })}
              >
                {tHC("s1")}
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      {status === "ready" && items.length > 0 && (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {items.map((t) => (
              <TemplateCard key={t.id} template={t} />
            ))}
          </div>

          {/* The API returns a bare list with no total, so the pager runs in "more available" mode. */}
          <PaginationControls
            page={currentPage}
            pageSize={pageSize}
            hasMore={hasMore}
            pageItemCount={items.length}
            pageSizeOptions={GALLERY_PAGE_SIZES}
            itemLabel="templates"
            onPageChange={(n) => update({ page: n })}
            onPageSizeChange={(size) => {
              setPageSize(size);
              update({ page: 1 });
            }}
          />
        </>
      )}
    </div>
  );
}

// Emits the browser-facing URL, so `kind` is lowercased here (?kind=agent) -
// see serverFetch.galleryQuery's note. parseKind reads either casing back.
function buildQuery(f: GalleryFilters): string {
  const p = new URLSearchParams();
  if (f.kind !== "all") p.set("kind", f.kind.toLowerCase());
  if (f.category !== "all") p.set("category", f.category);
  if (f.tag) p.set("tag", f.tag);
  if (f.page > 1) p.set("page", String(f.page));
  return p.toString();
}

function FilterButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={[
        "inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary",
        active
          ? "border-primary/40 bg-primary/12 text-foreground"
          : "border-border bg-card text-muted-foreground hover:bg-[var(--surface-hover)] hover:text-foreground",
      ].join(" ")}
    >
      {children}
    </button>
  );
}

function GallerySkeleton() {
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3" aria-hidden="true">
      {Array.from({ length: 6 }).map((_, i) => (
        <Card key={i} elevation="base" className="gap-0 p-5">
          <CardContent className="flex flex-col gap-3 p-0">
            <Skeleton className="size-8 rounded-[0.55rem]" />
            <Skeleton className="h-4 w-3/4" />
            <Skeleton className="h-3 w-full" />
            <Skeleton className="h-3 w-5/6" />
            <Skeleton className="mt-2 h-3 w-24" />
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
