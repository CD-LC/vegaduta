package com.agenticai.core.agents.skills;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A tenant's custom skill (migration V235). Built-in skills have no row - see {@link
 * BuiltInSkillCatalog}. Tenant-schema table: the schema is the tenancy, so no tenant_id.
 */
@com.agenticai.core.security.TenantConfigEntity
// DynamicUpdate: uses/successes/failures/last_used_at are bumped by bulk UPDATEs (SkillRepository);
// a full-row UPDATE from an instance loaded earlier would write its stale copy of them back.
@DynamicUpdate
@Entity
@Table(name = "skill", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class Skill {

    @Id private UUID id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 1024)
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ---- Supply-chain pin (migration V423) ----------------------------------
    // approved_sha256 is the content hash an owner explicitly accepted; when it
    // differs from content_sha256 the skill changed since approval and is
    // NEEDS_REVIEW. approved_manifest holds one hash per component so the review
    // UI can say WHAT changed. scan_risk caches the last static scan's risk
    // level so the chat-turn path can gate without re-scanning. All nullable:
    // null approved_sha256 = no approval recorded yet (UNPINNED). See
    // SkillSupplyChainService / SkillIntegrity.
    @Column(name = "approved_sha256", length = 64)
    private String approvedSha256;

    @Column(name = "approved_by", length = 128)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_manifest", columnDefinition = "TEXT")
    private String approvedManifest;

    @Column(name = "scan_risk", length = 16)
    private String scanRisk;

    // ---- Staged self-improvement (migration V243) --------------------------
    // An agent-proposed improvement to THIS skill, parked in parallel columns
    // until a workspace admin applies or discards it - the live
    // description/body/files above stay exactly as reviewed while these sit
    // here. All four pending_* columns are null together or populated
    // together (stageImprovement writes them as one unit; apply/clear null
    // them as one unit); pending_proposed_at IS NULL is the "no staged
    // change" check. See SkillService#proposeImprovement.
    @Column(name = "pending_description", length = 1024)
    private String pendingDescription;

    @Column(name = "pending_body", columnDefinition = "TEXT")
    private String pendingBody;

    /**
     * JSONB map {path: content} - staged Level-3 files, applied through the same validateFiles
     * limits as an import. {@code @JdbcTypeCode(JSON)} binds the String as jsonb (JAVA-011):
     * without it Hibernate binds VARCHAR and every non-null write fails with "column is of type
     * jsonb but expression is of type character varying".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_files", columnDefinition = "jsonb")
    private String pendingFilesJson;

    @Column(name = "pending_rationale", columnDefinition = "TEXT")
    private String pendingRationale;

    @Column(name = "pending_proposed_by", length = 128)
    private String pendingProposedBy;

    @Column(name = "pending_proposed_at")
    private Instant pendingProposedAt;

    // ---- Lifecycle, versioning and usage (migration V590) ------------------
    // `enabled` above stays the only runtime gate; the application keeps enabled == (status ==
    // ACTIVE) (see setEnabled and the lifecycle methods below). uses/successes/failures/lastUsedAt
    // are written by bulk UPDATEs in SkillRepository, never through this entity, so the values on
    // an instance loaded earlier in the same session can be stale - read them for display only.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SkillStatus status = SkillStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 16)
    private SkillOrigin origin = SkillOrigin.AUTHORED;

    @Column(name = "content_version", nullable = false)
    private int contentVersion = 1;

    @Column(name = "uses", nullable = false)
    private long uses;

    @Column(name = "successes", nullable = false)
    private long successes;

    @Column(name = "failures", nullable = false)
    private long failures;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    /** The agent whose turn the learning loop drafted this from; null for everything else. */
    @Column(name = "learned_for_agent_id")
    private UUID learnedForAgentId;

    /** PASS, WEAK or FAIL from the learning loop's self-check of a drafted skill; null = not checked (V653). */
    @Column(name = "self_check_verdict", length = 8)
    private String selfCheckVerdict;

    @Column(name = "self_check_reason", length = 300)
    private String selfCheckReason;

    @Column(name = "self_checked_at")
    private Instant selfCheckedAt;

    /** JSON text of the optional frontmatter keys (license, compatibility, metadata, allowed-tools). */
    @Column(name = "frontmatter_extra", columnDefinition = "TEXT")
    private String frontmatterExtra;

    // LAZY + explicit join-fetch where needed: the chat-turn hot path only needs name/description
    // (Level 1), and pulling every bundled file on every turn would defeat progressive disclosure
    // at the database layer too.
    @OneToMany(
            mappedBy = "skill",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("path ASC")
    private List<SkillFile> files = new ArrayList<>();

    protected Skill() {}

    public Skill(String name, String description, String body, String createdBy) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.description = description;
        this.body = body;
        this.enabled = true;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateContent(String description, String body) {
        this.description = description;
        this.body = body;
        this.updatedAt = Instant.now();
    }

    public void replaceFiles(Map<String, String> newFiles) {
        files.clear();
        newFiles.forEach((path, content) -> files.add(new SkillFile(this, path, content)));
        this.updatedAt = Instant.now();
    }

    public Map<String, String> filesAsMap() {
        Map<String, String> map = new LinkedHashMap<>();
        files.forEach(f -> map.put(f.getPath(), f.getContent()));
        return map;
    }

    public SkillDefinition toDefinition() {
        return new SkillDefinition(id, name, description, body, filesAsMap(), SkillSource.CUSTOM);
    }

    /**
     * Stages an agent-proposed improvement (V243). The live columns are untouched - nothing an
     * agent proposes reaches a prompt until {@link #applyStagedImprovement} runs under an admin
     * request.
     */
    public void stageImprovement(
            String description,
            String body,
            String filesJson,
            String rationale,
            String proposedBy) {
        this.pendingDescription = description;
        this.pendingBody = body;
        this.pendingFilesJson = filesJson;
        this.pendingRationale = rationale;
        this.pendingProposedBy = proposedBy;
        this.pendingProposedAt = Instant.now();
    }

    /**
     * @return {@code true} while an improvement proposal waits for admin review.
     */
    public boolean hasStagedImprovement() {
        return pendingProposedAt != null;
    }

    /**
     * Promotes the staged improvement into the live columns and clears the staging area. Callers
     * ({@code SkillService#applyImprovement}) run the usual sha256/content housekeeping after this.
     */
    public void applyStagedImprovement(Map<String, String> stagedFiles) {
        this.description = pendingDescription;
        this.body = pendingBody;
        if (stagedFiles != null) {
            replaceFiles(stagedFiles);
        }
        clearStagedImprovement();
        this.updatedAt = Instant.now();
    }

    /** Drops the staged improvement without touching the live content. */
    public void clearStagedImprovement() {
        this.pendingDescription = null;
        this.pendingBody = null;
        this.pendingFilesJson = null;
        this.pendingRationale = null;
        this.pendingProposedBy = null;
        this.pendingProposedAt = null;
    }

    public String getPendingDescription() {
        return pendingDescription;
    }

    public String getPendingBody() {
        return pendingBody;
    }

    public String getPendingFilesJson() {
        return pendingFilesJson;
    }

    public String getPendingRationale() {
        return pendingRationale;
    }

    public String getPendingProposedBy() {
        return pendingProposedBy;
    }

    public Instant getPendingProposedAt() {
        return pendingProposedAt;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            this.status = SkillStatus.ACTIVE;
        } else if (this.status == SkillStatus.ACTIVE) {
            this.status = SkillStatus.ARCHIVED;
        }
        this.updatedAt = Instant.now();
    }

    /** Parks the skill as a draft awaiting review: not live, not yet declined. */
    public void markPending(SkillOrigin origin, UUID learnedForAgentId, String note) {
        this.reviewNote = note;
        this.enabled = false;
        this.status = SkillStatus.PENDING;
        this.origin = origin;
        this.learnedForAgentId = learnedForAgentId;
        this.updatedAt = Instant.now();
    }

    /** A reviewer declined the draft; {@code note} (optional) is what they said. */
    public void reject(String note) {
        this.enabled = false;
        this.status = SkillStatus.REJECTED;
        this.reviewNote = note;
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.enabled = false;
        this.status = SkillStatus.ARCHIVED;
        this.updatedAt = Instant.now();
    }

    /** Live content changed: the next version number. */
    public int bumpVersion() {
        return ++this.contentVersion;
    }

    public SkillStatus getStatus() {
        return status;
    }

    public SkillOrigin getOrigin() {
        return origin;
    }

    public void setOrigin(SkillOrigin origin) {
        this.origin = origin;
    }

    public int getContentVersion() {
        return contentVersion;
    }

    public long getUses() {
        return uses;
    }

    public long getSuccesses() {
        return successes;
    }

    public long getFailures() {
        return failures;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public UUID getLearnedForAgentId() {
        return learnedForAgentId;
    }

    public String getSelfCheckVerdict() {
        return selfCheckVerdict;
    }

    public String getSelfCheckReason() {
        return selfCheckReason;
    }

    public Instant getSelfCheckedAt() {
        return selfCheckedAt;
    }

    public String getFrontmatterExtra() {
        return frontmatterExtra;
    }

    public void setFrontmatterExtra(String frontmatterExtra) {
        this.frontmatterExtra = frontmatterExtra;
    }

    public void setContentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    /** Records an owner's approval of exactly this content hash (V423). */
    public void approve(String sha256, String by, String manifestJson) {
        this.approvedSha256 = sha256;
        this.approvedBy = by;
        this.approvedAt = Instant.now();
        this.approvedManifest = manifestJson;
    }

    public void setScanRisk(String scanRisk) {
        this.scanRisk = scanRisk;
    }

    public String getApprovedSha256() {
        return approvedSha256;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getApprovedManifest() {
        return approvedManifest;
    }

    public String getScanRisk() {
        return scanRisk;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getBody() {
        return body;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<SkillFile> getFiles() {
        return files;
    }
}
