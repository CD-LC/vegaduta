"use client";

import { useEffect, useMemo, useState } from "react";
import { Check, Copy, History, Loader2, Search } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { usePagination } from "../../lib/usePagination";
import { cn } from "../../lib/utils";
import { errorMessage } from "../../lib/errors";
import PaginationControls from "../PaginationControls";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Input } from "../ui/input";
import { Separator } from "../ui/separator";
import { chunksAsContext, parseRecentSearches, pushRecentSearch } from "./knowledgeDocs";
import { highlight, type KnowledgeCollectionSummary, type SearchResultItem } from "./knowledgeFormat";

const RECENT_SEARCHES_KEY = "vd.knowledge.recentSearches";

/**
 * Right-hand semantic search panel: query, recent searches (this browser only), collection scope,
 * matched chunks with a relevance bar, and which agents would receive those chunks as context.
 */
export default function SemanticSearchPanel({
  collections,
  agents,
  processingAvailable,
  onError,
}: {
  collections: KnowledgeCollectionSummary[];
  agents: any[];
  processingAvailable: boolean;
  onError: (message: string | null) => void;
}) {
  const t = useTranslations("knowledgePanel");
  const sidecarDown = !processingAvailable;
  const [query, setQuery] = useState("");
  const [recentSearches, setRecentSearches] = useState<string[]>([]);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);
  const [searchCollectionIds, setSearchCollectionIds] = useState<string[]>([]);
  const [searching, setSearching] = useState(false);
  const [results, setResults] = useState<SearchResultItem[] | null>(null);
  const [latencyMs, setLatencyMs] = useState<number | null>(null);
  // The collection scope picker is one row per tenant collection - unbounded,
  // so it gets the same shared client-side pagination as the sidebar list.
  const scopePagination = usePagination(collections);

  function collectionName(id: string) {
    return collections.find((c) => c.id === id)?.name || t("unknownCollectionLabel");
  }

  function toggleSearchCollection(id: string) {
    setSearchCollectionIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }

  // Search history lives in this browser only; storage can throw (private mode), so it is best-effort.
  useEffect(() => {
    try {
      setRecentSearches(parseRecentSearches(window.localStorage.getItem(RECENT_SEARCHES_KEY)));
    } catch {
      /* no history */
    }
  }, []);

  function saveRecent(next: string[]) {
    setRecentSearches(next);
    try {
      window.localStorage.setItem(RECENT_SEARCHES_KEY, JSON.stringify(next));
    } catch {
      /* kept in memory only */
    }
  }

  async function copyText(key: string, text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedKey(key);
      window.setTimeout(() => setCopiedKey((k) => (k === key ? null : k)), 1500);
    } catch {
      /* clipboard blocked: the text is still on screen */
    }
  }

  function handleSearch(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    void runSearch(query);
  }

  async function runSearch(text: string) {
    onError(null);
    if (!processingAvailable) return; // sidecar down → search would just fail
    if (!text.trim()) return;
    setSearching(true);
    const startedAt = performance.now();
    try {
      const found = await api.searchKnowledge(text, 6, searchCollectionIds);
      saveRecent(pushRecentSearch(recentSearches, text));
      // Real, client-measured round-trip time - the backend response has no
      // latency field (confirmed absent from SearchResultResponse), so this
      // is timed here rather than shown as a server-reported number.
      setLatencyMs(Math.round(performance.now() - startedAt));
      setResults(found as SearchResultItem[]);
    } catch (err) {
      onError(errorMessage(err, t("searchFailedError")));
    } finally {
      setSearching(false);
    }
  }

  // Cosine distance (lower = closer) turned into a 0-1 "relevance" for the
  // mockup's score-bar visual - a direct, documented transform of the real
  // `distance` field, not a separate fabricated metric.
  function relevanceOf(distance: number) {
    return Math.max(0, Math.min(1, 1 - distance));
  }

  const matchedCollectionIds = useMemo(
    () => (results ? Array.from(new Set(results.map((r: SearchResultItem) => r.collectionId).filter(Boolean))) : []),
    [results]
  );
  const contextAgents = useMemo(() => {
    if (matchedCollectionIds.length === 0) return [];
    return agents.filter(
      (a) =>
        !a.allowedKnowledgeCollectionIds ||
        a.allowedKnowledgeCollectionIds.length === 0 ||
        matchedCollectionIds.some((id) => a.allowedKnowledgeCollectionIds.includes(id))
    );
  }, [agents, matchedCollectionIds]);

  return (
    <Card className="gap-0 p-5 lg:sticky lg:top-24 lg:self-start">
      <div className="space-y-3">
        <div className="flex items-center gap-2">
          <span className="size-2 rounded-full bg-primary" />
          <span className="text-sm font-semibold text-foreground">{t("semanticSearchTitle")}</span>
          <span className="text-xs text-muted-foreground">{t("testRetrievalLabel")}</span>
        </div>
        <form onSubmit={handleSearch}>
          <div className="relative">
            <Search
              aria-hidden="true"
              size={14}
              className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
            />
            <Input
              type="text"
              placeholder={t("searchPlaceholder")}
              aria-label={t("searchPlaceholder")}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              className="ps-9 pe-14"
              disabled={sidecarDown}
            />
            <Button
              type="submit"
              size="sm"
              className="absolute end-1.5 top-1/2 h-7 -translate-y-1/2 px-2.5"
              disabled={searching || sidecarDown || !query.trim()}
            >
              {searching ? <Loader2 aria-hidden="true" size={12} className="animate-spin" /> : t("goButton")}
            </Button>
          </div>
        </form>
        {sidecarDown && (
          <p className="text-xs text-[var(--ax-amber)]">Search is paused while the indexing service reconnects.</p>
        )}
        {recentSearches.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5" aria-label="Recent searches" role="group">
            <History size={12} className="text-muted-foreground" aria-hidden="true" />
            {recentSearches.map((q) => (
              <button
                key={q}
                type="button"
                disabled={sidecarDown || searching}
                className="max-w-[10rem] truncate rounded-full border border-border px-2.5 py-0.5 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:opacity-50"
                title={q}
                onClick={() => {
                  setQuery(q);
                  void runSearch(q);
                }}
              >
                {q}
              </button>
            ))}
            <button
              type="button"
              className="text-xs text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
              onClick={() => saveRecent([])}
            >
              Clear
            </button>
          </div>
        )}
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span>
            {t("topKLabel")} <strong className="text-foreground tabular-nums">6</strong>
          </span>
          {latencyMs != null && (
            <>
              <span>·</span>
              <span className="text-[var(--state-success)] tabular-nums">{latencyMs}ms</span>
            </>
          )}
        </div>
        {collections.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {scopePagination.pageItems.map((c: KnowledgeCollectionSummary) => (
              <button
                key={c.id}
                type="button"
                aria-pressed={searchCollectionIds.includes(c.id)}
                className={cn(
                  "rounded-full border px-2.5 py-0.5 text-xs font-medium transition-colors",
                  searchCollectionIds.includes(c.id)
                    ? "border-primary/30 bg-primary/10 text-primary"
                    : "border-border text-muted-foreground hover:bg-accent hover:text-foreground"
                )}
                onClick={() => toggleSearchCollection(c.id)}
              >
                {c.name}
              </button>
            ))}
          </div>
        )}
        <PaginationControls
          page={scopePagination.page}
          totalPages={scopePagination.totalPages}
          totalItems={scopePagination.totalItems}
          pageSize={scopePagination.pageSize}
          onPageChange={scopePagination.setPage}
          onPageSizeChange={scopePagination.setPageSize}
        />
      </div>

      <Separator className="my-4" />

      <div className="space-y-3">
        <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {t("matchedChunksLabel")}
        </div>

        {results === null && <div className="text-sm text-muted-foreground">{t("runQueryEmptyState")}</div>}
        {results && results.length === 0 && <div className="text-sm text-muted-foreground">{t("noResultsLabel")}</div>}

        {results &&
          results.map((r: SearchResultItem, i: number) => {
            const score = relevanceOf(r.distance);
            // Decorative bar fill stays on the accent palette; the adjacent
            // NUMBER is real text and needs the AA-checked state token
            // instead of --ax-green (audit: --ax-green measures ~3.4:1 as
            // text on a light card).
            const scoreColor = i === 0 ? "var(--ax-green)" : "var(--ax-cyan)";
            const scoreTextColor = i === 0 ? "var(--state-success)" : "var(--ax-cyan)";
            return (
              <div
                key={r.id}
                className={cn(
                  "rounded-lg border p-3",
                  i === 0 ? "border-primary/30 bg-accent/40" : "border-border bg-card"
                )}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate text-xs font-medium text-muted-foreground">
                    {t("chunkSourceLabel", { name: collectionName(r.collectionId), index: r.chunkIndex })}
                  </span>
                  <span className="flex shrink-0 items-center gap-1.5">
                    <button
                      type="button"
                      className="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-secondary hover:text-foreground"
                      aria-label="Copy this passage"
                      title="Copy this passage"
                      onClick={() => copyText(r.id, r.content)}
                    >
                      {copiedKey === r.id ? (
                        <Check aria-hidden="true" size={12} />
                      ) : (
                        <Copy aria-hidden="true" size={12} />
                      )}
                    </button>
                    <span className="h-1.5 w-16 overflow-hidden rounded-full bg-secondary">
                      <span
                        className="block h-full rounded-full"
                        style={{ width: `${Math.round(score * 100)}%`, background: scoreColor }}
                      />
                    </span>
                    <span className="text-xs font-semibold tabular-nums" style={{ color: scoreTextColor }}>
                      {score.toFixed(2)}
                    </span>
                  </span>
                </div>
                <div className="mt-2 text-sm leading-relaxed text-muted-foreground">
                  &quot;{highlight(r.content, query)}&quot;
                </div>
              </div>
            );
          })}

        {results && results.length > 0 && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="w-full gap-1.5"
            onClick={() =>
              copyText(
                "all",
                chunksAsContext(
                  results.map((x: SearchResultItem) => ({
                    content: x.content,
                    source: t("chunkSourceLabel", { name: collectionName(x.collectionId), index: x.chunkIndex }),
                  }))
                )
              )
            }
          >
            {copiedKey === "all" ? <Check aria-hidden="true" size={12} /> : <Copy aria-hidden="true" size={12} />}
            {copiedKey === "all" ? "Copied" : "Copy all passages as context"}
          </Button>
        )}

        {results && results.length > 0 && (
          <div className="rounded-lg border border-dashed border-border bg-card/40 p-3 text-xs text-muted-foreground">
            {t("contextNotePrefix", { count: results.length, plural: results.length === 1 ? "" : "s" })}{" "}
            {contextAgents.length === 0 ? (
              t("contextNoteAgentFallback")
            ) : contextAgents.length === 1 ? (
              <strong className="text-foreground">{contextAgents[0].name}</strong>
            ) : (
              <strong className="text-foreground">
                {t("contextNoteMultipleAgents", {
                  count: contextAgents.length,
                  names: contextAgents.map((a) => a.name).join(", "),
                })}
              </strong>
            )}{" "}
            {t("contextNoteSuffix")}
          </div>
        )}
      </div>
    </Card>
  );
}
