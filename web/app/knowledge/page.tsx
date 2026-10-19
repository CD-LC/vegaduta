"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../lib/api";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import KnowledgeCollectionsPanel from "../components/KnowledgeCollectionsPanel";
import KnowledgePanel from "../components/KnowledgePanel";
import { Upload, Plus, AlertTriangle } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { SetupHint } from "../components/setup/SetupLinks";
import { Button } from "../components/ui/button";
import { Alert, AlertDescription, AlertTitle } from "../components/ui/alert";
import { useSystemHealth } from "../components/SystemHealthProvider";
import { usePageReady } from "../components/RouteTransition";

/**
 * Knowledge Base - rebuilt this session (task #471) to pixel-match the
 * AXON design handoff (Knowledge.dc.html): a page-owned action row (real
 * collection count, "+ New collection", "Upload files") above a 3-column
 * body - collections sidebar, selected-collection detail, semantic search
 * test panel. Kept as one page (not split further) on purpose: the
 * collections list is exactly what feeds the detail/search panel's
 * picker, so they stay visually together.
 *
 * The topbar's two action buttons reach into the two child panels via
 * refs (openCreateForm / openUpload) rather than duplicating that state up
 * here, since each panel already owns the form/file-input it needs to
 * reveal - this keeps the page itself a thin layout + data-fetch shell,
 * matching every other AXON screen's split between "page fetches, panel
 * owns its own interaction state."
 */
export default function KnowledgePage() {
  const t = useTranslations("knowledgePage");
  const tp = useTranslations("consolePolish");
  const [collections, setCollections] = useState([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  // "Build the backend to make it real" (task #541) - a real, live
  // GET /api/knowledge/stats total (KnowledgeDocumentService.tenantStats),
  // not the mockup's fabricated ticking "1,906,606 vectors" counter.
  const [stats, setStats] = useState(null);
  // Hold the route-transition cover until the collections list has loaded, so
  // the panels don't flash their empty state first (see usePageReady).
  const [loaded, setLoaded] = useState(false);
  const collectionsRef = useRef(null);
  const knowledgeRef = useRef(null);

  // Knowledge upload (extraction + embedding) and semantic search both run
  // through the Python sidecar. When a health probe confirms it's down, gate
  // those controls off with an explanation instead of letting the user submit
  // and hit a failure. sidecarAvailable is optimistic (true until a probe
  // proves otherwise), so this is a no-op in the healthy/default case.
  const { sidecarAvailable } = useSystemHealth();
  const sidecarDown = !sidecarAvailable;

  // "Couldn't load" is NOT "you have nothing" (UX audit, 2026-07-29). The
  // catch below used to only `setCollections([])`, which made a failed
  // GET /api/knowledge/collections indistinguishable from a genuinely empty
  // knowledge base: the header reported "0 collections" as fact and the
  // panels below rendered their create-your-first empty states. On this
  // screen in particular that reads as data loss, which is the single worst
  // thing an empty state can imply about a document store. The banner this
  // flag drives says what actually happened and offers a retry.
  const [collectionsFailed, setCollectionsFailed] = useState(false);

  const refresh = useCallback(() => {
    api
      .listCollections()
      .then((list) => {
        setCollectionsFailed(false);
        setCollections(list);
        setSelectedId((current: string | null) => {
          if (current && list.some((c: { id: string }) => c.id === current)) return current;
          return list[0]?.id || null;
        });
      })
      .catch(() => {
        setCollections([]);
        setCollectionsFailed(true);
      })
      .finally(() => setLoaded(true));
    api.getKnowledgeStats().then(setStats).catch(() => setStats(null));
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);
  usePageReady(loaded);

  return (
    <AppShell eyebrow={t("buildEyebrow")} wide>
      <PageHeader
        title={t("knowledgeLabel")}
        subtitle={
          collectionsFailed ? (
            // Don't assert "0 collections" when the number is simply unknown -
            // the banner below explains, this just stops the header stating a
            // fabricated count as fact.
            <>
              {tp("knowledgeIntro")} <span className="text-muted-foreground">{tp("knowledgeCountUnavailable")}</span>
            </>
          ) : (
          <>
            {tp("knowledgeIntro")}{" "}
            <span className="font-medium text-foreground tabular-nums">{collections.length}</span>{" "}
            {t("collectionCountLabel", { plural: collections.length === 1 ? "" : "s" })}
            {stats != null && (
              <>
                {" · "}
                <span className="font-medium text-foreground tabular-nums">{stats.totalVectors.toLocaleString()}</span>{" "}
                {t("vectorCountLabel", { plural: stats.totalVectors === 1 ? "" : "s" })}
              </>
            )}
          </>
          )
        }
        actions={
          <>
            <Button variant="outline" size="sm" data-tour="knowledge-toggle" onClick={() => collectionsRef.current?.openCreateForm()}>
              <Plus size={14} /> {t("newCollectionButton")}
            </Button>
            <Button size="sm" onClick={() => knowledgeRef.current?.openUpload()} disabled={!selectedId || sidecarDown}>
              <Upload size={14} /> {t("uploadFilesButton")}
            </Button>
          </>
        }
      />
      <SetupHint className="mb-4" lead={tp("knowledgeSetupLead")} targets={["agents", "workflows"]} />

      {/* Load failure, stated as a load failure. Deliberately rendered ABOVE
          the sidecar-down notice and the panels themselves: if we couldn't
          read the collection list, nothing below it can be trusted as a
          picture of this workspace, so the person needs to know that before
          they read a single empty panel. */}
      {collectionsFailed && (
        <Alert variant="destructive" className="mb-6">
          <AlertTriangle />
          <AlertTitle>{tp("knowledgeLoadFailedTitle")}</AlertTitle>
          <AlertDescription>
            <p>{tp("knowledgeLoadFailedBody")}</p>
            <Button variant="outline" size="sm" className="mt-2" onClick={refresh}>
              {tp("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {sidecarDown && (
        <Alert variant="warning" className="mb-6">
          <AlertTriangle />
          <AlertTitle>{tp("knowledgePausedTitle")}</AlertTitle>
          <AlertDescription>{tp("knowledgePausedBody")}</AlertDescription>
        </Alert>
      )}

      {/* 1 column on phones; collections + detail side by side from md with the search
          panel spanning the row below; all three from xl. */}
      <div className="grid grid-cols-1 gap-6 md:grid-cols-[220px_minmax(0,1fr)] md:[&>*:nth-child(3)]:col-span-2 xl:grid-cols-[240px_minmax(0,1fr)_340px] xl:[&>*:nth-child(3)]:col-span-1">
        <KnowledgeCollectionsPanel ref={collectionsRef} collections={collections} onChanged={refresh} selectedId={selectedId} onSelect={setSelectedId} />
        <KnowledgePanel ref={knowledgeRef} collections={collections} selectedId={selectedId} onDocumentsChanged={refresh} onRequestCreateCollection={() => collectionsRef.current?.openCreateForm()} processingAvailable={!sidecarDown} />
      </div>
    </AppShell>
  );
}
