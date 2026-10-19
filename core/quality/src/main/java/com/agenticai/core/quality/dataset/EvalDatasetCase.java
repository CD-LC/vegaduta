package com.agenticai.core.quality.dataset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One golden case inside an {@link EvalDataset}: the user turn to replay,
 * plus optional expectations. Pure composition - a case has no meaning
 * outside its dataset, which is why (unlike every agent_id in this package)
 * {@code dataset_id} IS a real foreign key with ON DELETE CASCADE in V213.
 *
 * <p>Mapped as an independent entity rather than an {@code @OneToMany}
 * collection on {@link EvalDataset} so the run loop can stream cases without
 * loading the dataset aggregate, and case CRUD stays row-level (the same
 * shape {@code agent_job} chose over embedding).
 */
@Entity
@Table(name = "eval_dataset_case")
public class EvalDatasetCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(name = "user_message", nullable = false, columnDefinition = "TEXT")
    private String userMessage;

    /**
     * Optional reference answer. When present it is threaded to the ACCURACY
     * judge as ground truth via {@code EvaluationContext#expectedOutput} -
     * it is judge context, not a deterministic string match (this workstream
     * reuses the existing judges and writes no new scorers).
     */
    @Column(name = "expected_output", columnDefinition = "TEXT")
    private String expectedOutput;

    /**
     * Optional JSON array of tool names the replayed turn is expected to
     * have called (e.g. {@code ["search_knowledge_base"]}). Stored as the
     * raw JSON text; parsed/validated by {@code EvalDatasetService} on write
     * and by {@code EvalRunService} at assertion time. Only assertable when
     * the dataset opted into tool execution - a tool-less replay reports the
     * assertion as not-evaluated (null), never as failed.
     */
    @Column(name = "expected_tools", columnDefinition = "TEXT")
    private String expectedTools;

    /**
     * Optional deterministic grader (V375) as raw JSON text - see {@code
     * com.agenticai.core.quality.grader.CodeGrader} for the shapes. NULL = no
     * code grader (the pre-V375 behaviour). Validated on write by {@code
     * EvalDatasetService#setCaseGrader}; part of the dataset content hash only
     * when present.
     */
    @Column(name = "grader_spec", columnDefinition = "TEXT")
    private String graderSpec;

    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EvalDatasetCase() {
        // JPA
    }

    public EvalDatasetCase(
        UUID datasetId, String userMessage, String expectedOutput, String expectedTools, int position
    ) {
        this.datasetId = datasetId;
        this.userMessage = userMessage;
        this.expectedOutput = expectedOutput;
        this.expectedTools = expectedTools;
        this.position = position;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Intention-named update; bumps {@code updatedAt} itself. */
    public void updateCase(String userMessage, String expectedOutput, String expectedTools, int position) {
        this.userMessage = userMessage;
        this.expectedOutput = expectedOutput;
        this.expectedTools = expectedTools;
        this.position = position;
        this.updatedAt = Instant.now();
    }

    /** Intention-named setter for the V375 grader; the caller validates the spec first. */
    public void applyGraderSpec(String graderSpec) {
        this.graderSpec = graderSpec;
        this.updatedAt = Instant.now();
    }

    /** Nullable - the raw code-grader spec (V375). */
    public String getGraderSpec() {
        return graderSpec;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDatasetId() {
        return datasetId;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getExpectedOutput() {
        return expectedOutput;
    }

    public String getExpectedTools() {
        return expectedTools;
    }

    public int getPosition() {
        return position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
