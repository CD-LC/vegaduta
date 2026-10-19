package com.agenticai.core.agents.skills;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Supply-chain controls for skills: static scan, content-hash pinning and the re-approval gate.
 *
 * <p>Two switches, both under {@code app.skills.supply-chain}:
 *
 * <ul>
 *   <li>{@code enabled} (default true) - scan, report and record pins. Never changes what an agent
 *       can load.
 *   <li>{@code enforce} (default false) - additionally withhold from {@code load_skill} a custom
 *       skill whose content changed since an owner approved it, or that scans HIGH and has not been
 *       approved at its current content. Built-in (classpath) skills are NEVER withheld; they are
 *       only reported.
 * </ul>
 *
 * <p>A scan reduces risk, it does not prove safety - the copy in every report says so, and the pin
 * is what makes a later silent edit visible.
 *
 * <p>The hot path ({@link #blocks}) reads only stored columns ({@code content_sha256}, {@code
 * approved_sha256}, {@code scan_risk}): no body, no files, no regex, so per-turn skill metadata
 * stays two small indexed reads. Fresh scans happen on write paths and on the report endpoints, and
 * are cached by content hash.
 */
@Service
public class SkillSupplyChainService {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int MAX_CACHE = 256;

    private final SkillRepository skills;
    private final BuiltInSkillCatalog builtIns;
    private final AgentSkillConfigRepository agentConfigs;
    private final boolean enabled;
    private final boolean enforce;
    private final Map<String, SkillScanner.Report> cache = new ConcurrentHashMap<>();

    public SkillSupplyChainService(
            SkillRepository skills,
            BuiltInSkillCatalog builtIns,
            AgentSkillConfigRepository agentConfigs,
            @Value("${app.skills.supply-chain.enabled:true}") boolean enabled,
            @Value("${app.skills.supply-chain.enforce:false}") boolean enforce) {
        this.skills = skills;
        this.builtIns = builtIns;
        this.agentConfigs = agentConfigs;
        this.enabled = enabled;
        this.enforce = enforce;
    }

    // ------------------------------------------------------------------ DTOs

    /** Everything the review UI needs about one skill. */
    public record SkillSecurityReport(
            String name,
            String source,
            String status,
            boolean blocked,
            boolean enforcing,
            String contentSha256,
            String approvedSha256,
            String approvedBy,
            Instant approvedAt,
            List<String> changes,
            SkillScanner.Report scan,
            SkillScanner.Report stagedScan,
            List<UUID> attachedAgentIds,
            String disclaimer) {}

    public record SkillSecuritySummary(
            String name,
            String source,
            String status,
            SkillScanner.RiskLevel riskLevel,
            int findingCount,
            boolean blocked) {}

    public record SecurityOverview(
            boolean enabled, boolean enforcing, List<SkillSecuritySummary> skills) {}

    /**
     * One skill waiting on an owner. {@code reason} is {@code CHANGED} (content differs from the
     * approved hash) or {@code HIGH_RISK} (scans high and was never approved at this content).
     * {@code attachedAgentIds} are agents whose per-agent allow-list names this skill explicitly;
     * agents without an allow-list use every enabled skill.
     */
    public record SkillReviewItem(
            UUID skillId,
            String name,
            String reason,
            String status,
            SkillScanner.RiskLevel riskLevel,
            List<String> changes,
            Instant approvedAt,
            String approvedBy,
            boolean enabled,
            List<UUID> attachedAgentIds) {}

    /** Result of scanning a candidate (draft, upload or registry skill) before it is saved. */
    public record PreviewResult(
            String name,
            boolean replacesExisting,
            SkillScanner.Report scan,
            String disclaimer,
            String contentSha256) {}

    /** Longest SKILL.md the draft preview scans: the body cap plus room for front matter. */
    static final int MAX_DRAFT_CHARS = SkillService.MAX_BODY_CHARS + 4_096;

    static final String DISCLAIMER =
            "A scan reduces risk; it does not prove a skill is safe. Read what a skill tells your agents to do before you approve it.";

    // ------------------------------------------------------------------ switches

    public boolean isEnabled() {
        return enabled;
    }

    /** Enforcement only means anything while scanning/pinning is on. */
    public boolean isEnforcing() {
        return enabled && enforce;
    }

    // ------------------------------------------------------------------ hot path (stored columns
    // only)

    /**
     * True when {@code load_skill} must withhold this custom skill. Never true unless enforcing.
     */
    public boolean blocks(Skill skill) {
        if (!isEnforcing() || skill == null) {
            return false;
        }
        SkillIntegrity.Status status =
                SkillIntegrity.status(skill.getContentSha256(), skill.getApprovedSha256());
        return SkillIntegrity.blocked(true, status, riskForGate(skill, status));
    }

    public Optional<String> blockedReason(Skill skill) {
        if (!blocks(skill)) {
            return Optional.empty();
        }
        SkillIntegrity.Status status =
                SkillIntegrity.status(skill.getContentSha256(), skill.getApprovedSha256());
        return Optional.of(
                SkillIntegrity.blockedReason(skill.getName(), status, riskForGate(skill, status)));
    }

    /**
     * The risk the gate decides on. Normally the stored {@code scan_risk}. That column is NULL for
     * every skill that existed before V423 and is only filled once a report, review or write path
     * has touched the skill, so a legacy HIGH-risk skill nobody has opened would otherwise read as
     * "no risk" and slip past the gate. When the column is empty AND the answer could matter (the
     * skill has no approval at its current content), scan once here - the result is cached by
     * content hash, so later turns cost a map lookup. Nothing is written: this runs inside
     * read-only chat-turn transactions, and the report endpoints persist the value the next time
     * they run.
     */
    private SkillScanner.RiskLevel riskForGate(Skill skill, SkillIntegrity.Status status) {
        SkillScanner.RiskLevel stored = SkillScanner.RiskLevel.fromWire(skill.getScanRisk());
        if (stored != null
                || status == SkillIntegrity.Status.APPROVED
                || status == SkillIntegrity.Status.NEEDS_REVIEW) {
            return stored;
        }
        return scanSkill(skill).riskLevel();
    }

    // ------------------------------------------------------------------ write-path hooks (inside
    // SkillService's transaction)

    /** Refreshes the cached scan risk after a content change. No-op while the feature is off. */
    public void stampScan(Skill skill) {
        if (!enabled || skill == null) {
            return;
        }
        SkillScanner.Report report = scanSkill(skill);
        skill.setScanRisk(report.riskLevel().wire());
    }

    /**
     * Trust-on-first-use pin: when a skill with no approval on record is attached to an agent or
     * switched on by a human, remember its current hash as approved - unless it scans HIGH, which
     * always needs an explicit {@link #approve}. A skill that already carries an approval is left
     * alone, so a later edit still shows up as NEEDS_REVIEW.
     *
     * @return true when a pin was recorded
     */
    public boolean pinIfUnpinned(Skill skill, String actor) {
        if (!enabled || skill == null || skill.getApprovedSha256() != null) {
            return false;
        }
        SkillScanner.Report report = scanSkill(skill);
        skill.setScanRisk(report.riskLevel().wire());
        if (report.riskLevel() == SkillScanner.RiskLevel.HIGH) {
            return false;
        }
        String hash = hashOf(skill);
        skill.setContentSha256(hash);
        skill.approve(hash, truncate(actor, 128), manifestOf(skill));
        skills.save(skill);
        return true;
    }

    /**
     * Re-records an approval after an admin deliberately applied a reviewed rewrite of a skill that
     * already carried one, so applying the change does not leave it NEEDS_REVIEW and demand a
     * second click. Left alone (still needs an explicit {@link #approve}) when the skill was never
     * approved or the new content scans HIGH.
     */
    public boolean reapproveAfterAdminEdit(Skill skill, String actor) {
        if (!enabled || skill == null || skill.getApprovedSha256() == null) {
            return false;
        }
        SkillScanner.Report report = scanSkill(skill);
        skill.setScanRisk(report.riskLevel().wire());
        if (report.riskLevel() == SkillScanner.RiskLevel.HIGH) {
            return false;
        }
        String hash = hashOf(skill);
        skill.setContentSha256(hash);
        skill.approve(hash, truncate(actor, 128), manifestOf(skill));
        return true;
    }

    // ------------------------------------------------------------------ reports

    /**
     * The scan + pin state of one skill (built-in or custom). 404 when scanning is off or the skill
     * is unknown.
     */
    @Transactional
    public SkillSecurityReport report(String name) {
        requireEnabled();
        String wanted = name == null ? "" : name.trim();
        Optional<SkillDefinition> builtIn = builtIns.find(wanted);
        if (builtIn.isPresent()) {
            SkillDefinition def = builtIn.get();
            String hash = SkillIntegrity.hash(def);
            SkillScanner.Report scan =
                    scanCached(def.name(), def.description(), def.body(), def.files(), hash);
            return new SkillSecurityReport(
                    def.name(),
                    SkillSource.BUILT_IN.name(),
                    SkillIntegrity.Status.BUILT_IN.name(),
                    false,
                    isEnforcing(),
                    hash,
                    null,
                    null,
                    null,
                    List.of(),
                    scan,
                    null,
                    List.of(),
                    DISCLAIMER);
        }
        Skill skill =
                skills.findByNameWithFiles(wanted)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "No skill \"" + wanted + "\" in this workspace."));
        String hash = hashOf(skill);
        SkillIntegrity.Status status = SkillIntegrity.status(hash, skill.getApprovedSha256());
        SkillScanner.Report scan =
                scanCached(
                        skill.getName(),
                        skill.getDescription(),
                        skill.getBody(),
                        skill.filesAsMap(),
                        hash);
        syncStored(skill, hash, scan);
        List<String> changes =
                status == SkillIntegrity.Status.NEEDS_REVIEW
                        ? SkillIntegrity.diffSummary(skill.getApprovedManifest(), manifestOf(skill))
                        : List.of();
        boolean blocked = SkillIntegrity.blocked(isEnforcing(), status, scan.riskLevel());
        return new SkillSecurityReport(
                skill.getName(),
                SkillSource.CUSTOM.name(),
                status.name(),
                blocked,
                isEnforcing(),
                hash,
                skill.getApprovedSha256(),
                skill.getApprovedBy(),
                skill.getApprovedAt(),
                changes,
                scan,
                scanStaged(skill),
                attachments().getOrDefault(skill.getName(), List.of()),
                DISCLAIMER);
    }

    /**
     * Per-skill status + risk for the skills list badges. Reports state; never changes what loads.
     */
    @Transactional
    public SecurityOverview overview() {
        if (!enabled) {
            return new SecurityOverview(false, false, List.of());
        }
        List<SkillSecuritySummary> out = new ArrayList<>();
        for (SkillDefinition def : builtIns.all()) {
            String hash = SkillIntegrity.hash(def);
            SkillScanner.Report scan =
                    scanCached(def.name(), def.description(), def.body(), def.files(), hash);
            out.add(
                    new SkillSecuritySummary(
                            def.name(),
                            SkillSource.BUILT_IN.name(),
                            SkillIntegrity.Status.BUILT_IN.name(),
                            scan.riskLevel(),
                            scan.findings().size(),
                            false));
        }
        for (Skill skill : skills.findAllByOrderByNameAsc()) {
            String stored = skill.getContentSha256();
            SkillScanner.Report scan =
                    stored == null ? null : cache.get(cacheKey(skill.getName(), stored));
            if (scan == null) {
                String hash = hashOf(skill);
                scan =
                        scanCached(
                                skill.getName(),
                                skill.getDescription(),
                                skill.getBody(),
                                skill.filesAsMap(),
                                hash);
                stored = hash;
            }
            syncStored(skill, stored, scan);
            SkillIntegrity.Status status = SkillIntegrity.status(stored, skill.getApprovedSha256());
            out.add(
                    new SkillSecuritySummary(
                            skill.getName(),
                            SkillSource.CUSTOM.name(),
                            status.name(),
                            scan.riskLevel(),
                            scan.findings().size(),
                            SkillIntegrity.blocked(isEnforcing(), status, scan.riskLevel())));
        }
        return new SecurityOverview(true, isEnforcing(), out);
    }

    /**
     * Custom skills waiting on an owner: content changed since approval, or high risk and never
     * approved. Skills that are switched off are left out - rejecting a skill turns it off.
     */
    @Transactional
    public List<SkillReviewItem> reviews() {
        requireEnabled();
        Map<String, List<UUID>> attached = attachments();
        List<SkillReviewItem> out = new ArrayList<>();
        for (Skill skill : skills.findAllByOrderByNameAsc()) {
            String hash = hashOf(skill);
            SkillScanner.Report scan =
                    scanCached(
                            skill.getName(),
                            skill.getDescription(),
                            skill.getBody(),
                            skill.filesAsMap(),
                            hash);
            syncStored(skill, hash, scan);
            if (!skill.isEnabled()) {
                // Switched off (rejected, or an agent draft nobody turned on): agents cannot load
                // it, so it
                // is not waiting on anyone. It returns to the queue if it is switched on again
                // unapproved.
                continue;
            }
            SkillIntegrity.Status status = SkillIntegrity.status(hash, skill.getApprovedSha256());
            String reason = null;
            if (status == SkillIntegrity.Status.NEEDS_REVIEW) {
                reason = "CHANGED";
            } else if (status == SkillIntegrity.Status.UNPINNED
                    && scan.riskLevel() == SkillScanner.RiskLevel.HIGH) {
                reason = "HIGH_RISK";
            }
            if (reason == null) {
                continue;
            }
            List<String> changes =
                    status == SkillIntegrity.Status.NEEDS_REVIEW
                            ? SkillIntegrity.diffSummary(
                                    skill.getApprovedManifest(), manifestOf(skill))
                            : List.of();
            out.add(
                    new SkillReviewItem(
                            skill.getId(),
                            skill.getName(),
                            reason,
                            status.name(),
                            scan.riskLevel(),
                            changes,
                            skill.getApprovedAt(),
                            skill.getApprovedBy(),
                            skill.isEnabled(),
                            attached.getOrDefault(skill.getName(), List.of())));
        }
        return out;
    }

    /**
     * Owner approval of the CURRENT content. {@code expectedSha256} is what the reviewer was
     * looking at; if the skill changed again in the meantime this answers 409 instead of approving
     * text nobody read. A HIGH-risk skill additionally needs {@code acknowledgeHighRisk}.
     */
    @Transactional
    public SkillSecurityReport approve(
            String name, String expectedSha256, boolean acknowledgeHighRisk, String actor) {
        requireEnabled();
        String wanted = name == null ? "" : name.trim();
        if (builtIns.contains(wanted)) {
            throw new SkillValidationException(
                    "Built-in skills ship with the platform and don't need approval.");
        }
        Skill skill =
                skills.findByNameWithFiles(wanted)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "No skill \"" + wanted + "\" in this workspace."));
        String hash = hashOf(skill);
        if (expectedSha256 != null
                && !expectedSha256.isBlank()
                && !expectedSha256.trim().equalsIgnoreCase(hash)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This skill changed while you were reviewing it. Reload and review the latest content before approving.");
        }
        SkillScanner.Report scan = scanSkill(skill);
        if (scan.riskLevel() == SkillScanner.RiskLevel.HIGH && !acknowledgeHighRisk) {
            throw new SkillValidationException(
                    "A security scan rated this skill high risk. Confirm that you have read the findings to approve it anyway.");
        }
        skill.setContentSha256(hash);
        skill.setScanRisk(scan.riskLevel().wire());
        skill.approve(hash, truncate(actor, 128), manifestOf(skill));
        skills.save(skill);
        return report(wanted);
    }

    // ------------------------------------------------------------------ previews (nothing is
    // saved)

    /** Scans a SKILL.md typed in the editor before it is saved. */
    @Transactional(readOnly = true)
    public PreviewResult previewDraft(String skillMd) {
        requireEnabled();
        if (skillMd != null && skillMd.length() > MAX_DRAFT_CHARS) {
            throw new SkillValidationException(
                    "This SKILL.md is too long to scan ("
                            + skillMd.length()
                            + " characters, maximum "
                            + MAX_DRAFT_CHARS
                            + ").");
        }
        SkillMarkdownParser.Parsed parsed = SkillMarkdownParser.parse(skillMd);
        SkillScanner.Report scan =
                SkillScanner.scan(parsed.name(), parsed.description(), parsed.body(), Map.of());
        return new PreviewResult(
                parsed.name(),
                exists(parsed.name()),
                scan,
                DISCLAIMER,
                SkillIntegrity.hash(parsed.description(), parsed.body(), Map.of()));
    }

    /**
     * Scans an uploaded or registry-fetched skill .zip before it is imported. Same limits as an
     * import.
     */
    @Transactional(readOnly = true)
    public PreviewResult previewArchive(InputStream zip) {
        requireEnabled();
        SkillArchiveReader.Archive archive = SkillArchiveReader.read(zip);
        SkillMarkdownParser.Parsed parsed = archive.skill();
        SkillScanner.Report scan =
                SkillScanner.scan(
                        parsed.name(), parsed.description(), parsed.body(), archive.files());
        return new PreviewResult(
                parsed.name(), exists(parsed.name()), scan, DISCLAIMER, archiveHash(archive));
    }

    /**
     * Canonical SHA-256 of what an archive would install (description, body and files as parsed).
     * The preview returns it and the install re-computes it on the freshly fetched archive, so the
     * content an admin confirmed is provably the content that gets stored.
     */
    public static String archiveHash(SkillArchiveReader.Archive archive) {
        SkillMarkdownParser.Parsed parsed = archive.skill();
        return SkillIntegrity.hash(parsed.description(), parsed.body(), archive.files());
    }

    // ------------------------------------------------------------------ helpers

    private boolean exists(String name) {
        return builtIns.contains(name) || skills.existsByName(name);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Skill security scanning is turned off.");
        }
    }

    private Map<String, List<UUID>> attachments() {
        Map<String, List<UUID>> out = new HashMap<>();
        for (AgentSkillConfig config : agentConfigs.findAll()) {
            for (String skillName : config.getAllowedSkillNames()) {
                out.computeIfAbsent(skillName, k -> new ArrayList<>()).add(config.getAgentId());
            }
        }
        return out;
    }

    private SkillScanner.Report scanSkill(Skill skill) {
        return scanCached(
                skill.getName(),
                skill.getDescription(),
                skill.getBody(),
                skill.filesAsMap(),
                hashOf(skill));
    }

    private SkillScanner.Report scanCached(
            String name, String description, String body, Map<String, String> files, String hash) {
        String key = cacheKey(name, hash);
        SkillScanner.Report hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        SkillScanner.Report fresh = SkillScanner.scan(name, description, body, files);
        if (cache.size() >= MAX_CACHE) {
            cache.clear();
        }
        cache.put(key, fresh);
        return fresh;
    }

    private static String cacheKey(String name, String hash) {
        return SkillScanner.SCANNER_VERSION + "|" + name + "|" + hash;
    }

    /**
     * Keeps the stored columns the hot path reads in step with a fresh computation (idempotent).
     */
    private void syncStored(Skill skill, String hash, SkillScanner.Report scan) {
        boolean dirty = false;
        if (!hash.equals(skill.getContentSha256())) {
            skill.setContentSha256(hash);
            dirty = true;
        }
        String wire = scan.riskLevel().wire();
        if (!wire.equals(skill.getScanRisk())) {
            skill.setScanRisk(wire);
            dirty = true;
        }
        if (dirty) {
            skills.save(skill);
        }
    }

    private SkillScanner.Report scanStaged(Skill skill) {
        if (!skill.hasStagedImprovement()) {
            return null;
        }
        Map<String, String> files = Map.of();
        String json = skill.getPendingFilesJson();
        if (json != null && !json.isBlank()) {
            try {
                files = MAPPER.readValue(json, new TypeReference<Map<String, String>>() {});
            } catch (JsonProcessingException e) {
                files = Map.of();
            }
        }
        return SkillScanner.scan(
                skill.getName(), skill.getPendingDescription(), skill.getPendingBody(), files);
    }

    private static String hashOf(Skill skill) {
        return SkillIntegrity.hash(skill.getDescription(), skill.getBody(), skill.filesAsMap());
    }

    private static String manifestOf(Skill skill) {
        return SkillIntegrity.manifestJson(
                skill.getDescription(), skill.getBody(), skill.filesAsMap());
    }

    private static String truncate(String s, int max) {
        String v = s == null || s.isBlank() ? "unknown" : s.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
