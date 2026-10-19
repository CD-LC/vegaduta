package com.agenticai.core.workflow.poll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A Trigger node's HTTP-polling binding (V482): "when a new item appears at
 * this endpoint, run the workflow with the item as input". The complement to
 * the webhook trigger - webhooks need the sender to reach in, which most SaaS
 * APIs cannot do against a self-hosted install; polling reaches OUT on a
 * cadence and dedupes on a per-item id, so "new row / new email / new event"
 * works with nothing but a plain HTTP GET upstream.
 *
 * <p>{@code nodeId} is UNIQUE for the same reason {@code workflow_trigger_node}
 * keys on it: a trigger node owns exactly one binding whatever its kind.
 * {@code headersEncrypted} is AES-256-GCM ciphertext of a JSON object of
 * request headers (it can carry an Authorization header or API key) - same
 * contract as {@code workflow_trigger_node.webhook_token_encrypted}, written
 * only through {@code EncryptionService}.
 *
 * <p>{@code stateJson} is the whole persistent poll state: {@code
 * {"seeded":bool,"seenIds":[...]}} - a bounded ring of recently-seen item ids
 * that makes restarts and at-least-once polling safe without a second table.
 * Bounded by {@code app.workflow.poll.seen-ids-cap}.
 */
@Entity
@Table(name = "workflow_poll_trigger")
public class WorkflowPollTrigger {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";
    /** First successful poll when seedOnEnable is set: existing items are recorded, never fired. */
    public static final String STATUS_SEEDED = "SEEDED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "node_id", nullable = false, unique = true, length = 64)
    private String nodeId;

    @Column(nullable = false, length = 4096)
    private String url;

    @Column(nullable = false, length = 8)
    private String method = "GET";

    @Column(name = "headers_encrypted")
    private String headersEncrypted;

    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds = 300;

    @Column(name = "items_path", nullable = false, length = 256)
    private String itemsPath = "";

    @Column(name = "id_path", nullable = false, length = 256)
    private String idPath = "id";

    @Column(name = "seed_on_enable", nullable = false)
    private boolean seedOnEnable = true;

    @Column(name = "state_json")
    private String stateJson;

    @Column(name = "next_poll_at", nullable = false)
    private Instant nextPollAt;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_status", length = 16)
    private String lastStatus;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowPollTrigger() {
        // JPA
    }

    public WorkflowPollTrigger(UUID workflowId, String nodeId) {
        this.workflowId = workflowId;
        this.nodeId = nodeId;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.nextPollAt = now;
    }

    /**
     * Reconfigure from the validated request shape - resets {@code stateJson}
     * (a different endpoint/paths means the old seen-id ring would silently
     * suppress everything new) and schedules the first poll immediately.
     */
    public void configure(String url, String method, String headersEncrypted, int intervalSeconds,
                          String itemsPath, String idPath, boolean seedOnEnable) {
        this.url = url;
        this.method = method;
        this.headersEncrypted = headersEncrypted;
        this.intervalSeconds = intervalSeconds;
        this.itemsPath = itemsPath;
        this.idPath = idPath;
        this.seedOnEnable = seedOnEnable;
        this.stateJson = null;
        this.enabled = true;
        this.consecutiveFailures = 0;
        this.lastStatus = null;
        this.nextPollAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Records one completed poll. {@code failures == 0} is a success: the next
     * poll lands one interval out. A failure backs off linearly ({@code
     * interval * min(failures, backoffCap)}) so a dead endpoint does not get
     * hit at full cadence forever, but recovers promptly when it comes back.
     */
    public void markPolled(String status, String stateJson, int failures, int backoffCap) {
        Instant now = Instant.now();
        this.lastStatus = status;
        this.stateJson = stateJson;
        this.lastPolledAt = now;
        this.consecutiveFailures = failures;
        int multiplier = failures <= 0 ? 1 : Math.min(failures + 1, Math.max(1, backoffCap));
        this.nextPollAt = now.plusSeconds((long) intervalSeconds * multiplier);
        this.updatedAt = now;
    }

    public void disable() {
        this.enabled = false;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getUrl() {
        return url;
    }

    public String getMethod() {
        return method;
    }

    public String getHeadersEncrypted() {
        return headersEncrypted;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public String getItemsPath() {
        return itemsPath;
    }

    public String getIdPath() {
        return idPath;
    }

    public boolean isSeedOnEnable() {
        return seedOnEnable;
    }

    public String getStateJson() {
        return stateJson;
    }

    public Instant getNextPollAt() {
        return nextPollAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public Instant getLastPolledAt() {
        return lastPolledAt;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }
}
