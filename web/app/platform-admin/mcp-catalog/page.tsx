"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { RefreshCw, ExternalLink, Search, CheckCircle2, CircleDashed } from "lucide-react";
import { api, type McpRegistrySearchResult } from "../lib/api";
import AdminShell from "../components/AdminShell";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { toast } from "../lib/toastBus";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Badge } from "../components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { safeHref } from "../lib/safeLink";
import { useTranslations } from "../../lib/i18n";

// Platform-wide MCP visibility for admins - previously nothing here at all
// (web-admin had a Voice & Tool Credentials page but no MCP equivalent).
// Read-only by design: the curated catalog below is a fixed, hand-maintained
// list in McpCatalogService (core/mcp) - not persisted, not editable through
// any API today - so this page shows admins exactly what every tenant's
// "Browse catalog" tab (web/app/components/RegistryBrowser.tsx) offers,
// without reading Java source to find out. The second tab is the same live
// pass-through search over the public MCP Registry that tenants get, for the
// same "what's out there beyond our curated list" visibility.
//
// This deliberately does NOT attempt per-tenant MCP registration visibility
// (which servers each tenant has actually connected) - that data lives in
// per-tenant schemas with no cross-tenant listing endpoint today, and adding
// one is a real backend feature, not a UI redesign. Linked from the Voice &
// Tool Credentials page rather than its own top-level nav rail entry
// (see AdminShell.tsx's IA comment).

function TypeBadge({ type }: { type: string }) {
  return (
    <Badge variant="outline" className="font-mono text-[10px] uppercase">
      {type}
    </Badge>
  );
}

function ReadyBadge({ ready }: { ready?: boolean }) {
    const tHC = useTranslations("hcPlatformadminMcpcatalogPage");
  return ready ? (
    <Badge variant="outline" className="gap-1 text-[var(--success-text)]">
      <CheckCircle2 size={12} /> Ready
    </Badge>
  ) : (
    <Badge variant="secondary" className="gap-1">
      <CircleDashed size={12} /> {tHC("hcPlatformadminMcpcatalogPage.s11")}
    </Badge>
  );
}

export default function McpCatalogPage() {
    const tHC = useTranslations("hcPlatformadminMcpcatalogPage");
  const [tab, setTab] = useState("catalog");
  const [catalog, setCatalog] = useState([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");
  const [loadingMore, setLoadingMore] = useState(false);
  const [searchResults, setSearchResults] = useState<McpRegistrySearchResult | null>(null);
  const [searching, setSearching] = useState(false);

  const refreshCatalog = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.getMcpCatalog();
      setCatalog(Array.isArray(list) ? list : []);
    } catch (err) {
      toast.error((err as Error).message || "Could not load the MCP catalog.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refreshCatalog().catch((e) => console.error("MCP catalog initial load failed", e));
  }, [refreshCatalog]);

  const catalogPg = usePagination(catalog as any[], 12);

  // Registry search is cursor-paged: append the next page to what is shown.
  async function loadMore() {
    const cursor = searchResults?.nextCursor;
    if (!cursor) return;
    setLoadingMore(true);
    try {
      const res = await api.searchMcpRegistry(query.trim(), cursor);
      setSearchResults((prev) => ({
        ...res,
        results: [...(prev?.results || []), ...(res?.results || [])],
      }));
    } catch (err) {
      toast.error((err as Error).message || "Could not load more registry results.");
    } finally {
      setLoadingMore(false);
    }
  }

  async function runSearch(e?: FormEvent) {
    e?.preventDefault();
    setSearching(true);
    try {
      const res = await api.searchMcpRegistry(query.trim(), null);
      setSearchResults(res);
      if (res?.unavailable) {
        toast.error("The public MCP Registry is unavailable right now - try again shortly.");
      }
    } catch (err) {
      toast.error((err as Error).message || "Registry search failed.");
    } finally {
      setSearching(false);
    }
  }

  return (
    <AdminShell
      title={tHC("hcPlatformadminMcpcatalogPage.s10")}
      actions={
        <Button
          variant="outline"
          size="sm"
          onClick={tab === "catalog" ? refreshCatalog : runSearch}
          disabled={tab === "catalog" ? loading : searching}
          title="Refresh"
        >
          <RefreshCw size={14} className={(tab === "catalog" ? loading : searching) ? "animate-spin" : undefined} />{" "}
          Refresh
        </Button>
      }
    >
      <p className="mb-4 text-sm text-muted-foreground">
        {tHC("hcPlatformadminMcpcatalogPage.s9")}
      </p>

      <div className="mb-4 flex items-center gap-1 border-b border-border" role="tablist" aria-label={tHC("hcPlatformadminMcpcatalogPage.s8")}>
        <button
          type="button"
          id="mcp-catalog-tab-catalog"
          role="tab"
          aria-selected={tab === "catalog"}
          aria-controls="mcp-catalog-panel-catalog"
          tabIndex={tab === "catalog" ? 0 : -1}
          onClick={() => setTab("catalog")}
          className={`border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
            tab === "catalog"
              ? "border-primary text-foreground"
              : "border-transparent text-muted-foreground hover:text-foreground"
          }`}
        >
          {tHC("hcPlatformadminMcpcatalogPage.s7")}{catalog.length})
        </button>
        <button
          type="button"
          id="mcp-catalog-tab-search"
          role="tab"
          aria-selected={tab === "search"}
          aria-controls="mcp-catalog-panel-search"
          tabIndex={tab === "search" ? 0 : -1}
          onClick={() => setTab("search")}
          className={`border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
            tab === "search"
              ? "border-primary text-foreground"
              : "border-transparent text-muted-foreground hover:text-foreground"
          }`}
        >
          {tHC("hcPlatformadminMcpcatalogPage.s6")}
        </button>
      </div>

      {tab === "catalog" && (
        <div
          id="mcp-catalog-panel-catalog"
          role="tabpanel"
          aria-labelledby="mcp-catalog-tab-catalog"
          className="grid grid-cols-1 gap-4 lg:grid-cols-2 xl:grid-cols-3"
        >
          {catalogPg.pageItems.map((entry: any) => (
            <Card key={entry.key} className="gap-3 p-5">
              <CardHeader className="p-0">
                <div className="flex items-start justify-between gap-2">
                  <CardTitle className="text-base">{entry.name}</CardTitle>
                  <ReadyBadge ready={entry.ready} />
                </div>
              </CardHeader>
              <CardContent className="space-y-2 p-0">
                <div className="flex flex-wrap items-center gap-1.5">
                  <TypeBadge type={entry.type} />
                  {entry.requiredEnvVars?.length > 0 && (
                    <Badge variant="secondary" className="text-[10px]">
                      {entry.requiredEnvVars.length} {tHC("hcPlatformadminMcpcatalogPage.s5")}{entry.requiredEnvVars.length === 1 ? "" : "s"}
                    </Badge>
                  )}
                </div>
                <p className="text-sm text-muted-foreground">{entry.description}</p>
                {/* SEC-037: registry data - only http(s) URLs become links. */}
                {safeHref(entry.docsUrl) && (
                  <a
                    href={safeHref(entry.docsUrl)}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
                  >
                    Documentation <ExternalLink size={11} />
                  </a>
                )}
              </CardContent>
            </Card>
          ))}
          {!loading && catalog.length === 0 && (
            <p className="col-span-full py-8 text-center text-sm text-muted-foreground">{tHC("hcPlatformadminMcpcatalogPage.s4")}</p>
          )}
          <div className="col-span-full">
            <PaginationControls
              page={catalogPg.page}
              totalPages={catalogPg.totalPages}
              totalItems={catalogPg.totalItems}
              pageSize={catalogPg.pageSize}
              onPageChange={catalogPg.setPage}
              onPageSizeChange={catalogPg.setPageSize}
              itemLabel="servers"
            />
          </div>
        </div>
      )}

      {tab === "search" && (
        <div id="mcp-catalog-panel-search" role="tabpanel" aria-labelledby="mcp-catalog-tab-search">
          <form onSubmit={runSearch} className="mb-4 flex max-w-md items-center gap-2">
            <Input
              placeholder={tHC("hcPlatformadminMcpcatalogPage.s3")}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <Button type="submit" size="sm" disabled={searching}>
              <Search size={14} /> Search
            </Button>
          </form>
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 xl:grid-cols-3">
            {(searchResults?.results || []).map((s) => (
              <Card key={s.name} className="gap-3 p-5">
                <CardHeader className="p-0">
                  <div className="flex items-start justify-between gap-2">
                    <CardTitle className="text-base">{s.displayName || s.name}</CardTitle>
                    {!s.runnable && (
                      <Badge variant="destructive" title={s.notRunnableReason || ""}>
                        {tHC("hcPlatformadminMcpcatalogPage.s2")}
                      </Badge>
                    )}
                  </div>
                </CardHeader>
                <CardContent className="space-y-2 p-0">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <TypeBadge type={s.type} />
                    {s.version && (
                      <Badge variant="secondary" className="font-mono text-[10px]">
                        v{s.version}
                      </Badge>
                    )}
                  </div>
                  <p className="text-sm text-muted-foreground">{s.description}</p>
                  {/* SEC-037: public-registry data - only http(s) URLs become links. */}
                  {safeHref(s.repositoryUrl) && (
                    <a
                      href={safeHref(s.repositoryUrl)}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
                    >
                      Repository <ExternalLink size={11} />
                    </a>
                  )}
                </CardContent>
              </Card>
            ))}
          </div>
          {searchResults?.nextCursor && (
            <div className="flex justify-center py-4">
              <Button variant="outline" size="sm" onClick={() => void loadMore()} disabled={loadingMore}>
                {loadingMore ? "Loading..." : "Load more results"}
              </Button>
            </div>
          )}
          {searchResults && (searchResults.results || []).length === 0 && (
            <p className="py-8 text-center text-sm text-muted-foreground">
              {searchResults.unavailable ? "The registry is unavailable right now." : "No matches."}
            </p>
          )}
          {!searchResults && (
            <p className="py-8 text-center text-sm text-muted-foreground">{tHC("hcPlatformadminMcpcatalogPage.s1")}</p>
          )}
        </div>
      )}
    </AdminShell>
  );
}
