"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AlertCircle, RefreshCw, Search } from "lucide-react";
import { API_BASE } from "../../lib/api";
import { Card, CardContent } from "../ui/card";
import { Skeleton } from "../ui/skeleton";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Select } from "../ui/select";
import PluginCard from "./PluginCard";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import type { PluginCatalog, PluginEntry } from "./serverFetch";

// The plugins marketplace client (same seeded-SSR + client-takeover contract
// as TemplatesGallery): rendered with whatever the server component managed
// to fetch - real HTML for crawlers when the API is live - and re-fetches on
// the client only when the server came up empty or the visitor retries.
// Catalog is a small fixed list (no filters/paging), so the states are just
// loading skeletons, a retryable error card, and the grid.
//
// Deliberately a bare fetch() rather than a lib/api method: the catalog is
// public/unauthenticated (permitAll - the marketplace page renders for
// logged-out visitors), so the bearer-token plumbing has nothing to add.
/** Search by name/description and narrow by availability (a build to download, or a store link). Pure for tests. */
export function filterPlugins(
  plugins: PluginEntry[],
  query: string,
  availability: "all" | "available" | "soon"
): PluginEntry[] {
  const needle = query.trim().toLowerCase();
  return plugins.filter((p) => {
    const ready = p.artifact !== null || p.externalStoreUrl !== null;
    if (availability === "available" && !ready) return false;
    if (availability === "soon" && ready) return false;
    return !needle || `${p.displayName} ${p.description}`.toLowerCase().includes(needle);
  });
}

export default function PluginsGallery({ initialCatalog }: { initialCatalog: PluginCatalog | null }) {
  const [catalog, setCatalog] = useState<PluginCatalog | null>(initialCatalog);
  const [status, setStatus] = useState<"loading" | "ready" | "error">(initialCatalog ? "ready" : "loading");
  const [error, setError] = useState<string | null>(null);
  // Skip the on-mount fetch when the server already provided the catalog -
  // avoids a redundant round-trip / flash on mount.
  const skipNextFetch = useRef<boolean>(!!initialCatalog);
  // Above the loading/error early returns so hook order stays stable.
  const [query, setQuery] = useState("");
  const [availability, setAvailability] = useState<"all" | "available" | "soon">("all");
  const filtered = useMemo(
    () => filterPlugins(catalog?.plugins || [], query, availability),
    [catalog, query, availability]
  );
  const catalogPagination = usePagination(filtered);

  const load = useCallback(async () => {
    setStatus("loading");
    setError(null);
    try {
      const res = await fetch(`${API_BASE}/api/plugins/catalog`, {
        headers: { Accept: "application/json" },
      });
      if (!res.ok) throw new Error("The service is temporarily unavailable. Please try again shortly.");
      setCatalog((await res.json()) as PluginCatalog);
      setStatus("ready");
    } catch (e: any) {
      setError(e?.message || "Couldn't load the plugin catalog. Please try again.");
      setStatus("error");
    }
  }, []);

  useEffect(() => {
    if (skipNextFetch.current) {
      skipNextFetch.current = false;
      return;
    }
    void load();
  }, [load]);

  if (status === "loading") {
    return (
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3" aria-hidden="true">
        {Array.from({ length: 3 }).map((_, i) => (
          <Card key={i} elevation="base" className="gap-0 p-5">
            <CardContent className="flex flex-col gap-3 p-0">
              <Skeleton className="size-9 rounded-[0.55rem]" />
              <Skeleton className="h-4 w-3/4" />
              <Skeleton className="h-3 w-full" />
              <Skeleton className="h-3 w-5/6" />
              <Skeleton className="mt-2 h-8 w-32" />
            </CardContent>
          </Card>
        ))}
      </div>
    );
  }

  if (status === "error" || !catalog) {
    return (
      <Card elevation="base" className="items-center gap-3 py-12 text-center">
        <CardContent className="flex flex-col items-center gap-3">
          <span className="cat-chip cat-chip-lg" style={{ ["--cat" as string]: "var(--state-error)" }}>
            <AlertCircle size={18} strokeWidth={2} aria-hidden="true" />
          </span>
          <p className="text-sm font-medium text-foreground">We couldn&apos;t load the plugin catalog</p>
          <p className="max-w-sm text-sm text-muted-foreground">
            {error || "Couldn't load the plugin catalog. Please try again."}
          </p>
          <Button variant="outline" size="sm" onClick={load}>
            <RefreshCw size={14} strokeWidth={2} aria-hidden="true" /> Try again
          </Button>
        </CardContent>
      </Card>
    );
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className="relative min-w-0 flex-1 basis-56">
          <Search
            size={14}
            aria-hidden="true"
            className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="search"
            className="ps-8"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search plugins"
            aria-label="Search plugins"
          />
        </div>
        <Select
          className="w-48"
          aria-label="Availability"
          value={availability}
          onValueChange={(v) => setAvailability(v as "all" | "available" | "soon")}
          options={[
            { value: "all", label: "All plugins" },
            { value: "available", label: "Available now" },
            { value: "soon", label: "Coming soon" },
          ]}
        />
      </div>
      {filtered.length === 0 ? (
        <p
          className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground"
          role="status"
        >
          No plugins match your search.
        </p>
      ) : null}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {catalogPagination.pageItems.map((p) => (
          <PluginCard key={p.id} plugin={p} />
        ))}
      </div>
      <PaginationControls
        page={catalogPagination.page}
        totalPages={catalogPagination.totalPages}
        totalItems={catalogPagination.totalItems}
        pageSize={catalogPagination.pageSize}
        onPageChange={catalogPagination.setPage}
        onPageSizeChange={catalogPagination.setPageSize}
        itemLabel="plugins"
      />
    </>
  );
}
