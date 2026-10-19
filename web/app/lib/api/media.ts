import { apiFetch, friendlyStatusFallback, toJson, jsonBody } from "./core";
import type { DocumentGenerationJob, Page, DocumentGenerationRequest } from "./types";
import { filenameFromContentDisposition } from "../contentDisposition";

export const mediaApi = {
  // Artifact-listing (2026-08-03): the four GET-by-id endpoints above now
  // have real list siblings (GeneratedImageController/
  // ElevenLabsGeneratedAudioController/SarvamGeneratedAudioController/
  // VideoGenerationController all gained a bare @GetMapping), so files/
  // page.tsx no longer has to say "listing not available" for these four
  // types. 404 is not expected here (list routes always return, even if
  // empty), so failures surface through toJson like any other endpoint.
  listGeneratedImages: () => apiFetch(`/api/imagegen/images`).then(toJson<unknown[]>),
  listElevenLabsAudio: () => apiFetch(`/api/elevenlabs/audio`).then(toJson<unknown[]>),
  listSarvamAudio: () => apiFetch(`/api/sarvam/audio`).then(toJson<unknown[]>),
  listVideoGenerationJobs: () => apiFetch(`/api/videogen/jobs`).then(toJson<unknown[]>),

  // Studio's "Generate a video" form (2026-08-05) - the REST counterpart of
  // the agent-chat generate_video tool, direct-called instead of via chat.
  // Quota/plan/BYOK errors (VideoGenerationQuotaService.checkAllowed) arrive
  // already user-worded through the body's `error` field, same as every
  // other quota-gated call in this file - toJson surfaces them verbatim.
  // `timeoutMs` bounds this ONE HTTP call (Universal Request Lifecycle
  // policy - Part 1/12) - GenerateVideoForm.tsx tracks the video job's own
  // multi-minute wall-clock ceiling separately, since a job spans many of
  // these calls over several minutes.
  generateVideo: (
    prompt: string,
    durationSeconds: number,
    sourceImage?: { imageBase64: string; imageMimeType: string } | null,
    timeoutMs?: number
  ) =>
    apiFetch(`/api/videogen/generate`, {
      method: "POST",
      timeoutMs,
      ...jsonBody({
        prompt,
        durationSeconds,
        imageBase64: sourceImage?.imageBase64 ?? null,
        imageMimeType: sourceImage?.imageMimeType ?? null,
      }),
    }).then(toJson),
  getVideoGenerationJob: (jobId: string, timeoutMs?: number) =>
    apiFetch(`/api/videogen/jobs/${jobId}`, { timeoutMs }).then(toJson),

  // Document generation (2026-08-05): same artifact create/list/download
  // trio as the four generators above, but list is a real paginated Spring
  // Data Page (see Page<T> above) rather than a bare array, so this one
  // takes page/size/search params instead of taking none.
  listGeneratedDocuments: ({
    page = 0,
    size = 20,
    search,
  }: { page?: number; size?: number; search?: string } = {}): Promise<Page<DocumentGenerationJob>> => {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("size", String(size));
    if (search) params.set("search", search);
    return apiFetch(`/api/documentgen/documents?${params.toString()}`).then(toJson<Page<DocumentGenerationJob>>);
  },
  // POST returns 202 + the created job record (status starts PENDING/
  // PROCESSING - callers poll listGeneratedDocuments or re-fetch by id to
  // see it reach COMPLETE, same async-job pattern as generateVideo).
  generateDocument: (request: DocumentGenerationRequest): Promise<DocumentGenerationJob> =>
    apiFetch(`/api/documentgen/documents`, { method: "POST", ...jsonBody(request) }).then(
      toJson<DocumentGenerationJob>
    ),
  // Same authenticated-blob-fetch pattern as downloadGeneratedImage/
  // downloadElevenLabsAudio above - a plain <a href> can't carry the bearer
  // token apiFetch attaches.
  downloadGeneratedDocument: async (id: string) => {
    const res = await apiFetch(`/api/documentgen/documents/${id}`);
    if (!res.ok) {
      // .status is read by MediaCard to tell "never existed" (404) and "still generating" (409) apart.
      const err: any = new Error(
        res.status === 404
          ? "This document is no longer available."
          : res.status === 409
            ? "This document is still being generated - try again in a moment."
            : friendlyStatusFallback(res.status)
      );
      err.status = res.status;
      throw err;
    }
    const filename = filenameFromContentDisposition(res.headers.get("Content-Disposition"), `document-${id}`);
    const blob = await res.blob();
    return { blob, filename };
  },
  // Data-analysis sandbox (gap-analysis item 9): files (charts, .xlsx, .py)
  // a run_code call saved. Same authenticated-blob pattern as above; the
  // server always answers as an attachment with the file's real name.
  downloadSandboxArtifact: async (id: any) => {
    const res = await apiFetch(`/api/sandbox/artifacts/${id}`);
    if (!res.ok) {
      throw new Error(res.status === 404 ? "This file is no longer available." : friendlyStatusFallback(res.status));
    }
    const filename = filenameFromContentDisposition(res.headers.get("Content-Disposition"), `analysis-${id}`);
    const blob = await res.blob();
    return { blob, filename };
  },
};
