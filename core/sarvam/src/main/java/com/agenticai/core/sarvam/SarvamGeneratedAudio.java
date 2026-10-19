package com.agenticai.core.sarvam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A synthesized WAV clip produced by {@link SarvamTextToSpeechClient} via
 * {@code SarvamSpeakToolCallback},
 * stored so the tool can hand the calling LLM a retrievable reference
 * instead of raw base64 audio bytes in the tool result - the build plan is
 * explicit that inline base64 there "would blow the same context-window
 * budget the existing tool-output guardrail cap was built to prevent."
 *
 * <p><b>Why this is its own table, not literally the Output node's local-
 * file storage the build plan's prose points at:</b> that storage is two
 * columns on {@code core.workflow.WorkflowRun} ({@code outputFileName}/
 * {@code outputData}), scoped to one specific workflow run row - and {@code
 * core/workflow} depends on {@code core/agents} (where this tool callback
 * necessarily lives, alongside every other {@code ToolCallback}), never the
 * other way around. Depending on {@code core/workflow} from here to reuse
 * that table would be a genuine module cycle, not a design preference. This
 * table reuses the same PATTERN {@code WorkflowRun}/{@code
 * WorkflowController#downloadRunOutput} already established instead - bytes
 * persisted as a base64 {@code TEXT} column, served back through an
 * authenticated GET-by-id endpoint with a {@code Content-Disposition:
 * attachment} header - just as its own dedicated, tool-call-scoped table in
 * the one module both the tool callback and this storage can actually live
 * in together.
 *
 * <p>No {@code tenant_id} column, same as every other table in this module -
 * schema-per-tenant multi-tenancy already isolates one tenant's synthesized
 * clips from another's. No retention/pruning policy exists yet (out of
 * Stage 4's scope) - rows accumulate until a later stage adds one, the same
 * honest gap the build plan leaves for Stage 1's credential table until
 * Stage 2 added real validation.
 */
@Entity
@Table(name = "sarvam_generated_audio")
public class SarvamGeneratedAudio {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    /**
     * Legacy path only (migration V65) - base64-encoded audio bytes for rows
     * created before the storage migration. Null for every row created
     * since; see {@link #objectKey}. Never populated together with
     * objectKey - {@link SarvamGeneratedAudioService#store} always sets
     * exactly one of the two.
     */
    @Column(name = "audio_data", columnDefinition = "TEXT")
    private String audioDataBase64;

    /**
     * Storage migration Stage 3 - the {@link
     * com.agenticai.core.storage.FileStorageService} object key this row's
     * bytes actually live at, e.g. {@code "sarvam-audio/<uuid>.wav"}. Null
     * only for pre-migration rows (see {@link #audioDataBase64}).
     */
    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SarvamGeneratedAudio() {
        // JPA
    }

    /** Legacy constructor (base64-in-Postgres) - kept only so old code paths/tests referencing it still compile; new code should use {@link #forObjectStorage}. */
    public SarvamGeneratedAudio(String filename, String contentType, String audioDataBase64) {
        this.filename = filename;
        this.contentType = contentType;
        this.audioDataBase64 = audioDataBase64;
        this.createdAt = Instant.now();
    }

    private SarvamGeneratedAudio(String filename, String contentType, String objectKey, boolean objectStorage) {
        this.filename = filename;
        this.contentType = contentType;
        this.objectKey = objectKey;
        this.createdAt = Instant.now();
    }

    /** The one constructor {@link SarvamGeneratedAudioService#store} actually uses now - see {@link #objectKey}'s javadoc. */
    public static SarvamGeneratedAudio forObjectStorage(String filename, String contentType, String objectKey) {
        return new SarvamGeneratedAudio(filename, contentType, objectKey, true);
    }

    public UUID getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public String getAudioDataBase64() {
        return audioDataBase64;
    }

    /** Null for pre-migration rows - see this field's own javadoc. */
    public String getObjectKey() {
        return objectKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
