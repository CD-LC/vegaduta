// Browser-side download helpers (split out of core.ts, which re-exports them).

// Shared object-URL-anchor download trigger. Every blob-download call in
// this file (downloadKnowledgeDocumentFile, downloadWorkflowRunOutput,
// downloadWorkflowDiagramPdf, and the generated-artifact downloads below)
// hands back a { blob, filename } pair and previously left every CALLER to
// reimplement the same six lines (KnowledgePanel.tsx's handleDownloadFile is
// the canonical example - see its own comment). Factored out here, once, for
// the two new artifact-download callers (MediaCard.tsx, files/page.tsx) so
// they don't each duplicate it a third and fourth time - existing callers are
// left exactly as they are per this change's "don't touch what already
// works" scope.
export function triggerBrowserDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename || "download";
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

// Maps a recorded MediaRecorder Blob's actual MIME type to the filename
// extension sendVoiceTurnStream uploads it under. This used to be `type.includes
// ("wav") ? "turn.wav" : "turn.webm"` - a hardcoded two-way guess that
// silently mislabels every OTHER MediaRecorder output as ".webm", even
// though the bytes underneath are a completely different container.
// CallPanel's startPushToTalk only ever REQUESTS "audio/wav" then falls
// back to "audio/webm" (isTypeSupported checks), but Safari/iOS support
// neither - MediaRecorder there silently ignores the requested mimeType and
// records its own default instead, which is "audio/mp4" (AAC). The
// resulting blob.type correctly reports "audio/mp4", but the old ext logic
// still forced the filename to "turn.webm" - an MP4-encoded file sent to
// the backend labeled as WebM. That mismatch reaches two format-sniffing
// consumers: the sidecar's /transcribe (transcription.py's _suffix_of
// picks the temp file's on-disk suffix from this same filename, so
// faster-whisper/PyAV can be handed a mislabeled container) and Sarvam's
// cloud STT API (SarvamSpeechToTextClient forwards this filename verbatim
// as the multipart part's filename). Either can fail to decode or
// mis-decode a wrongly-labeled file - the likely explanation for "Hold to
// talk sometimes doesn't work": it depends on which STT tier ends up
// handling the turn and how strict that tier is about a mismatched
// extension, which is exactly the kind of failure that looks intermittent
// and browser-dependent rather than 100% broken. Order matters below (mp4
// checked before "m4a"/generic fallback) since some UAs report
// "audio/mp4;codecs=mp4a.40.2".
export function voiceBlobExtension(mimeType: string): string {
  const type = (mimeType || "").toLowerCase();
  if (type.includes("wav")) return "wav";
  if (type.includes("webm")) return "webm";
  if (type.includes("ogg")) return "ogg";
  if (type.includes("mp4") || type.includes("m4a") || type.includes("aac")) return "mp4";
  if (type.includes("mpeg") || type.includes("mp3")) return "mp3";
  // Unknown/empty mimeType (e.g. an older browser that ignores every
  // MediaRecorder options object entirely) - webm was the prior default
  // and remains the most common real-world MediaRecorder output.
  return "webm";
}
