"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ChevronDown, ExternalLink, Fingerprint, Loader2, RefreshCw, Search, ShieldCheck } from "lucide-react";
import type { DirectoryEntry, DirectoryPage } from "./serverFetch";
import { DIRECTORY_PAGE_SIZE } from "./serverFetch";
import PaginationControls from "../PaginationControls";
import { pageForSizeChange } from "../../lib/usePagination";
import { useTranslations } from "../../lib/i18n";
import { publicApiBase } from "../../lib/env";
import { useAuthedHint } from "../../lib/hooks/useAuthedHint";

const API_BASE = publicApiBase();

// Public agent-directory browser - signed-out visitors and crawlers get the
// SSR-rendered list (initialEntries); search re-queries the public endpoint
// client-side. Every entry is a tenant-published, platform-signed snapshot -
// the "verify" affordance surfaces the signed manifest fields, not a trust
// claim we invented.

const DIRECTORY_PAGE_SIZES = [12, 24, 48, 100];

export default function DirectoryBrowser({
  initialEntries,
  initialTotal,
  initialHasMore,
}: {
  initialEntries: DirectoryEntry[] | null;
  /** Total matches the server reported for the first page; undefined when it did not (older core). */
  initialTotal?: number;
  initialHasMore?: boolean;
}) {
  const t = useTranslations("directoryPage");
  const tl = useTranslations("libraryPages");
  // Signed-in visitors go straight to their agents; only strangers need /signup.
  const authed = useAuthedHint();
  const [entries, setEntries] = useState<DirectoryEntry[]>(initialEntries ?? []);
  const [failed, setFailed] = useState(initialEntries === null);
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DIRECTORY_PAGE_SIZE);
  const [total, setTotal] = useState<number | undefined>(initialTotal);
  const [hasMore, setHasMore] = useState(initialHasMore ?? false);
  // Query the current `entries` were fetched for - drives "no agents yet"
  // (empty directory) vs "no matches" (empty search) copy.
  const [activeQuery, setActiveQuery] = useState("");
  const [busy, setBusy] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [detail, setDetail] = useState<Record<string, DirectoryEntry | null>>({});
  // Responses can land out of order while typing; only the newest one wins.
  const latestRequest = useRef(0);
  const firstRun = useRef(true);
  const lastQuery = useRef("");

  const search = useCallback(async (q: string, pageNo: number, size: number) => {
    const id = ++latestRequest.current;
    setBusy(true);
    try {
      const res = await fetch(
        `${API_BASE}/api/directory/agents?limit=${size}${pageNo > 1 ? `&offset=${(pageNo - 1) * size}` : ""}${q ? `&q=${encodeURIComponent(q)}` : ""}`,
        {
          headers: { Accept: "application/json" },
        }
      );
      if (!res.ok) throw new Error(String(res.status));
      const body = (await res.json()) as DirectoryPage;
      if (id !== latestRequest.current) return;
      setEntries(body.entries || []);
      // `total` / `hasMore` come from the offset-aware directory API; without them (older core) the pager falls
      // back to "a full page means there may be more".
      setTotal(typeof body.total === "number" ? body.total : undefined);
      setHasMore(typeof body.hasMore === "boolean" ? body.hasMore : (body.entries || []).length >= size);
      setActiveQuery(q);
      setFailed(false);
    } catch {
      if (id === latestRequest.current) setFailed(true);
    } finally {
      if (id === latestRequest.current) setBusy(false);
    }
  }, []);

  // Re-query on debounced search input. The SSR list already covers the first
  // render, so skip it unless SSR failed - but DO re-query when the box is
  // cleared, otherwise the previous filtered results stay on screen.
  useEffect(() => {
    const isFirst = firstRun.current;
    firstRun.current = false;
    if (isFirst && !failed) return undefined;
    // Typing is debounced; a page or page-size click is not.
    const delay = isFirst || lastQuery.current === query ? 0 : 300;
    lastQuery.current = query;
    const timer = setTimeout(() => void search(query.trim(), page, pageSize), delay);
    return () => clearTimeout(timer);
    // `failed` only matters on mount; re-running on it would loop retries.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, page, pageSize, search]);

  const toggleDetail = async (agentId: string) => {
    if (expanded === agentId) {
      setExpanded(null);
      return;
    }
    setExpanded(agentId);
    if (detail[agentId] !== undefined) return;
    try {
      const res = await fetch(`${API_BASE}/api/directory/agents/${encodeURIComponent(agentId)}`, {
        headers: { Accept: "application/json" },
      });
      if (!res.ok) throw new Error(String(res.status));
      const full = (await res.json()) as DirectoryEntry;
      setDetail((d) => ({ ...d, [agentId]: full }));
    } catch {
      setDetail((d) => ({ ...d, [agentId]: null }));
    }
  };

  const searching = activeQuery !== "" || query.trim() !== "";

  return (
    <section aria-busy={busy}>
      <div className="mb-6 flex w-full max-w-md items-center gap-2 rounded-md border border-border bg-secondary/40 px-3 focus-within:border-primary/60 focus-within:ring-2 focus-within:ring-primary/20">
        <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <input
          type="search"
          className="h-10 min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
          placeholder={t("searchPlaceholder")}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setPage(1);
          }}
          aria-label={t("searchPlaceholder")}
        />
        {busy && <Loader2 className="size-4 shrink-0 animate-spin text-muted-foreground" aria-hidden="true" />}
      </div>

      {failed && (
        <div
          role="alert"
          className="flex flex-col items-start gap-3 rounded-xl border border-border bg-secondary/30 p-5 text-sm text-muted-foreground sm:flex-row sm:items-center sm:justify-between"
        >
          <p>{t("unavailable")}</p>
          <button
            type="button"
            onClick={() => void search(query.trim(), page, pageSize)}
            disabled={busy}
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-xs font-medium text-foreground hover:bg-secondary disabled:opacity-60"
          >
            <RefreshCw className={`size-3.5 ${busy ? "animate-spin" : ""}`} aria-hidden="true" />
            {t("retry")}
          </button>
        </div>
      )}
      {!failed && entries.length === 0 && !busy && (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed border-border px-6 py-14 text-center">
          <Fingerprint className="size-8 text-muted-foreground" aria-hidden="true" />
          <p className="text-sm font-medium text-foreground">{searching ? t("noResults") : t("empty")}</p>
          {!searching && (
            <>
              <p className="max-w-md text-sm text-muted-foreground">{t("emptyHint")}</p>
              <Link
                href={authed ? "/agents" : "/signup"}
                className="text-sm font-medium text-primary underline-offset-4 hover:underline"
              >
                {t("emptyCta")}
              </Link>
            </>
          )}
        </div>
      )}

      <div className="grid items-start gap-4 sm:grid-cols-2">
        {entries.map((e) => {
          const isOpen = expanded === e.agentId;
          const full = isOpen ? detail[e.agentId] : undefined;
          const manifest = full?.manifest as Record<string, any> | null | undefined;
          const card = full?.card as Record<string, any> | null | undefined;
          const caps = e.capabilities ?? [];
          return (
            <article
              key={e.agentId}
              className="min-w-0 rounded-xl border border-border bg-card/40 p-5 transition-colors hover:border-foreground/30"
            >
              <button
                type="button"
                className="flex w-full items-start gap-3 rounded-md text-start outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
                onClick={() => toggleDetail(e.agentId)}
                aria-expanded={isOpen}
              >
                <span className="min-w-0 flex-1">
                  <h2 className="break-words text-base font-semibold text-foreground">{e.displayName || e.agentId}</h2>
                  {e.description && <p className="mt-1 line-clamp-2 text-sm text-muted-foreground">{e.description}</p>}
                  {caps.length > 0 && (
                    <span className="mt-2 flex flex-wrap gap-1.5">
                      {caps.slice(0, 6).map((c) => (
                        <span
                          key={c}
                          className="rounded-full border border-border px-2 py-0.5 text-xs text-muted-foreground"
                        >
                          {c}
                        </span>
                      ))}
                      {caps.length > 6 && (
                        <span className="px-1 py-0.5 text-xs text-muted-foreground">+{caps.length - 6}</span>
                      )}
                    </span>
                  )}
                </span>
                <ChevronDown
                  className={`mt-1 size-4 shrink-0 text-muted-foreground transition-transform ${isOpen ? "rotate-180" : ""}`}
                  aria-hidden="true"
                />
              </button>
              {isOpen && (
                <div className="mt-3 border-t border-border pt-3 text-xs text-muted-foreground">
                  {full === undefined && <p>{t("loading")}</p>}
                  {full === null && <p>{t("detailFailed")}</p>}
                  {full && (
                    <div className="grid gap-1.5">
                      {card?.url && (
                        <p>
                          {t("agentUrl")}{" "}
                          <code className="break-all font-mono text-foreground/80">{String(card.url)}</code>
                        </p>
                      )}
                      {manifest?.signature && (
                        <p className="flex items-center gap-1.5">
                          <ShieldCheck className="size-3.5 text-[var(--state-success)]" aria-hidden="true" />
                          {t("signedManifest")}
                        </p>
                      )}
                      {manifest?.manifest_hash_sha256 && (
                        <p>
                          {t("manifestHash")}{" "}
                          <code className="break-all font-mono text-foreground/80">
                            {String(manifest.manifest_hash_sha256)}
                          </code>
                        </p>
                      )}
                      {full.homepageUrl && (
                        <a
                          href={full.homepageUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline"
                        >
                          {t("homepage")} <ExternalLink className="size-3" aria-hidden="true" />
                        </a>
                      )}
                    </div>
                  )}
                </div>
              )}
            </article>
          );
        })}
      </div>

      {!failed && entries.length > 0 && (
        <PaginationControls
          page={page}
          pageSize={pageSize}
          {...(total !== undefined ? { totalItems: total } : { hasMore })}
          pageItemCount={entries.length}
          pageSizeOptions={DIRECTORY_PAGE_SIZES}
          loading={busy}
          itemLabel={tl("agentsLabel")}
          onPageChange={setPage}
          onPageSizeChange={(size) => {
            setPage(pageForSizeChange(page, pageSize, size));
            setPageSize(size);
          }}
        />
      )}
    </section>
  );
}
