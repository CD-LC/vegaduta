import { apiFetch, friendlyStatusFallback, toJson, toJsonQuietOn, jsonBody } from "./core";
import type { KnowledgeCollectionResponse, EmbeddingModelOption, FaceEnrollmentResponse } from "./types";
import type { RagEvalRequestBody, RagEvalResponse, RagSettings } from "../ragQuality";
import { filenameFromContentDisposition } from "../contentDisposition";
import { pagedQuery, toPaged, type PagedRows } from "./paged";

export const knowledgeApi = {
  // Knowledge base (Stage 5/6) + collections (Stage F1) + multi-modal (F3a-d)
  //
  // uploadKnowledge kept as the original .md-only name/shape for backward
  // compatibility with anything still calling it, but the panel now calls
  // uploadKnowledgeFile directly - same endpoint, just explicit about the
  // optional collectionId query param Stage F1 added.
  uploadKnowledge: (file: Blob) => knowledgeApi.uploadKnowledgeFile(file, null),

  // Synchronous path (Stage 6 + F3a/F3b): .md/.pdf/.docx/.html/.htm plus
  // .png/.jpg/.jpeg/.webp (OCR). collectionId omitted -> tenant's Default
  // collection, matching KnowledgeController.resolveCollectionId's server-
  // side default.
  uploadKnowledgeFile: (file: Blob, collectionId: string) => {
    const formData = new FormData();
    formData.append("file", file);
    const qs = collectionId ? `?collectionId=${collectionId}` : "";
    return apiFetch(`/api/knowledge/upload${qs}`, {
      method: "POST",
      body: formData,
      // 400/409 (identical content already uploaded) is worded inline by the
      // panel; no second, global toast. The body is IngestResponse; documentId
      // is the KnowledgeDocument row the chunks landed in.
    }).then(toJsonQuietOn<{ documentId: string | null; chunkCount: number; [key: string]: unknown }>([400, 409]));
  },

  // Async path (Stage F3c/F3d): .mp3/.wav/.m4a/.ogg/.mp4/.mov/.webm. Returns
  // 202 + a KnowledgeIngestionJobResponse immediately - caller polls
  // getIngestionJob(jobId) rather than waiting on this request, since
  // transcription can take minutes.
  uploadKnowledgeAudio: (file: Blob, collectionId: string) => {
    const formData = new FormData();
    formData.append("file", file);
    const qs = collectionId ? `?collectionId=${collectionId}` : "";
    return apiFetch(`/api/knowledge/upload-audio${qs}`, {
      method: "POST",
      body: formData,
    }).then(toJson);
  },

  getIngestionJob: (jobId: string) => apiFetch(`/api/knowledge/jobs/${jobId}`).then(toJson),

  // Face enrollment (on-device face recognition, gated by
  // setAgentBiometricConsent above) - enrolls a named person's face embedding
  // against a knowledge-base photo so this agent can recognize them later.
  // documentId is optional (an enrollment isn't always tied to a specific
  // knowledge document), so it's only included in the body when present -
  // same conditional-spread convention as initiateTenantUpgrade's opts below.
  enrollFace: (
    agentId: string,
    collectionId: string,
    documentId: string,
    personName: string,
    embedding: unknown,
    consented: boolean
  ) =>
    apiFetch(`/api/face-enrollments`, {
      method: "POST",
      ...jsonBody({
        agentId,
        collectionId,
        ...(documentId ? { documentId } : {}),
        personName,
        embedding,
        consented,
      }),
    }).then(toJson<FaceEnrollmentResponse>),

  listFaceEnrollments: (agentId: string, collectionId: string) =>
    apiFetch(`/api/face-enrollments?agentId=${agentId}&collectionId=${collectionId}`).then(
      toJson<FaceEnrollmentResponse[]>
    ),

  deleteFaceEnrollment: (enrollmentId: string) =>
    apiFetch(`/api/face-enrollments/${enrollmentId}`, { method: "DELETE" }),

  // collectionIds is optional - omit/empty means "search every collection
  // this tenant has" (KnowledgeSearchService's existing unrestricted
  // behavior), matching SearchRequest's own semantics server-side.
  searchKnowledge: (query: string, topK: number, collectionIds: string[]) =>
    apiFetch(`/api/knowledge/search`, {
      method: "POST",
      ...jsonBody({
        query,
        topK,
        collectionIds: collectionIds && collectionIds.length > 0 ? collectionIds : undefined,
      }),
    }).then(toJson),

  // RAG quality (core/knowledge rag package). Settings: the collection's parent-child opt-in. 404/503 are
  // expected states (collection just deleted, tenant schema pending migration V432), not failures, so they
  // raise no global toast and the panel renders its own inline note.
  getRagSettings: (collectionId: string): Promise<RagSettings> =>
    apiFetch(`/api/knowledge/collections/${collectionId}/retrieval-settings`).then(
      toJsonQuietOn<RagSettings>([404, 503])
    ),
  updateRagSettings: (collectionId: string, payload: { parentChildEnabled?: boolean }): Promise<RagSettings> =>
    apiFetch(`/api/knowledge/collections/${collectionId}/retrieval-settings`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJsonQuietOn<RagSettings>([409, 503])),
  // Retrieval eval (premium-gated like the other eval endpoints: a FREE-plan 403 is an expected state the
  // panel renders as an inline upgrade note). The server stops at its time budget (80s default) because the
  // a fronting edge proxy (e.g. Cloudflare) typically answers 524 at about 100s, so the client gives up just under that.
  runRagEval: (collectionId: string, payload: RagEvalRequestBody): Promise<RagEvalResponse> =>
    apiFetch(`/api/knowledge/${collectionId}/eval`, {
      method: "POST",
      timeoutMs: 95000,
      ...jsonBody(payload),
    }).then(toJsonQuietOn<RagEvalResponse>([403])),

  // Knowledge collections (Stage F1) - hierarchical folders chunks land in.
  listCollections: () => apiFetch(`/api/knowledge/collections`).then(toJson<KnowledgeCollectionResponse[]>),
  // Multi-model ingestion - the fixed catalog for the "Embedding model"
  // dropdown at collection-creation time (immutable afterward).
  listEmbeddingModels: () =>
    apiFetch(`/api/knowledge/collections/embedding-models`).then(toJson<EmbeddingModelOption[]>),
  createCollection: (payload: object) =>
    apiFetch(`/api/knowledge/collections`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson<KnowledgeCollectionResponse>),
  updateCollection: (collectionId: string, payload: object) =>
    apiFetch(`/api/knowledge/collections/${collectionId}`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson<KnowledgeCollectionResponse>),
  deleteCollection: (collectionId: string) =>
    // 409 = still holds files: the panel words it itself (inline), so no global toast.
    apiFetch(`/api/knowledge/collections/${collectionId}`, { method: "DELETE" }).then(toJsonQuietOn([409])),
  // 204 on success; 404 = already gone, treated as deleted.
  deleteKnowledgeDocument: async (documentId: string): Promise<void> => {
    const res = await apiFetch(`/api/knowledge/documents/${documentId}`, { method: "DELETE" });
    if (res.ok || res.status === 404) return;
    await toJson(res);
  },

  // "Build the backend to make it real" - the real, persisted per-file
  // inventory for a collection (KnowledgeDocumentResponse[], newest first),
  // backing the AXON Knowledge screen's file table + pipeline status.
  // A 404 here is not an error worth an alarm: it just means the selected
  // collection was deleted, or the selection is momentarily stale mid-refresh
  // (the Knowledge panel already handles that by clearing to an empty table).
  // Return [] on 404 WITHOUT routing through toJson, so it never raises the
  // global HUD error toast (ToastHost) - same "expected 404, opt out of the
  // toast" precedent as the RL endpoints above. Every other non-2xx still goes
  // through toJson so genuine failures surface as usual.
  listKnowledgeDocuments: (collectionId: string) =>
    apiFetch(`/api/knowledge/collections/${collectionId}/documents`).then((res): unknown[] | Promise<unknown[]> =>
      res.status === 404 ? [] : toJson<unknown[]>(res)
    ),
  // One page of the same file table (newest first, 404 -> empty). The server caps each
  // response, so the panels page with offset and read X-Total-Count / X-Has-More.
  listKnowledgeDocumentsPage: (collectionId: any, offset = 0, limit = 500) =>
    apiFetch(
      `/api/knowledge/collections/${collectionId}/documents?${pagedQuery(new URLSearchParams(), offset, limit)}`
    ).then((res: any): Promise<PagedRows> | PagedRows =>
      res.status === 404 ? { rows: [] as any[], total: 0, hasMore: false } : toPaged(res, offset, limit)
    ),
  // Tenant-wide totals (KnowledgeStatsResponse) for the topbar's collection/
  // document/vector counts - every field a live query, see KnowledgeController#stats.
  getKnowledgeStats: () => apiFetch(`/api/knowledge/stats`).then(toJson),
  // Stage 5 (KB multi-modal storage) - downloads the ORIGINAL uploaded file
  // (image/pdf/audio/video), not the extracted text. Same blob+filename
  // handoff pattern as downloadWorkflowRunOutput above, for the same reason
  // (a plain <a href> can't carry the bearer token). 404 means either the
  // document doesn't exist or it has no stored original - see
  // KnowledgeController#downloadOriginalFile's javadoc for why those two
  // cases aren't distinguished.
  downloadKnowledgeDocumentFile: async (documentId: string) => {
    const res = await apiFetch(`/api/knowledge/documents/${documentId}/file`);
    if (!res.ok) {
      throw new Error(
        res.status === 404
          ? "The original file is no longer available for this document."
          : friendlyStatusFallback(res.status)
      );
    }
    const filename = filenameFromContentDisposition(res.headers.get("Content-Disposition"), "download");
    const blob = await res.blob();
    return { blob, filename };
  },

  // Artifact-download Phase 1 (2026-07-29): agent tool callbacks
  // (GenerateImageToolCallback, ElevenLabsSpeakToolCallback,
  // SarvamSpeakToolCallback, video generation) have ALWAYS stored their
  // output and served it back over these four GET-by-id endpoints - only
  // web/app had zero callers for any of them, so a generated image/video/
  // speech clip referenced in an agent's reply rendered as inert plain text
  // (see RichText.tsx's scheme check) with no way to see or save it. Same
  // blob+filename shape and Content-Disposition-parsing convention as
  // downloadKnowledgeDocumentFile above - a plain <img>/<audio>/<video> src
  // can't carry the bearer token apiFetch attaches, so every caller
  // (MediaCard.tsx, files/page.tsx) fetches the bytes through this
  // authenticated path first and turns them into an object URL. Each 404
  // path message is deliberately specific rather than reusing
  // friendlyStatusFallback's generic "That couldn't be found." - the four
  // failure modes (deleted image, deleted/expired audio row, video job not
  // yet complete, deleted job) read differently to a user retrying a
  // download.
  downloadGeneratedImage: async (imageId: string) => {
    const res = await apiFetch(`/api/imagegen/images/${imageId}`);
    if (!res.ok) {
      throw new Error(res.status === 404 ? "This image is no longer available." : friendlyStatusFallback(res.status));
    }
    const filename = filenameFromContentDisposition(res.headers.get("Content-Disposition"), `image-${imageId}.png`);
    const blob = await res.blob();
    return { blob, filename };
  },
  downloadElevenLabsAudio: async (audioId: string) => {
    const res = await apiFetch(`/api/elevenlabs/audio/${audioId}`);
    if (!res.ok) {
      throw new Error(
        res.status === 404 ? "This audio clip is no longer available." : friendlyStatusFallback(res.status)
      );
    }
    const filename = filenameFromContentDisposition(res.headers.get("Content-Disposition"), `speech-${audioId}.mp3`);
    const blob = await res.blob();
    return { blob, filename };
  },
  // generate_music tracks (GeneratedMusicController, /api/musicgen/tracks) -
  // fetched as a blob for the same reason as the speech clips above.
  downloadGeneratedMusic: async (trackId: string) => {
    const res = await apiFetch(`/api/musicgen/tracks/${trackId}`);
    if (!res.ok) {
      throw new Error(
        res.status === 404 ? "This music track is no longer available." : friendlyStatusFallback(res.status)
      );
    }
    const filename = filenameFromContentDisposition(res.headers.get("Content-Disposition"), `music-${trackId}.mp3`);
    const blob = await res.blob();
    return { blob, filename };
  },
  downloadSarvamAudio: async (audioId: string) => {
    const res = await apiFetch(`/api/sarvam/audio/${audioId}`);
    if (!res.ok) {
      throw new Error(
        res.status === 404 ? "This audio clip is no longer available." : friendlyStatusFallback(res.status)
      );
    }
    const filename = filenameFromContentDisposition(res.headers.get("Content-Disposition"), `speech-${audioId}.mp3`);
    const blob = await res.blob();
    return { blob, filename };
  },
  // 404 here covers two distinct states the backend deliberately doesn't
  // distinguish for this GET (VideoGenerationController#getVideo returns a
  // plain notFound() for both) - the job hasn't reached COMPLETE yet, or the
  // id doesn't exist at all. Callers that already know the job's status
  // (e.g. a poll loop watching /api/videogen/jobs/{id}) should only call this
  // once that status is COMPLETE; this message is written for the case where
  // they don't.
  downloadGeneratedVideo: async (videoId: string) => {
    const res = await apiFetch(`/api/videogen/videos/${videoId}`);
    if (!res.ok) {
      throw new Error(
        res.status === 404
          ? "This video isn't ready yet or is no longer available."
          : friendlyStatusFallback(res.status)
      );
    }
    const filename = filenameFromContentDisposition(res.headers.get("Content-Disposition"), `video-${videoId}.mp4`);
    const blob = await res.blob();
    return { blob, filename };
  },
};
