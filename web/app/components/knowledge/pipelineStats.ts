/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * Real 4-stage aggregate view over a collection's documents - every number
 * here is a live count of KnowledgeDocumentStatus values for the selected
 * collection, not a simulated animation.
 */
export function computePipelineStats(documents: any[]) {
  const totalBytes = documents.reduce((sum, d) => sum + (d.fileSizeBytes || 0), 0);
  const queuedOrUploaded = documents.filter((d) => d.status === "UPLOADED" || d.status === "QUEUED").length;
  const extracting = documents.filter((d) => d.status === "EXTRACTING").length;
  const embedding = documents.filter((d) => d.status === "EMBEDDING").length;
  return {
    totalFiles: documents.length,
    totalBytes,
    queuedOrUploaded,
    extracting,
    embedding,
    ready: documents.filter((d) => d.status === "READY").length,
    failed: documents.filter((d) => d.status === "FAILED").length,
    // Real "is the pipeline doing anything right now" flag - true while any
    // document sits in a non-terminal KnowledgeDocumentStatus, same set
    // DOCUMENT_POLL_INTERVAL_MS polls against. Drives the glow below;
    // never a fixed-duration animation, so it stops the instant the last
    // document actually reaches READY/FAILED, not on a timer.
    active: queuedOrUploaded + extracting + embedding > 0,
  };
}
