"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Download, Files as FilesIcon, Loader2, Search } from "lucide-react";
import { api } from "../lib/api";
import { cloudSpeechOffered } from "../lib/cloudSpeech";
import { usePagination } from "../lib/usePagination";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { EmptyState, Section } from "../components/ax/page-kit";
import { Card, CardContent } from "../components/ui/card";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { usePageReady } from "../components/RouteTransition";
import Select from "../components/Select";
import LoadMoreBar from "../components/LoadMoreBar";
import PaginationControls from "../components/PaginationControls";
import { ListSkeleton } from "../components/tenantLists/ListStates";
import { useTranslations } from "../lib/i18n";
import FilesAlerts from "./FilesAlerts";
import FilesTable from "./FilesTable";
import FilePreviewDialog, { type FilePreview } from "./FilePreviewDialog";
import {
  ARTIFACT_LIST_CAP,
  PAGE_SIZE,
  fetchRowBlob,
  normalizeDocument,
  normalizeElevenLabsAudio,
  normalizeImage,
  normalizeKnowledgeDoc,
  normalizeSarvamAudio,
  normalizeVideoJob,
  saveBlob,
  type FileRow,
} from "./fileRows";
import { GROUPS, SORTS, countByGroup, filterRows, previewKind, rowKey, sortRows, type FileGroup, type FileSort } from "./filesLogic";

/**
 * A single place to see, search, and download generated files.
 *
 *  - Knowledge documents have a real per-collection list endpoint; this page aggregates across every collection.
 *  - Generated images, ElevenLabs/Sarvam speech, generated video and generated documents each have a list endpoint.
 *
 * All six sources normalize into one FileRow list (fileRows.ts), merged and sorted client-side, and shown in one
 * searchable table (FilesTable.tsx) with the shared pager. Images, speech and video cannot be paged by the API,
 * so they are fetched whole and paged here; FilesAlerts tells the operator when one of those lists hit its cap.
 */
export default function FilesPage() {
  const t = useTranslations("filesPage");
  const [rows, setRows] = useState<any[]>([]);
  const [loaded, setLoaded] = useState(false);
  // "Couldn't load" must never look like "there's nothing here". Two failure shapes are tracked: the collection
  // list itself failing (nothing below can be trusted) vs. individual collections failing (partial table).
  const [loadFailed, setLoadFailed] = useState(false);
  const [partialFailureCount, setPartialFailureCount] = useState(0);

  // Artifacts (images/speech/video/documents): same two failure shapes, tracked separately since they load from
  // different endpoint groups and one failing must not blank out the other.
  const [artifactRows, setArtifactRows] = useState<FileRow[]>([]);
  const [artifactsLoaded, setArtifactsLoaded] = useState(false);
  const [artifactsFailed, setArtifactsFailed] = useState(false);
  const [artifactsCapped, setArtifactsCapped] = useState(false);
  const tf = useTranslations("tenantFiles");
  const [docsCapped, setDocsCapped] = useState<{ shown: number; total: number } | null>(null);
  const [artifactPartialFailureCount, setArtifactPartialFailureCount] = useState(0);

  const [downloadingKey, setDownloadingKey] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const [searchText, setSearchText] = useState("");
  const [group, setGroup] = useState<FileGroup>("all");
  const [sort, setSort] = useState<FileSort>("newest");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkBusy, setBulkBusy] = useState(false);
  const [preview, setPreview] = useState<FilePreview | null>(null);
  const [previewBusyKey, setPreviewBusyKey] = useState<string | null>(null);

  // Collections whose file table was cut by the server cap (newest first). "Load more" pages each of them forward
  // with offset. The offset is the number of rows of that collection CURRENTLY held (rowsRef), not a running
  // counter: a local delete shifts the server's rows up too, so a counter would skip documents. `failed` = last
  // page errored or made no progress; the auto-load effect skips those (no request loop) while the button retries.
  const [moreDocs, setMoreDocs] = useState<{ id: string; name: string; total: number | null; failed?: boolean }[]>([]);
  const [moreDocsBusy, setMoreDocsBusy] = useState(false);
  const rowsRef = useRef<any[]>([]);
  rowsRef.current = rows;

  const loadMoreDocs = useCallback(
    async (manual = true) => {
      const targets = manual ? moreDocs : moreDocs.filter((c) => !c.failed);
      if (targets.length === 0) return;
      setMoreDocsBusy(true);
      try {
        const results = await Promise.allSettled(
          targets.map((c) => {
            const held = rowsRef.current.filter((d: any) => d.collectionId === c.id);
            return api.listKnowledgeDocumentsPage(c.id, held.length, 500).then((page: any) => ({ c, page, held }));
          })
        );
        const fresh: any[] = [];
        const next = new Map(moreDocs.map((c) => [c.id, c] as const));
        for (const [i, r] of results.entries()) {
          const c = targets[i];
          if (r.status === "rejected") {
            next.set(c.id, { ...c, failed: true });
            continue;
          }
          const { page, held } = r.value as any;
          const have = new Set(held.map((d: any) => d.id));
          const add = page.rows
            .filter((d: any) => !have.has(d.id))
            .map((d: any) => ({ ...d, collectionId: c.id, collectionName: c.name }));
          fresh.push(...add);
          if (!page.hasMore) next.delete(c.id);
          else next.set(c.id, { ...c, total: page.total, failed: add.length === 0 });
        }
        setRows((prev: any[]) => {
          const seen = new Set(prev.map((d: any) => d.id));
          return [...prev, ...fresh.filter((d) => !seen.has(d.id))];
        });
        setMoreDocs([...next.values()]);
      } finally {
        setMoreDocsBusy(false);
      }
    },
    [moreDocs]
  );

  const refresh = useCallback(() => {
    setLoaded(false);
    setLoadFailed(false);
    setPartialFailureCount(0);
    api
      .listCollections()
      .then(async (collections: any[]) => {
        const results = await Promise.allSettled(
          (collections || []).map((c: any) =>
            api.listKnowledgeDocumentsPage(c.id, 0, 500).then((page: any) => ({
              c,
              page,
              docs: (page.rows || []).map((d: any) => ({ ...d, collectionId: c.id, collectionName: c.name })),
            }))
          )
        );
        const failed = results.filter((r) => r.status === "rejected").length;
        const ok = results.filter((r) => r.status === "fulfilled").map((r) => (r as PromiseFulfilledResult<any>).value);
        const docs = ok.flatMap((v: any) => v.docs);
        setMoreDocs(ok.filter((v: any) => v.page.hasMore).map((v: any) => ({ id: v.c.id, name: v.c.name, total: v.page.total })));
        setRows(docs);
        setPartialFailureCount(failed);
      })
      .catch(() => {
        setRows([]);
        setLoadFailed(true);
      })
      .finally(() => setLoaded(true));
  }, []);

  const refreshArtifacts = useCallback(() => {
    setArtifactsLoaded(false);
    setArtifactsFailed(false);
    setArtifactsCapped(false);
    setArtifactPartialFailureCount(0);
    // The unpaged lists (images, speech, video) are flagged when one reaches the cap: it may have been truncated.
    const speech = cloudSpeechOffered();
    const capSeen = { value: false };
    const noteCap = (list: any[]) => {
      if (list.length >= ARTIFACT_LIST_CAP) capSeen.value = true;
      return list;
    };
    Promise.allSettled([
      api.listGeneratedImages().then((r: any) => noteCap(r || []).map((x: any) => normalizeImage(x, t))),
      // The two speech listings belong to core's CLOUD_SPEECH feature. Where it is off (a personal install) the
      // routes do not exist, and a 404 here would show the partial-failure banner for a list nobody has.
      ...(speech
        ? [
            api.listElevenLabsAudio().then((r: any) => noteCap(r || []).map((x: any) => normalizeElevenLabsAudio(x, t))),
            api.listSarvamAudio().then((r: any) => noteCap(r || []).map((x: any) => normalizeSarvamAudio(x, t))),
          ]
        : []),
      api.listVideoGenerationJobs().then((r: any) => noteCap(r || []).map((x: any) => normalizeVideoJob(x, t))),
      api.listGeneratedDocuments({ page: 0, size: 200 }).then((r: any) => {
        const shown = (r?.content || []).length;
        setDocsCapped(typeof r?.totalElements === "number" && r.totalElements > shown ? { shown, total: r.totalElements } : null);
        return (r?.content || []).map((x: any) => normalizeDocument(x, t));
      }),
    ])
      .then((results) => {
        const failed = results.filter((r) => r.status === "rejected").length;
        const merged = results
          .filter((r) => r.status === "fulfilled")
          .flatMap((r) => (r as PromiseFulfilledResult<any>).value);
        setArtifactRows(merged);
        setArtifactsCapped(capSeen.value);
        setArtifactPartialFailureCount(failed);
        // Every list failing at once reads as a real outage, not a partial gap.
        if (failed === results.length) setArtifactsFailed(true);
      })
      .catch(() => {
        setArtifactRows([]);
        setArtifactsFailed(true);
      })
      .finally(() => setArtifactsLoaded(true));
  }, [t]);

  useEffect(() => {
    refresh();
    refreshArtifacts();
  }, [refresh, refreshArtifacts]);
  usePageReady(loaded && artifactsLoaded);

  // Merge + sort every source into one row list, filter by the search box, then paginate the FILTERED list.
  const combinedRows: FileRow[] = useMemo(() => {
    const merged = [...rows.map(normalizeKnowledgeDoc), ...artifactRows];
    merged.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
    return merged;
  }, [rows, artifactRows]);

  const groupCounts = useMemo(() => countByGroup(combinedRows), [combinedRows]);

  // Search/filter run client-side over loaded rows: while one is active keep paging older files in.
  useEffect(() => {
    if ((searchText || group !== "all") && moreDocs.some((c) => !c.failed) && !moreDocsBusy) void loadMoreDocs(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchText, group, moreDocs, moreDocsBusy]);

  const filteredRows = useMemo(() => sortRows(filterRows(combinedRows, group, searchText), sort), [combinedRows, group, searchText, sort]);

  const { page, pageItems, totalItems, totalPages, pageSize, setPage, setPageSize } = usePagination(filteredRows, PAGE_SIZE);

  // Reset to page 1 whenever the search text changes, so a narrower result set never strands the view on a blank page.
  useEffect(() => {
    setPage(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchText, group, sort]);

  // Drop selections whose row no longer exists (after a refresh).
  useEffect(() => {
    setSelected((prev) => {
      if (prev.size === 0) return prev;
      const live = new Set(combinedRows.map(rowKey));
      const next = new Set([...prev].filter((k) => live.has(k)));
      return next.size === prev.size ? prev : next;
    });
  }, [combinedRows]);

  // Release the preview's blob URL whenever it is replaced or closed.
  useEffect(() => {
    return () => {
      if (preview) URL.revokeObjectURL(preview.url);
    };
  }, [preview]);

  async function handlePreview(row: FileRow) {
    const kind = previewKind(row.kind);
    if (!kind) return;
    setDownloadError(null);
    const key = rowKey(row);
    setPreviewBusyKey(key);
    try {
      const { blob } = await fetchRowBlob(row);
      setPreview({ row, kind, url: URL.createObjectURL(blob) });
    } catch (err: any) {
      setDownloadError(err?.message || t("previewFailed"));
    } finally {
      setPreviewBusyKey(null);
    }
  }

  const selectedRows = useMemo(() => combinedRows.filter((r) => selected.has(rowKey(r)) && r.downloadable), [combinedRows, selected]);

  async function handleBulkDownload() {
    setDownloadError(null);
    setBulkBusy(true);
    let failed = 0;
    for (const row of selectedRows) {
      try {
        const { blob, filename } = await fetchRowBlob(row);
        saveBlob(blob, filename || row.filenameFallback);
        // Browsers drop back-to-back programmatic downloads; a short gap keeps them all.
        await new Promise((res) => setTimeout(res, 300));
      } catch {
        failed++;
      }
    }
    setBulkBusy(false);
    if (failed > 0) setDownloadError(t("bulkFailed", { failed, total: selectedRows.length }));
    else setSelected(new Set());
  }

  async function handleDownload(row: FileRow) {
    setDownloadError(null);
    const key = rowKey(row);
    setDownloadingKey(key);
    try {
      const { blob, filename } = await fetchRowBlob(row);
      saveBlob(blob, filename || row.filenameFallback);
    } catch (err: any) {
      setDownloadError(err?.message || t("downloadFailed"));
    } finally {
      setDownloadingKey(null);
    }
  }

  const stillLoading = !loaded || !artifactsLoaded;
  const totalOutage = loadFailed && artifactsFailed;

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader icon={FilesIcon} title={t("title")} subtitle={t("subtitle")} />

      <FilesAlerts
        loadFailed={loadFailed}
        partialFailureCount={partialFailureCount}
        onRetryDocs={refresh}
        artifactsFailed={artifactsFailed}
        artifactPartialFailureCount={artifactPartialFailureCount}
        onRetryArtifacts={refreshArtifacts}
        docsCapped={docsCapped}
        artifactsCapped={artifactsCapped}
        downloadError={downloadError}
      />

      <Section title={t("sectionTitle")} description={t("sectionDescription")}>
        <Card>
          <CardContent>
            {stillLoading ? (
              <ListSkeleton rows={6} label={tf("loading")} />
            ) : totalOutage ? null : combinedRows.length === 0 ? (
              <EmptyState
                icon={FilesIcon}
                title={t("emptyTitle")}
                description={t("emptyBody")}
                action={
                  <Button asChild variant="outline" size="sm">
                    <Link href="/knowledge">{t("emptyCta")}</Link>
                  </Button>
                }
              />
            ) : (
              <>
                <div className="mb-3 flex flex-wrap items-center gap-2">
                  <div className="relative min-w-0 flex-1 basis-56 sm:max-w-sm">
                    <Search size={14} aria-hidden="true" className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
                    <Input
                      type="text"
                      placeholder={t("searchPlaceholder")}
                      aria-label={t("searchLabel")}
                      className="pl-8"
                      value={searchText}
                      onChange={(e) => setSearchText(e.target.value)}
                    />
                  </div>
                  <Select
                    aria-label={t("sortLabel")}
                    value={sort}
                    onChange={(e: any) => setSort(e.target.value as FileSort)}
                    options={SORTS.map((s) => ({ value: s.value, label: t(`sort_${s.value}`) }))}
                  />
                </div>
                <div role="group" aria-label={t("filterLabel")} className="mb-4 flex flex-wrap gap-1.5">
                  {GROUPS.filter((g) => g.id === "all" || groupCounts[g.id] > 0).map((g) => (
                    <Button
                      key={g.id}
                      type="button"
                      size="sm"
                      variant={group === g.id ? "default" : "outline"}
                      aria-pressed={group === g.id}
                      className="px-2.5"
                      onClick={() => setGroup(g.id)}
                    >
                      {t(`group_${g.id}`)} <span className="tabular-nums opacity-70">{groupCounts[g.id]}</span>
                    </Button>
                  ))}
                </div>
                {selected.size > 0 && (
                  <div className="mb-3 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-muted/40 px-3 py-2" role="status">
                    <span className="text-xs text-foreground">
                      {t("selectedCount", { count: selected.size })}
                      {selectedRows.length < selected.size && ` ${t("downloadableCount", { count: selectedRows.length })}`}
                    </span>
                    <div className="flex gap-2">
                      <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())}>
                        {t("clear")}
                      </Button>
                      <Button size="sm" className="gap-1" disabled={bulkBusy || selectedRows.length === 0} onClick={handleBulkDownload}>
                        {bulkBusy ? <Loader2 size={12} className="animate-spin" aria-hidden="true" /> : <Download size={12} aria-hidden="true" />}
                        {t("downloadSelected")}
                      </Button>
                    </div>
                  </div>
                )}

                {filteredRows.length === 0 ? (
                  <p className="py-6 text-sm text-muted-foreground">{t("noMatch")}</p>
                ) : (
                  <>
                    <FilesTable
                      rows={pageItems}
                      selected={selected}
                      onSelectedChange={(updater) => setSelected(updater)}
                      downloadingKey={downloadingKey}
                      previewBusyKey={previewBusyKey}
                      onDownload={handleDownload}
                      onPreview={handlePreview}
                    />

                    <LoadMoreBar
                      loaded={rows.length}
                      total={
                        moreDocs.length === 0 || moreDocs.some((c) => c.total == null)
                          ? null
                          : rows.length +
                            moreDocs.reduce(
                              (n, c) => n + Math.max((c.total as number) - rows.filter((d: any) => d.collectionId === c.id).length, 0),
                              0
                            )
                      }
                      hasMore={moreDocs.length > 0}
                      loading={moreDocsBusy}
                      onLoadMore={() => void loadMoreDocs(true)}
                      itemLabel={t("itemLabel")}
                    />
                    <PaginationControls
                      page={page}
                      totalPages={totalPages}
                      totalItems={totalItems}
                      pageSize={pageSize}
                      onPageChange={setPage}
                      onPageSizeChange={setPageSize}
                      itemLabel={t("itemLabel")}
                    />
                  </>
                )}
              </>
            )}
          </CardContent>
        </Card>
      </Section>

      <FilePreviewDialog preview={preview} onClose={() => setPreview(null)} />
    </AppShell>
  );
}
