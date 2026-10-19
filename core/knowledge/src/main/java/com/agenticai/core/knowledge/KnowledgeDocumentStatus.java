package com.agenticai.core.knowledge;

/**
 * {@link KnowledgeDocument}'s lifecycle - real, disclosed pipeline stages,
 * not a fabricated live percentage. Both ingestion paths pass through the
 * same states:
 *
 * <ul>
 *   <li>{@code UPLOADED} - the row is created the instant
 *       {@code KnowledgeController.upload}/{@code uploadAudio} accepts the
 *       file, before any processing has happened.
 *   <li>{@code QUEUED} - async path only (audio/video): waiting for
 *       {@code KnowledgeIngestionJobRunner}'s next poll.
 *   <li>{@code EXTRACTING} - raw bytes are being turned into plain text:
 *       the sidecar {@code /extract} call for pdf/docx/html/image, the
 *       sidecar {@code /transcribe} call for audio/video, or (for {@code
 *       .md}) the trivial in-process decode - all three are real "bytes to
 *       text" work, so all three get this same honest label.
 *   <li>{@code EMBEDDING} - {@code SidecarClient.embed} is in flight for
 *       this file's extracted text.
 *   <li>{@code READY} - chunks are persisted in {@code knowledge_chunks};
 *       {@link KnowledgeDocument#getChunkCount()} is the real, final count.
 *   <li>{@code FAILED} - {@link KnowledgeDocument#getErrorMessage()} holds
 *       the real exception message that ended the pipeline early.
 * </ul>
 *
 * <p>Because the sync path (pdf/docx/html/md/image) runs synchronously
 * within one HTTP request, its EXTRACTING/EMBEDDING states are typically
 * visible for milliseconds, not minutes - this is stated honestly rather
 * than artificially slowed down or faked into a longer-looking animation.
 * The async path (audio/video, via {@code KnowledgeIngestionJobRunner}'s
 * 15s poll) is where these states are actually observable as "in progress"
 * for a meaningful duration.
 */
public enum KnowledgeDocumentStatus {
    UPLOADED,
    QUEUED,
    EXTRACTING,
    EMBEDDING,
    READY,
    FAILED
}
