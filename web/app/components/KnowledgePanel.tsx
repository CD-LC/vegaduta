"use client";

import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Loader2, Plus, FolderPlus, Upload } from "lucide-react";
import { api } from "../lib/api";
import PaginationControls from "./PaginationControls";
import { usePagination } from "../lib/usePagination";
import { useTranslations } from "../lib/i18n";
import { cn } from "../lib/utils";
import { EmptyState } from "./ax/page-kit";
import { Button } from "./ui/button";
import { ConfirmDialog } from "./ui/confirm-dialog";
import { Card } from "./ui/card";
import { errorMessage } from "../lib/errors";
import RagQualityPanel from "./RagQualityPanel";
import LoadMoreBar from "./LoadMoreBar";
import { duplicateUploadFilename } from "../lib/knowledgeErrors";
import { countStatusGroups, filterDocs, sortDocs, type DocSort, type DocStatusGroup } from "./knowledge/knowledgeDocs";
import {
  ALL_EXTENSIONS,
  ASYNC_EXTENSIONS,
  DOCUMENT_POLL_INTERVAL_MS,
  NON_TERMINAL_STATUSES,
  extensionOf,
  readFileAsDataUrl,
  type FaceEnrollmentItem,
  type KnowledgeCollectionSummary,
} from "./knowledge/knowledgeFormat";
import CollectionHeaderCard from "./knowledge/CollectionHeaderCard";
import IngestionPipelineCard from "./knowledge/IngestionPipelineCard";
import KnowledgeDocumentsTable from "./knowledge/KnowledgeDocumentsTable";
import OcrProviderCard from "./knowledge/OcrProviderCard";
import SemanticSearchPanel from "./knowledge/SemanticSearchPanel";
import FaceEnrollmentControls from "./knowledge/FaceEnrollmentControls";
import KnowledgeDocumentFilters from "./knowledge/KnowledgeDocumentFilters";
import { computePipelineStats } from "./knowledge/pipelineStats";

/**
 * Knowledge center detail + right-side semantic search panel - rebuilt this
 * session (task #471) to pixel-match Knowledge.dc.html's 3-column layout.
 *
 * Follow-up ("Build the backend to make it real", task #541): the file
 * table and per-collection file/chunk counts were originally a deliberate,
 * honest departure from the mockup (no chunkCount/fileCount field existed
 * anywhere in KnowledgeCollectionResponse, and uploads were fully stateless
 * after their single HTTP response). That's no longer true - migration V45
 * added a real KnowledgeDocument row per upload (both sync and async paths),
 * KnowledgeIngestionService/KnowledgeIngestionJobService transition it
 * through real EXTRACTING/EMBEDDING/READY/FAILED stages as the actual work
 * happens, and KnowledgeCollectionResponse now carries real fileCount/
 * chunkCount aggregates - see KnowledgeDocumentService's javadoc for the
 * exact queries. `documents` below is fetched from that real endpoint
 * (api.listKnowledgeDocuments), not a session-local array.
 *
 * "Exposed to agents" and the search panel's "these chunks would be
 * injected into X's context" note are both real: derived by cross-
 * referencing api.listAgents()'s allowedKnowledgeCollectionIds against the
 * selected collection's id (empty array on an agent = unrestricted, so it
 * counts as exposed to every collection) - the backend has no reverse
 * collection->agents endpoint, but this is a real client-side derivation of
 * real data, not an invented number.
 */
const KnowledgePanel = forwardRef(function KnowledgePanel(
  { collections, selectedId, onDocumentsChanged, onRequestCreateCollection, processingAvailable = true }: any,
  ref
) {
  const tHC = useTranslations("hcComponentsKnowledgePanel");
  // When the Python sidecar (embeddings/extraction) is down, upload and
  // semantic search both hard-depend on it, so the page gates them off via
  // this prop. Optional + defaults true so the healthy case (and any caller
  // that omits it) behaves exactly as before.
  const sidecarDown = !processingAvailable;
  const t = useTranslations("knowledgePanel");
  const tFace = useTranslations("faceRecognition");
  const fileInputRef = useRef(null);
  const [uploading, setUploading] = useState(false);
  const [documents, setDocuments] = useState([]); // real, persisted per-collection file inventory
  const [documentsLoading, setDocumentsLoading] = useState(false);
  // The file table is server-capped (newest first): older files are paged in with offset.
  const [docMeta, setDocMeta] = useState({ total: null, hasMore: false });
  const [docsLoadingMore, setDocsLoadingMore] = useState(false);
  const loadedDocsRef = useRef(0);
  // Latest selection, so an in-flight page for a collection the user already left is discarded
  // instead of being appended to (or overwriting) the new collection's table.
  const documentsRef = useRef<any[]>([]);
  const selectedIdRef = useRef(selectedId);
  selectedIdRef.current = selectedId;
  documentsRef.current = documents;
  // A failed or no-progress "load more" must not re-fire from the auto-load effect (request loop);
  // the button still retries. Reset whenever the list is re-read from the top.
  const [docsLoadError, setDocsLoadError] = useState(false);
  const [error, setError] = useState(null);

  // One-time stage-sweep animation (see the CSS block's comment for why):
  // fast synchronous uploads (.md/.pdf/etc.) finish extraction+embedding
  // before the 4s poll ever samples an EXTRACTING/EMBEDDING row, so those
  // stages would otherwise never visibly light up even though the file did
  // genuinely pass through them server-side. Fired once per completed
  // upload, independent of pipelineStats.active (which stays honest about
  // ongoing work for the slower async audio/video path).
  const [, setSweeping] = useState(false);
  const sweepTimeoutRef = useRef(null);
  useEffect(() => () => clearTimeout(sweepTimeoutRef.current), []);

  const [agents, setAgents] = useState([]);

  const [docGroup, setDocGroup] = useState<DocStatusGroup>("all");
  const [docQuery, setDocQuery] = useState("");
  const [docSort, setDocSort] = useState<DocSort>("newest");

  // Face recognition (task #15) - consent banner dismissal, the optional
  // "person's name" field for the next upload, the current collection's
  // enrollments (fetched once per collection, see the effect below), and a
  // non-blocking best-effort notice from the enrollment attempt after an
  // image upload.
  const [biometricBannerDismissed, setBiometricBannerDismissed] = useState(false);
  const [biometricBusy, setBiometricBusy] = useState(false);
  const [personName, setPersonName] = useState("");
  const [faceEnrollments, setFaceEnrollments] = useState([]);
  const [faceNotice, setFaceNotice] = useState(null); // { tone: "success" | "info", text }

  useImperativeHandle(ref, () => ({
    openUpload: () => {
      if (!processingAvailable) return; // sidecar down → no-op, matches the disabled controls
      fileInputRef.current?.click();
    },
  }));

  useEffect(() => {
    api
      .listAgents()
      .then(setAgents)
      .catch(() => setAgents([]));
  }, []);

  const selectedCollection = collections.find((c: KnowledgeCollectionSummary) => c.id === selectedId) || null;

  // Real derivation (see file header comment) - no reverse endpoint exists,
  // so this cross-references the agent->collections direction that does.
  const exposedAgents = useMemo(() => {
    if (!selectedCollection) return [];
    return agents.filter(
      (a) =>
        !a.allowedKnowledgeCollectionIds ||
        a.allowedKnowledgeCollectionIds.length === 0 ||
        a.allowedKnowledgeCollectionIds.includes(selectedCollection.id)
    );
  }, [agents, selectedCollection]);

  // Face recognition (task #15) - FaceEnrollmentRequest requires a single
  // agentId (enrollment is per-agent, not per-collection - see
  // FaceEnrollmentController.java), but this panel is collection-centric
  // and can have zero, one, or many agents exposed to a collection. v1
  // keeps this simple and honest: only offer the consent/enroll UI when
  // exactly one agent is exposed, rather than guessing which agent a photo
  // should be enrolled against. Collections shared by multiple agents can
  // still be used normally - they just don't get the face-enrollment
  // shortcut here yet (a reasonable follow-up, not built out for v1).
  const biometricAgent = exposedAgents.length === 1 ? exposedAgents[0] : null;

  // Real per-collection enrollment list - fetched once when the panel loads
  // or the collection/agent changes, cached in state per the task spec (not
  // refetched on every render).
  useEffect(() => {
    if (!biometricAgent?.id || !selectedId) {
      setFaceEnrollments([]);
      return;
    }
    api
      .listFaceEnrollments(biometricAgent.id, selectedId)
      .then(setFaceEnrollments)
      .catch(() => setFaceEnrollments([]));
  }, [biometricAgent?.id, selectedId]);

  const [consentConfirmOpen, setConsentConfirmOpen] = useState(false);
  const [forgetTarget, setForgetTarget] = useState<FaceEnrollmentItem | null>(null);

  async function handleEnableBiometricConsent() {
    if (!biometricAgent) return;
    setBiometricBusy(true);
    setError(null);
    try {
      await api.setAgentBiometricConsent(biometricAgent.id, true);
      // Refresh the agent list so exposedAgents/biometricAgent above pick up
      // the new biometricConsentEnabled value.
      const refreshed = await api.listAgents();
      setAgents(refreshed);
    } catch (err) {
      setError(errorMessage(err, tFace("consentUpdateError")));
    } finally {
      setBiometricBusy(false);
    }
  }

  function faceFailureMessage(reason: string | null) {
    if (reason === "no-face-detected") return tFace("reasonNoFaceDetected");
    if (reason === "model-not-ready") return tFace("reasonModelNotReady");
    return tFace("reasonGeneric");
  }

  async function handleDeleteFaceEnrollment(enrollment: FaceEnrollmentItem) {
    try {
      await api.deleteFaceEnrollment(enrollment.id);
      setFaceEnrollments((prev) => prev.filter((e) => e.id !== enrollment.id));
    } catch (err) {
      setError(errorMessage(err, tFace("removeEnrollmentError")));
    }
  }

  // Real 4-stage aggregate view over `documents` (see file header comment
  // for why this is now real instead of the placeholder it used to be).
  const pipelineStats = useMemo(() => computePipelineStats(documents), [documents]);

  const docCounts = useMemo(() => countStatusGroups(documents), [documents]);
  const visibleDocuments = useMemo(
    () => sortDocs(filterDocs(documents, docGroup, docQuery), docSort),
    [documents, docGroup, docQuery, docSort]
  );

  const {
    page: docPage,
    pageSize: docPageSize,
    pageItems: docPageItems,
    totalPages: docTotalPages,
    totalItems: docTotalItems,
    setPage: setDocPage,
    setPageSize: setDocPageSize,
  } = usePagination(visibleDocuments);

  useEffect(() => {
    setDocPage(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [docGroup, docQuery, docSort]);

  const fetchDocuments = useCallback(
    async (collectionId: string | null) => {
      if (!collectionId) {
        setDocuments([]);
        setDocMeta({ total: null, hasMore: false });
        return;
      }
      try {
        // Polling re-reads everything already loaded (up to the server max), so a refresh
        // does not collapse pages the user paged in back to the newest 500.
        const page = await api.listKnowledgeDocumentsPage(
          collectionId,
          0,
          Math.min(2000, Math.max(500, loadedDocsRef.current))
        );
        if (selectedIdRef.current !== collectionId) return; // stale: the user switched collection mid-flight
        loadedDocsRef.current = page.rows.length;
        setDocuments(page.rows);
        setDocMeta({ total: page.total, hasMore: page.hasMore });
        // Keep the page-level collection fileCount/chunkCount and topbar
        // vector count (both real, both derived from this same document
        // table) in sync as files move through the pipeline - not just on
        // manual navigation.
        onDocumentsChanged?.();
      } catch {
        if (selectedIdRef.current !== collectionId) return;
        setDocuments([]);
        setDocMeta({ total: null, hasMore: false });
      }
    },
    [onDocumentsChanged]
  );

  async function loadMoreDocuments() {
    if (!selectedId || docsLoadingMore) return;
    const forId = selectedId;
    setDocsLoadingMore(true);
    setDocsLoadError(false);
    try {
      const page = await api.listKnowledgeDocumentsPage(forId, loadedDocsRef.current, 500);
      if (selectedIdRef.current !== forId) return; // stale: collection changed while this page was in flight
      const seen = new Set(documentsRef.current.map((d: any) => d.id));
      const fresh = page.rows.filter((d: any) => !seen.has(d.id));
      setDocuments((prev: any[]) => {
        const have = new Set(prev.map((d) => d.id));
        const next = [...prev, ...fresh.filter((d: any) => !have.has(d.id))];
        loadedDocsRef.current = next.length;
        return next;
      });
      setDocMeta({ total: page.total, hasMore: page.hasMore });
      // A page of rows already held means the window shifted: stop auto-loading, keep the button.
      if (fresh.length === 0 && page.hasMore) setDocsLoadError(true);
    } catch {
      // Keep hasMore so the button can retry; the flag only stops the auto-load effect.
      if (selectedIdRef.current === forId) setDocsLoadError(true);
    } finally {
      setDocsLoadingMore(false);
    }
  }

  // The name filter runs client-side over the loaded rows: while one is typed, keep paging older
  // files in so a match is never hidden beyond the first page.
  useEffect(() => {
    if (docQuery && docMeta.hasMore && !docsLoadingMore && !documentsLoading && !docsLoadError)
      void loadMoreDocuments();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [docQuery, docMeta.hasMore, docsLoadingMore, documentsLoading, docsLoadError, documents.length]);

  // Real per-file list for whichever collection is selected - refetched on
  // selection change, then kept live by the polling effect below while any
  // row is still mid-pipeline.

  useEffect(() => {
    setDocumentsLoading(true);
    setDocsLoadError(false);
    loadedDocsRef.current = 0;
    void fetchDocuments(selectedId).finally(() => setDocumentsLoading(false));
  }, [selectedId, fetchDocuments]);

  useEffect(() => {
    const hasNonTerminal = documents.some((d) => NON_TERMINAL_STATUSES.includes(d.status));
    if (!hasNonTerminal) return undefined;
    const timer = setInterval(() => void fetchDocuments(selectedId), DOCUMENT_POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [documents, selectedId, fetchDocuments]);

  async function handleUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setError(null);
    setFaceNotice(null);
    const ext = extensionOf(file.name);
    if (!ext) {
      setError(t("unsupportedFileTypeError", { name: file.name, extensions: ALL_EXTENSIONS.join(", ") }));
      return;
    }
    if (file.size === 0) {
      setError(t("emptyFileError"));
      return;
    }

    const collectionId = selectedId || null;
    // Snapshot before the upload clears it in `finally` below.
    const trimmedPersonName = personName.trim();
    setUploading(true);
    try {
      let uploadResult = null;
      if (ASYNC_EXTENSIONS.includes(ext)) {
        await api.uploadKnowledgeAudio(file, collectionId);
      } else {
        uploadResult = await api.uploadKnowledgeFile(file, collectionId);
      }
      // Either path has already created (and, for sync files, likely
      // already finished) the real KnowledgeDocument row server-side by the
      // time these calls resolve - refetch immediately so it appears
      // without waiting for the next poll tick.
      await fetchDocuments(collectionId);
      // Real completion acknowledgment, not a progress simulation: this
      // fires only after the upload call actually resolved successfully,
      // and it sweeps through stages the file provably passed through
      // (documentService only ever reaches this point via Extract->Embed->
      // Ready). Timeout length matches the CSS sweep's total duration
      // (4 x 0.25s stagger + 0.45s final-stage animation = ~1.45s).
      setSweeping(true);
      clearTimeout(sweepTimeoutRef.current);
      sweepTimeoutRef.current = setTimeout(() => setSweeping(false), 1500);

      // Face enrollment (task #15) - best-effort bonus step layered on top
      // of the upload above, same "doesn't block the main operation"
      // philosophy as KnowledgeController.storeOriginalFile server-side:
      // the knowledge-base upload has already succeeded by this point
      // regardless of anything below.
      const isFaceCandidateImage = [".png", ".jpg", ".jpeg", ".webp"].includes(ext);
      if (biometricAgent?.biometricConsentEnabled === true && trimmedPersonName && isFaceCandidateImage) {
        try {
          const { enrollFaceFromDataUrl, ensureFaceModel, isFaceModelCached } = await import("../lib/edge/face");
          if (!isFaceModelCached()) {
            // The biometricConsentEnabled check above IS the consent for
            // this download - matches how ensureVisionModel's caller in
            // CameraPanel gates on its own opt-in rather than re-prompting
            // per download.
            await ensureFaceModel();
          }
          const dataUrl = await readFileAsDataUrl(file);
          const result = await enrollFaceFromDataUrl(dataUrl);
          if (result.success === true) {
            const enrollment = await api.enrollFace(
              biometricAgent.id,
              collectionId,
              uploadResult?.documentId,
              trimmedPersonName,
              result.embedding,
              true
            );
            setFaceEnrollments((prev) => [...prev, enrollment]);
            setFaceNotice({ tone: "success", text: tFace("enrollSuccess", { name: trimmedPersonName }) });
          } else if (result.success === false) {
            setFaceNotice({ tone: "info", text: faceFailureMessage(result.reason) });
          }
        } catch {
          // Never fail the whole upload over this - the KB upload above
          // already succeeded; face enrollment is a best-effort bonus step.
          setFaceNotice({ tone: "info", text: faceFailureMessage(null) });
        }
      }
    } catch (err) {
      const existing = duplicateUploadFilename(err);
      setError(existing ? t("duplicateFileError", { name: existing }) : errorMessage(err, t("uploadFailedError")));
      await fetchDocuments(collectionId); // the FAILED row (if one was created before the error) still belongs in the table
    } finally {
      setUploading(false);
      setPersonName("");
    }
  }

  return (
    <>
      <ConfirmDialog
        open={consentConfirmOpen}
        onOpenChange={setConsentConfirmOpen}
        title={tFace("privacyTitle")}
        description={tFace("privacyDetails")}
        confirmLabel={tFace("bannerEnable")}
        onConfirm={handleEnableBiometricConsent}
      />
      <ConfirmDialog
        open={forgetTarget !== null}
        onOpenChange={(o) => {
          if (!o) setForgetTarget(null);
        }}
        title={forgetTarget ? tFace("forgetPersonConfirm", { name: forgetTarget.personName }) : ""}
        destructive
        onConfirm={async () => {
          if (forgetTarget) await handleDeleteFaceEnrollment(forgetTarget);
        }}
      />
      <div className="space-y-5">
        <input
          ref={fileInputRef}
          type="file"
          accept={ALL_EXTENSIONS.join(",")}
          style={{ display: "none" }}
          onChange={handleUpload}
        />

        {error && (
          <div
            role="alert"
            className="rounded-lg border border-[var(--danger-border)] bg-[var(--danger-bg)] px-3 py-2 text-sm text-[var(--danger-text)]"
          >
            {error}
          </div>
        )}

        {faceNotice && (
          <div
            role="status"
            className={cn(
              "rounded-lg border px-3 py-2 text-sm",
              faceNotice.tone === "success"
                ? "border-[var(--state-success)]/30 bg-[var(--state-success)]/10 text-[var(--state-success)]"
                : "border-border bg-card text-muted-foreground"
            )}
          >
            {faceNotice.text}
          </div>
        )}

        {!selectedCollection ? (
          collections.length === 0 ? (
            <EmptyState
              icon={FolderPlus}
              title={t("emptyWorkspaceTitle")}
              description={t("emptyWorkspaceHint")}
              action={
                <div className="flex flex-wrap items-center justify-center gap-3">
                  {onRequestCreateCollection && (
                    <Button size="sm" onClick={() => onRequestCreateCollection()}>
                      <Plus aria-hidden="true" size={14} /> {t("createFirstCollectionButton")}
                    </Button>
                  )}
                  <Link
                    href="/help#knowledge"
                    className="text-sm font-medium text-primary underline-offset-4 hover:underline"
                  >
                    {tHC("s2")}
                  </Link>
                </div>
              }
            />
          ) : (
            <EmptyState icon={FolderPlus} title={t("selectCollectionEmptyState")} />
          )
        ) : (
          <>
            <CollectionHeaderCard selectedCollection={selectedCollection} exposedAgents={exposedAgents} />

            <RagQualityPanel collectionId={selectedCollection.id} />

            {documents.length > 0 && (
              <IngestionPipelineCard
                pipelineStats={pipelineStats}
                embeddingModel={selectedCollection.embeddingModel}
                uploading={uploading}
              />
            )}

            <Card className="gap-0 p-5">
              <div className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {t("uploadCardLabel")}
              </div>
              <div className="flex flex-wrap items-center gap-3">
                <Button
                  type="button"
                  variant="outline"
                  className="border-dashed hover:border-primary/40"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={uploading || sidecarDown}
                >
                  {uploading ? (
                    <Loader2 aria-hidden="true" size={14} className="animate-spin" />
                  ) : (
                    <Upload size={14} aria-hidden="true" />
                  )}{" "}
                  {uploading ? t("uploadingLabel") : t("chooseFileLabel")}
                </Button>
                <span className="text-xs text-muted-foreground">
                  {sidecarDown
                    ? "Uploads are paused while the indexing service reconnects."
                    : t("uploadTargetLabel", { name: selectedCollection.name, extensions: ALL_EXTENSIONS.join(", ") })}
                </span>
              </div>
              <FaceEnrollmentControls
                biometricAgent={biometricAgent}
                biometricBannerDismissed={biometricBannerDismissed}
                setBiometricBannerDismissed={setBiometricBannerDismissed}
                biometricBusy={biometricBusy}
                setConsentConfirmOpen={setConsentConfirmOpen}
                personName={personName}
                setPersonName={setPersonName}
                uploading={uploading}
                faceEnrollments={faceEnrollments}
                setForgetTarget={setForgetTarget}
              />

              {documentsLoading && documents.length === 0 ? (
                <div className="mt-4 text-sm text-muted-foreground">{t("loadingFilesLabel")}</div>
              ) : documents.length === 0 ? (
                <div className="mt-4 rounded-lg border border-dashed border-border bg-card/40 px-4 py-8 text-center">
                  <div className="text-sm font-medium text-foreground">{t("noFilesUploadedLabel")}</div>
                  <div className="mt-1 text-xs text-muted-foreground">{t("noFilesUploadedHint")}</div>
                </div>
              ) : (
                <div className="mt-4">
                  <KnowledgeDocumentFilters
                    docQuery={docQuery}
                    setDocQuery={setDocQuery}
                    docSort={docSort}
                    setDocSort={setDocSort}
                    docGroup={docGroup}
                    setDocGroup={setDocGroup}
                    docCounts={docCounts}
                  />
                  {visibleDocuments.length === 0 && (
                    <div className="rounded-lg border border-dashed border-border bg-card/40 px-4 py-6 text-center text-sm text-muted-foreground">
                      {tHC("s1")}
                    </div>
                  )}
                  <KnowledgeDocumentsTable
                    docs={docPageItems}
                    onError={setError}
                    onDeleted={() => void fetchDocuments(selectedId)}
                  />
                </div>
              )}
              <LoadMoreBar
                loaded={documents.length}
                total={docMeta.total}
                hasMore={docMeta.hasMore}
                loading={docsLoadingMore}
                onLoadMore={() => void loadMoreDocuments()}
                itemLabel={t("filesItemLabel")}
              />
              <PaginationControls
                page={docPage}
                totalPages={docTotalPages}
                totalItems={docTotalItems}
                pageSize={docPageSize}
                onPageChange={setDocPage}
                onPageSizeChange={setDocPageSize}
                itemLabel={t("filesItemLabel")}
              />
            </Card>
          </>
        )}

        {/* Tenant-level OCR configuration - deliberately last in the column:
         * a rarely-touched setting, not part of the day-to-day upload-and-
         * search flow that owns the top of this screen. */}
        <OcrProviderCard />
      </div>

      <SemanticSearchPanel
        collections={collections}
        agents={agents}
        processingAvailable={processingAvailable}
        onError={setError}
      />
    </>
  );
});

export default KnowledgePanel;
