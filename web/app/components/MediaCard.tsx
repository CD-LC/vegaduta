"use client";

import { useEffect, useState } from "react";
import { Download, AlertTriangle, Loader2, ImageIcon, Music, Video as VideoIcon, File as FileIcon } from "lucide-react";
import { api, triggerBrowserDownload } from "../lib/api";
import { getFileTypeIcon } from "../lib/fileIcons";
import RichText from "./RichText";
import { Button } from "./ui/button";

// ---------------------------------------------------------------------------
// STOPGAP NOTICE (artifact-download Phase 1, 2026-07-29):
//
// Tool callbacks (GenerateImageToolCallback, ElevenLabsSpeakToolCallback,
// SarvamSpeakToolCallback, video generation) return their result URL as a
// plain relative path embedded in the assistant's text reply, e.g.
// "/api/imagegen/images/<uuid>". There is no structured, typed signal in the
// chat/stream response today that says "this turn produced an artifact" -
// the ONLY way the frontend knows one exists is by pattern-matching the
// reply text for these known URL shapes. That is exactly what this file
// does, and it is a deliberate, temporary stopgap - not the intended design.
// A later phase is expected to replace this with structured SSE `artifact`
// events (or an equivalent typed field on the turn/response) that name the
// artifact's kind/id explicitly, at which point this regex-sniffing approach
// should be deleted rather than extended. Do not read the presence of this
// file as "URL-sniffing is how this product does artifacts" - it's a bridge
// until the backend emits structured events, tracked as Phase 2 work.
// ---------------------------------------------------------------------------

export type MediaKind =
  "image" | "elevenlabs-audio" | "sarvam-audio" | "music" | "video" | "document" | "sandbox-artifact";

export interface MediaRef {
  kind: MediaKind;
  id: string;
  raw: string;
  index: number;
  length: number;
}

// Matches either a markdown link whose href is one of the known
// artifact routes ("[caption](/api/imagegen/images/<uuid>)") or a bare path
// on its own. The markdown-link alternative is tried first so the WHOLE
// "[label](url)" construct is captured and later stripped in one piece -
// otherwise removing only the inner URL would leave a dangling, broken
// "[label]()" behind for RichText.tsx to render as literal text.
const MEDIA_URL_PATTERN = new RegExp(
  "\\[([^\\]]+)\\]\\((\\/api\\/(?:imagegen\\/images|elevenlabs\\/audio|sarvam\\/audio|musicgen\\/tracks|videogen\\/videos|documentgen\\/documents|sandbox\\/artifacts)\\/" +
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\)" +
    "|" +
    "(\\/api\\/(?:imagegen\\/images|elevenlabs\\/audio|sarvam\\/audio|musicgen\\/tracks|videogen\\/videos|documentgen\\/documents|sandbox\\/artifacts)\\/" +
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})",
  "g"
);

function kindFromPath(path: string): MediaKind | null {
  if (path.includes("/api/imagegen/images/")) return "image";
  if (path.includes("/api/elevenlabs/audio/")) return "elevenlabs-audio";
  if (path.includes("/api/sarvam/audio/")) return "sarvam-audio";
  if (path.includes("/api/musicgen/tracks/")) return "music";
  if (path.includes("/api/videogen/videos/")) return "video";
  if (path.includes("/api/documentgen/documents/")) return "document";
  if (path.includes("/api/sandbox/artifacts/")) return "sandbox-artifact";
  return null;
}

// The inverse of kindFromPath - used to rebuild the exact path a real tool
// result would contain, so a ref can be checked against tool-call history
// without re-parsing anything.
const KIND_URL_PREFIX: Record<MediaKind, string> = {
  image: "/api/imagegen/images/",
  "elevenlabs-audio": "/api/elevenlabs/audio/",
  "sarvam-audio": "/api/sarvam/audio/",
  music: "/api/musicgen/tracks/",
  video: "/api/videogen/videos/",
  document: "/api/documentgen/documents/",
  "sandbox-artifact": "/api/sandbox/artifacts/",
};

/** One recorded tool invocation - just enough of ToolCallLogView (AgentController.java) to verify a media ref against it. */
export interface ToolCallTraceEntry {
  toolOutput?: string | null;
}

/**
 * A model can describe an artifact URL in its reply text without ever
 * calling the tool that would have produced it (seen in practice: asked to
 * "sing", a text LLM cannot, but may still fabricate a plausible-looking
 * "/api/elevenlabs/audio/<uuid>" reference rather than say so - the `speak`
 * tool's own description already warns against this, but a prompt is not an
 * enforcement mechanism). Fetching a ref that was never really produced
 * 404s and reads as "this used to work and expired," which is misleading.
 *
 * `trace` is the tool-call log for the turns this text could plausibly come
 * from (see call sites: AgentPlayground passes just this turn's own calls,
 * matching the `speak` tool's "never reuse a URL from a previous turn"
 * contract; ChatPanel passes the agent's whole call history, which is looser
 * but still catches a fully fabricated id, since that id is a random UUID a
 * model cannot guess). `trace` being undefined (a call site not wired up
 * yet) means "unknown" - refs are still attempted rather than suppressed,
 * so this only tightens behavior at wired call sites.
 */
function isBackedByRealToolCall(ref: MediaRef, trace?: ToolCallTraceEntry[]): boolean {
  if (!trace) return true;
  const needle = `${KIND_URL_PREFIX[ref.kind]}${ref.id}`;
  return trace.some((call) => (call.toolOutput || "").includes(needle));
}

/**
 * A ref the trace does not vouch for is NOT declared fabricated on the spot any more - the
 * trace is a best-effort before/after diff of the tool-call log and has missed real calls in
 * production (2026-10-10: a document whose generation job completed, whose URL the tool
 * returned, shown as "never actually generated" with 0 tool calls). The server is the
 * authority: an unbacked ref is fetched like any other, and only a 404 (the artifact does not
 * exist for this tenant) shows the never-generated notice. A fabricated id is a random UUID
 * the model cannot guess, so a fetch of it always 404s and the honest copy still appears.
 */
function isNotFound(err: any): boolean {
  if (err?.status === 404) return true;
  const message = String(err?.message || "");
  return /no longer available|not found/i.test(message);
}

/** Exported for reuse/testing - scans assistant text for the known artifact URL shapes. */
export function extractMediaRefs(text: string): MediaRef[] {
  if (!text) return [];
  const refs: MediaRef[] = [];
  MEDIA_URL_PATTERN.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = MEDIA_URL_PATTERN.exec(text)) !== null) {
    const path = m[2] || m[3];
    const kind = path ? kindFromPath(path) : null;
    if (!kind || !path) continue;
    const id = path.split("/").pop() || "";
    if (!id) continue;
    refs.push({ kind, id, raw: m[0], index: m.index, length: m[0].length });
  }
  return refs;
}

/** Removes the matched raw substrings, then collapses any blank-line runs the removal left behind. */
function stripMediaRefs(text: string, refs: MediaRef[]): string {
  if (refs.length === 0) return text;
  let out = "";
  let last = 0;
  for (const ref of refs) {
    out += text.slice(last, ref.index);
    last = ref.index + ref.length;
  }
  out += text.slice(last);
  return out.replace(/\n{3,}/g, "\n\n").trim();
}

const KIND_LABEL: Record<MediaKind, string> = {
  image: "Generated image",
  "elevenlabs-audio": "Generated speech",
  "sarvam-audio": "Generated speech",
  music: "Generated music",
  video: "Generated video",
  document: "Generated document",
  "sandbox-artifact": "Analysis output",
};

// Static per-kind icon, used before a document's real filename/extension is
// known (header while loading, UnverifiedMediaNotice which never fetches) -
// "document" gets a generic file icon here; once a document's filename is
// known, DocumentPreview below swaps in the extension-specific icon from
// fileIcons.ts instead.
const KIND_ICON: Record<MediaKind, typeof ImageIcon> = {
  image: ImageIcon,
  "elevenlabs-audio": Music,
  "sarvam-audio": Music,
  music: Music,
  video: VideoIcon,
  document: FileIcon,
  "sandbox-artifact": FileIcon,
};

// One fetch function per artifact kind - all return the same
// { blob, filename } shape (api.ts's downloadKnowledgeDocumentFile
// convention), so the component below can stay generic over `kind`.
const FETCHERS: Record<MediaKind, (id: string) => Promise<{ blob: Blob; filename: string }>> = {
  image: (id) => api.downloadGeneratedImage(id),
  "elevenlabs-audio": (id) => api.downloadElevenLabsAudio(id),
  "sarvam-audio": (id) => api.downloadSarvamAudio(id),
  music: (id) => api.downloadGeneratedMusic(id),
  video: (id) => api.downloadGeneratedVideo(id),
  document: (id) => api.downloadGeneratedDocument(id),
  "sandbox-artifact": (id) => api.downloadSandboxArtifact(id),
};

// GenerateVideoToolCallback hands back its videoUrl the instant the job is
// enqueued - tens of seconds to a few minutes before the file is actually
// servable (see that class's javadoc; VideoGenerationController#getVideo
// 404s until the job reaches COMPLETE). Without this, a genuinely successful
// video generation would 404 on MediaItem's very first fetch and render
// exactly like a broken/expired file - indistinguishable from the fabrication
// case this whole component exists to guard against. Mirrors the poll
// interval/wall-clock cap studio/GenerateVideoForm.tsx already uses for the
// same job.
const VIDEO_POLL_INTERVAL_MS = 4000;
const VIDEO_MAX_WALL_CLOCK_MS = 10 * 60 * 1000;

/**
 * One artifact reference, rendered as real inline media once it's fetched.
 * Every artifact endpoint (see api.ts) is an authenticated GET whose bytes a
 * plain <img>/<audio>/<video> src can't carry the bearer token for - so this
 * fetches the blob first (same authenticated path the download button uses)
 * and points the element at a local object URL, exactly like AppShell's own
 * avatar image and KnowledgePanel's file download already do.
 */
function MediaItem({ kind, id, unverified = false }: { kind: MediaKind; id: string; unverified?: boolean }) {
  const [state, setState] = useState<{
    status: "loading" | "generating" | "ready" | "error" | "timeout" | "unverified";
    blob?: Blob;
    blobUrl?: string;
    filename?: string;
    error?: string;
  }>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;
    let objectUrl: string | null = null;
    let pollTimer: ReturnType<typeof setTimeout> | null = null;
    const startedAt = Date.now();
    setState({ status: "loading" });

    function loadArtifact() {
      FETCHERS[kind](id)
        .then(({ blob, filename }) => {
          if (cancelled) return;
          objectUrl = URL.createObjectURL(blob);
          setState({ status: "ready", blob, blobUrl: objectUrl, filename });
        })
        .catch((err: any) => {
          if (cancelled) return;
          if (unverified && isNotFound(err)) {
            setState({ status: "unverified" });
            return;
          }
          setState({ status: "error", error: err?.message || "This file couldn't be loaded." });
        });
    }

    // See VIDEO_POLL_INTERVAL_MS's comment above - check the job's real
    // status before ever attempting the blob fetch, so a still-processing
    // (but genuinely successful) video reads as progress, not failure.
    function pollVideoJob() {
      api
        .getVideoGenerationJob(id)
        .then((job: any) => {
          if (cancelled) return;
          if (job?.status === "COMPLETE") {
            loadArtifact();
          } else if (job?.status === "FAILED") {
            setState({ status: "error", error: job.errorMessage || "Video generation failed." });
          } else if (Date.now() - startedAt >= VIDEO_MAX_WALL_CLOCK_MS) {
            setState({
              status: "timeout",
              error:
                "This is taking longer than usual. It will keep generating in the background - check the Files page shortly.",
            });
          } else {
            setState({ status: "generating" });
            pollTimer = setTimeout(pollVideoJob, VIDEO_POLL_INTERVAL_MS);
          }
        })
        .catch((err: any) => {
          if (cancelled) return;
          if (unverified && isNotFound(err)) {
            setState({ status: "unverified" });
            return;
          }
          setState({ status: "error", error: err?.message || "This file couldn't be loaded." });
        });
    }

    if (kind === "video") {
      pollVideoJob();
    } else {
      loadArtifact();
    }

    return () => {
      cancelled = true;
      if (pollTimer) clearTimeout(pollTimer);
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [kind, id, unverified]);

  const Icon = KIND_ICON[kind];
  const label = KIND_LABEL[kind];

  if (state.status === "unverified") {
    return <UnverifiedMediaNotice kind={kind} />;
  }

  function handleDownload() {
    if (state.status === "ready" && state.blob) {
      triggerBrowserDownload(state.blob, state.filename || "download");
    }
  }

  return (
    <div className="w-full max-w-[85%] overflow-hidden rounded-lg border border-border bg-secondary/30">
      <div className="flex items-center justify-between gap-2 border-b border-border/60 px-3 py-1.5">
        <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
          <Icon size={13} />
          {label}
        </span>
        {state.status === "ready" && (
          <Button variant="ghost" size="sm" className="h-8 gap-1 px-2 text-xs" onClick={handleDownload}>
            <Download aria-hidden="true" size={12} /> Download
          </Button>
        )}
      </div>
      <div className="p-2">
        {(state.status === "loading" || state.status === "generating") && (
          <div className="flex items-center gap-2 py-3 text-xs text-muted-foreground">
            <Loader2 aria-hidden="true" size={13} className="animate-spin" />
            {state.status === "generating" ? "Generating your video… this can take a few minutes." : "Loading…"}
          </div>
        )}
        {/* A failed fetch must not look like "nothing was generated" - state it
            plainly instead, same "honest failure state" posture as
            knowledge/page.tsx's collectionsFailed banner. */}
        {state.status === "error" && (
          <div className="flex items-center gap-2 py-2 text-xs text-destructive">
            <AlertTriangle aria-hidden="true" size={13} /> {state.error}
          </div>
        )}
        {/* Still genuinely in progress, not broken - softer styling than the
            error state above so it doesn't read as a failure. */}
        {state.status === "timeout" && (
          <div className="flex items-center gap-2 py-2 text-xs text-muted-foreground">
            <AlertTriangle aria-hidden="true" size={13} /> {state.error}
          </div>
        )}
        {/* Sandbox charts (png/jpg/svg) render inline - the blob's own type decides, since the
            URL carries no extension; every other sandbox output (xlsx/csv/py/...) is a file row. */}
        {state.status === "ready" && kind === "sandbox-artifact" && state.blob?.type.startsWith("image/") && (
          <img
            src={state.blobUrl}
            alt={state.filename || "Analysis chart"}
            className="max-h-96 w-auto max-w-full rounded bg-white"
          />
        )}
        {/* HTML reports/dashboards render inline. sandbox="" runs no scripts
            and opens no network - static markup, CSS and SVG show; injected or
            hostile markup stays inert. */}
        {state.status === "ready" && kind === "sandbox-artifact" && state.blob?.type === "text/html" && (
          <iframe
            sandbox=""
            title={state.filename || "Generated page"}
            src={state.blobUrl}
            className="h-96 w-full rounded border border-border bg-white"
          />
        )}
        {state.status === "ready" && kind === "sandbox-artifact" && !state.blob?.type.startsWith("image/") && state.blob?.type !== "text/html" && (
          <DocumentPreview filename={state.filename} blobUrl={state.blobUrl} />
        )}
        {state.status === "ready" && kind === "image" && (
          <img
            src={state.blobUrl}
            alt={state.filename || "Generated image"}
            className="max-h-80 w-auto max-w-full rounded"
          />
        )}
        {state.status === "ready" && (kind === "elevenlabs-audio" || kind === "sarvam-audio" || kind === "music") && (
          <audio src={state.blobUrl} controls className="w-full" />
        )}
        {state.status === "ready" && kind === "video" && (
          <video src={state.blobUrl} controls className="max-h-80 w-auto max-w-full rounded" />
        )}
        {state.status === "ready" && kind === "document" && (
          <DocumentPreview filename={state.filename} blobUrl={state.blobUrl} />
        )}
      </div>
    </div>
  );
}

/**
 * Renders a generated document once fetched. Most document types (docx/
 * xlsx/pptx/csv/txt/json/md) can't be previewed inline as <img>/<video> can,
 * so this just shows the extension-specific icon (getFileTypeIcon,
 * fileIcons.tsx) and filename - the header's Download button (MediaItem
 * above) is how the user actually gets the file. PDFs are the one exception:
 * browsers natively render them via <embed>, so a pdf gets that inline
 * preview in addition to the same icon+filename row and Download button.
 */
function DocumentPreview({ filename, blobUrl }: { filename?: string; blobUrl?: string }) {
  const { Icon: DocIcon, colorClassName } = getFileTypeIcon(filename);
  const ext = filename && filename.includes(".") ? filename.slice(filename.lastIndexOf(".") + 1).toLowerCase() : "";
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2 py-1 text-xs text-foreground">
        <DocIcon size={16} className={colorClassName} />
        <span className="truncate">{filename || "document"}</span>
      </div>
      {ext === "pdf" && blobUrl && (
        <embed src={blobUrl} type="application/pdf" className="h-96 w-full rounded border border-border/60" />
      )}
    </div>
  );
}

/**
 * Drop-in replacement for a bare `<RichText text={turn.content} />` in the
 * chat surfaces (AgentPlayground.tsx, ChatPanel.tsx). Detects the known
 * generated-artifact URL shapes (see the STOPGAP notice above), strips them
 * out of the text handed to RichText (a raw "/api/imagegen/images/<uuid>"
 * has no http(s) scheme, so RichText's own link scheme-check would otherwise
 * render it as inert plain text - see RichText.tsx), and renders a real
 * media card with a download button for each one instead.
 */
export default function MediaCard({
  text,
  className = "",
  trace,
}: {
  text: string;
  className?: string;
  /** This turn's (or this agent's) real tool-call log - see isBackedByRealToolCall's javadoc-style comment above. */
  trace?: ToolCallTraceEntry[];
}) {
  if (!text) return null;
  const refs = extractMediaRefs(text);
  if (refs.length === 0) {
    return <RichText text={text} className={className} />;
  }
  const cleanedText = stripMediaRefs(text, refs);
  return (
    <div className="flex flex-col gap-2">
      {cleanedText && <RichText text={cleanedText} className={className} />}
      {refs.map((ref, i) => (
        <MediaItem
          key={`${ref.kind}-${ref.id}-${i}`}
          kind={ref.kind}
          id={ref.id}
          unverified={!isBackedByRealToolCall(ref, trace)}
        />
      ))}
    </div>
  );
}

/**
 * Shown for a ref with no matching tool call whose fetch then 404s (see isNotFound's comment).
 * Deliberately NOT phrased like MediaItem's fetch-error state ("couldn't be
 * loaded" reads as "this used to exist") - this artifact was never real, so
 * the copy says that plainly instead.
 */
function UnverifiedMediaNotice({ kind }: { kind: MediaKind }) {
  const Icon = KIND_ICON[kind];
  const label = KIND_LABEL[kind];
  return (
    <div
      className="w-full max-w-[85%] overflow-hidden rounded-lg border border-destructive/30 bg-destructive/5"
      data-testid="media-unverified"
    >
      <div className="flex items-center gap-1.5 border-b border-destructive/20 px-3 py-1.5 text-xs font-medium text-muted-foreground">
        <Icon size={13} />
        {label}
      </div>
      <div className="flex items-center gap-2 p-2 text-xs text-destructive">
        <AlertTriangle aria-hidden="true" size={13} />
        The agent described this but never actually generated it - nothing was produced.
      </div>
    </div>
  );
}
