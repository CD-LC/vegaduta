"use client";

// Code search tab: result count, a top-k picker (the service returns at most top_k hits), paging and an empty state.
import { useState } from "react";
import { Loader2, Search as SearchIcon } from "lucide-react";
import { EmptyState } from "../components/ax/page-kit";
import PaginationControls from "../components/PaginationControls";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { useTranslations } from "../lib/i18n";
import { usePagination } from "../lib/usePagination";
import { codeIntel } from "./lib";

const CODE_CLS =
  "m-0 overflow-auto whitespace-pre-wrap break-words rounded-lg border border-border bg-muted/50 p-3 font-mono text-xs leading-relaxed text-foreground";
const HIT_CLS = "mb-3 rounded-xl border border-border bg-card p-4 shadow-sm";
const TOP_K = [8, 20, 50] as const;

interface Hit {
  path: string;
  start_line: number;
  end_line: number;
  symbol?: string;
  score?: number;
  content?: string;
}

export default function SearchTab({ repoId, onError }: { repoId: string; onError: (message: string) => void }) {
  const t = useTranslations("codeIntel");
  const ts = useTranslations("codeIntelSearch");
  const [q, setQ] = useState("");
  const [topK, setTopK] = useState<number>(TOP_K[0]);
  const [hits, setHits] = useState<Hit[]>([]);
  const [searched, setSearched] = useState(false);
  const [busy, setBusy] = useState(false);
  const pg = usePagination(hits, 5);

  const run = async () => {
    if (!repoId || !q) return;
    setBusy(true);
    try {
      setHits((await codeIntel.search(repoId, q, topK)) as Hit[]);
      setSearched(true);
    } catch (e) {
      onError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end gap-2">
        <Input
          className="min-w-0 flex-1"
          aria-label={t("searchButton")}
          placeholder={repoId ? t("searchPlaceholder") : t("selectRepoFirst")}
          value={q}
          disabled={!repoId}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && run()}
        />
        <div className="space-y-1">
          <Label htmlFor="ci-topk" className="text-xs">
            {ts("topK")}
          </Label>
          <select
            id="ci-topk"
            value={topK}
            onChange={(e) => setTopK(Number(e.target.value))}
            className="h-9 rounded-md border border-border bg-card px-2 text-sm"
          >
            {TOP_K.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </div>
        <Button size="sm" disabled={busy || !repoId || !q} onClick={run}>
          {busy ? <Loader2 size={15} className="animate-spin" /> : <SearchIcon size={15} />} {t("searchButton")}
        </Button>
      </div>
      {searched && hits.length === 0 && (
        <EmptyState icon={SearchIcon} title={ts("noResultsTitle")} description={ts("noResultsBody")} />
      )}
      {hits.length > 0 && (
        <p className="mb-3 text-sm text-muted-foreground" data-testid="ci-search-count">
          {hits.length >= topK ? ts("countCapped", { count: hits.length }) : ts("count", { count: hits.length })}
        </p>
      )}
      {pg.pageItems.map((h, i) => (
        <div key={`${h.path}:${h.start_line}:${i}`} className={HIT_CLS}>
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <span className="break-all font-mono text-xs text-primary">
              {h.path}:{h.start_line}-{h.end_line}
            </span>
            {h.symbol && <Badge variant="secondary">{h.symbol}</Badge>}
            <span className="ms-auto font-mono text-xs text-muted-foreground">{(h.score ?? 0).toFixed(3)}</span>
          </div>
          <pre className={CODE_CLS}>{(h.content || "").split("\n").slice(0, 12).join("\n")}</pre>
        </div>
      ))}
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        pageSizeOptions={[5, 10, 25]}
        itemLabel={ts("hitsLabel")}
      />
    </div>
  );
}
