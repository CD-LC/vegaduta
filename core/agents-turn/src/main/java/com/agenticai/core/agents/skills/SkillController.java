package com.agenticai.core.agents.skills;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.ListPaging;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Agent Skills management API. Reads are open to any workspace member; every write is
 * workspace-admin only, because a skill's name and description are injected into the system prompt
 * of every agent in the workspace - authoring one is closer to editing shared policy than to
 * editing a personal document.
 *
 * <p>{@link SkillValidationException} extends {@code IllegalArgumentException} so it lands in
 * {@code GlobalExceptionHandler}'s generic 400 with its person-facing message; the two statuses
 * that handler cannot infer are mapped locally below, in the same body shape.
 */
@RestController
public class SkillController {

    /** Compressed upload ceiling; the unpacked limits live in {@link SkillArchiveReader}. */
    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

    // Granular RBAC (2026-09-23): skill authoring tracks MANAGE_AGENTS - a
    // skill is injected into every agent's system prompt, which is exactly
    // the agent-administration surface. Grants bind admins too; platform-
    // admin keeps the role check (no tenant grants by design).
    private static final String PERM_MANAGE_AGENTS = "PERM_MANAGE_AGENTS";
    private static final String ROLE_PLATFORM_ADMIN = "ROLE_platform-admin";

    private final SkillService skillService;
    private final AgentService agentService;
    private final SkillRegistryClient registry;
    private final SkillDistillationService distillationService;
    private final SkillLifecycleService lifecycle;

    public SkillController(
            SkillService skillService,
            AgentService agentService,
            SkillRegistryClient registry,
            SkillDistillationService distillationService,
            SkillLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
        this.registry = registry;
        this.skillService = skillService;
        this.agentService = agentService;
        this.distillationService = distillationService;
    }

    /**
     * {@code createdBy} is the review provenance an admin needs at a glance ({@code "agent:*"}
     * marks machine-authored drafts awaiting approval); null on built-ins. {@code
     * pendingImprovement} marks a skill carrying a staged agent rewrite under {@code GET
     * .../improvement}.
     */
    public record SkillSummaryResponse(
            UUID id,
            String name,
            String description,
            String source,
            boolean enabled,
            int fileCount,
            String createdBy,
            boolean pendingImprovement,
            String status,
            String origin,
            int version,
            long uses,
            List<UUID> agentIds,
            Instant updatedAt,
            Double effectiveness,
            boolean stale,
            SkillLifecycleService.SelfCheck selfCheck) {
        static SkillSummaryResponse from(
                SkillService.SkillMetadata m, SkillLifecycleService.SkillView v) {
            boolean custom = v != null;
            return new SkillSummaryResponse(
                    m.id(),
                    m.name(),
                    m.description(),
                    m.source().name(),
                    m.enabled(),
                    m.fileCount(),
                    m.createdBy(),
                    m.pendingImprovement(),
                    custom ? v.status().name() : (m.enabled() ? "ACTIVE" : "ARCHIVED"),
                    custom ? v.origin().name() : SkillOrigin.IMPORTED.name(),
                    custom ? v.version() : 1,
                    custom ? v.uses() : 0,
                    custom ? v.agentIds() : List.of(),
                    custom ? v.updatedAt() : null,
                    custom ? v.effectiveness() : null,
                    custom && v.stale(),
                    custom ? v.selfCheck() : null);
        }
    }

    /** {@code size} is the contract name; {@code sizeBytes} is kept for existing clients. */
    public record SkillFileEntry(String path, int sizeBytes, int size) {
        SkillFileEntry(String path, int sizeBytes) {
            this(path, sizeBytes, sizeBytes);
        }
    }

    public record SkillDetailResponse(
            UUID id,
            String name,
            String description,
            String body,
            String source,
            String skillMd,
            List<SkillFileEntry> files,
            String status,
            String origin,
            int version,
            long uses,
            List<UUID> agentIds,
            Instant updatedAt,
            Double effectiveness,
            boolean stale,
            String staleReason,
            String reviewNote,
            Map<String, Object> frontmatter,
            SkillLifecycleService.SelfCheck selfCheck) {
        static SkillDetailResponse from(
                SkillDefinition d,
                SkillLifecycleService.SkillView v,
                Map<String, Object> extras) {
            List<SkillFileEntry> files =
                    d.files().entrySet().stream()
                            .map(
                                    e ->
                                            new SkillFileEntry(
                                                    e.getKey(),
                                                    e.getValue()
                                                            .getBytes(StandardCharsets.UTF_8)
                                                            .length))
                            .sorted(java.util.Comparator.comparing(SkillFileEntry::path))
                            .toList();
            boolean custom = v != null;
            return new SkillDetailResponse(
                    d.id(),
                    d.name(),
                    d.description(),
                    d.body(),
                    d.source().name(),
                    SkillMarkdownParser.render(d.name(), d.description(), d.body(), extras),
                    files,
                    custom ? v.status().name() : "ACTIVE",
                    custom ? v.origin().name() : SkillOrigin.IMPORTED.name(),
                    custom ? v.version() : 1,
                    custom ? v.uses() : 0,
                    custom ? v.agentIds() : List.of(),
                    custom ? v.updatedAt() : null,
                    custom ? v.effectiveness() : null,
                    custom && v.stale(),
                    custom ? v.staleReason() : null,
                    custom ? v.reviewNote() : null,
                    extras,
                    custom ? v.selfCheck() : null);
        }
    }

    public record SkillWriteRequest(String name, String description, String body, String skillMd) {}

    public record EnabledRequest(boolean enabled) {}

    public record ImportResponse(SkillDetailResponse skill, List<String> skippedPaths) {}

    public record AgentSkillsRequest(Boolean skillsEnabled, Set<String> allowedSkillNames) {}

    public record AgentSkillsResponse(
            boolean skillsEnabled,
            Set<String> allowedSkillNames,
            List<SkillSummaryResponse> activeSkills) {}

    // ------------------------------------------------------------------ catalog

    /** {@code ?status=PENDING|ACTIVE|REJECTED|ARCHIVED} narrows the page; absent lists everything. */
    @GetMapping("/api/skills")
    public ResponseEntity<List<SkillSummaryResponse>> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset,
            @RequestParam(required = false) String status) {
        SkillStatus wanted = parseStatus(status);
        Map<UUID, SkillLifecycleService.SkillView> views = lifecycle.views();
        List<SkillSummaryResponse> all =
                skillService.listAll().stream()
                        .map(m -> SkillSummaryResponse.from(m, m.id() == null ? null : views.get(m.id())))
                        .filter(r -> wanted == null || wanted.name().equals(r.status()))
                        .toList();
        return ListPaging.window(all, limit, offset, 50, 200);
    }

    /** {@code name} is a skill name or, for a custom skill, its id. */
    @GetMapping("/api/skills/{name}")
    public SkillDetailResponse get(@PathVariable String name) {
        return detail(resolveSkill(name));
    }

    @GetMapping(value = "/api/skills/{name}/file", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> file(@PathVariable String name, @RequestParam String path) {
        SkillDefinition skill = resolveSkill(name);
        String content = skill.files().get(path);
        if (content == null) {
            throw new SkillNotFoundException(name + "/" + path);
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(content);
    }

    /**
     * Accepts either a raw SKILL.md ({@code skillMd}) or the three fields - the editor sends
     * whichever it has.
     */
    @PostMapping("/api/skills")
    public ResponseEntity<SkillDetailResponse> create(
            @RequestBody SkillWriteRequest request, Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        SkillMarkdownParser.Parsed parsed = resolve(request, null);
        SkillDefinition created =
                skillService.create(
                        parsed.name(),
                        parsed.description(),
                        parsed.body(),
                        Map.of(),
                        subject(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(detail(created));
    }

    /**
     * Edits SKILL.md only; bundled files are replaced by re-importing the archive. The name is
     * immutable.
     */
    @PutMapping("/api/skills/{id}")
    public SkillDetailResponse update(
            @PathVariable UUID id,
            @RequestBody SkillWriteRequest request,
            Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        SkillMarkdownParser.Parsed parsed = resolve(request, "unchanged-name");
        return detail(skillService.update(id, parsed.description(), parsed.body(), null));
    }

    @DeleteMapping("/api/skills/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        skillService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/api/skills/{name}/enabled")
    public ResponseEntity<Void> setEnabled(
            @PathVariable String name,
            @RequestBody EnabledRequest request,
            Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        skillService.setEnabled(name, request.enabled());
        return ResponseEntity.noContent().build();
    }

    // ---- staged self-improvement review (V243, improve_skill tool) -------

    public record PendingImprovementResponse(
            UUID skillId,
            String name,
            String description,
            String body,
            Map<String, String> files,
            String rationale,
            String proposedBy,
            Instant proposedAt) {
        static PendingImprovementResponse from(SkillService.PendingImprovement p) {
            return new PendingImprovementResponse(
                    p.skillId(),
                    p.name(),
                    p.description(),
                    p.body(),
                    p.files(),
                    p.rationale(),
                    p.proposedBy(),
                    p.proposedAt());
        }
    }

    /**
     * The staged agent-proposed rewrite of one skill, if any. Workspace-admin only for the same
     * reason every write here is - the staged text is agent-authored content an admin must read
     * before it touches a prompt.
     */
    @GetMapping("/api/skills/{id}/improvement")
    public PendingImprovementResponse pendingImprovement(
            @PathVariable UUID id, Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        return skillService
                .pendingImprovement(id)
                .map(PendingImprovementResponse::from)
                .orElseThrow(() -> new SkillNotFoundException(id.toString()));
    }

    /** Applies the staged improvement - the live skill changes only here, under an admin's call. */
    @PostMapping("/api/skills/{id}/improvement/apply")
    public SkillDetailResponse applyImprovement(
            @PathVariable UUID id, Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        return detail(skillService.applyImprovement(id));
    }

    /** Discards the staged improvement - the live skill is untouched either way. */
    @DeleteMapping("/api/skills/{id}/improvement")
    public ResponseEntity<Void> discardImprovement(
            @PathVariable UUID id, Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        skillService.discardImprovement(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/api/skills/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResponse> importArchive(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "replace", defaultValue = "false") boolean replace,
            Authentication authentication)
            throws IOException {
        requireWorkspaceAdmin(authentication);
        if (file == null || file.isEmpty()) {
            throw new SkillValidationException("Choose a skill .zip file or a SKILL.md to upload.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new SkillValidationException(
                    "That file is too large - a skill archive can be at most 5 MB.");
        }
        byte[] upload = file.getBytes();
        // A bare SKILL.md is wrapped into a one-entry archive so it goes through the exact same
        // reader, limits and import path as a zip - there is no second, laxer parser.
        byte[] archive = SkillArchiveReader.looksLikeZip(upload) ? upload : wrapSkillMd(upload);
        SkillService.ImportResult result =
                skillService.importArchive(
                        new java.io.ByteArrayInputStream(archive),
                        replace,
                        subject(authentication));
        lifecycle.recordImport(result.skill().name(), archive);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ImportResponse(detail(result.skill()), result.skippedPaths()));
    }

    private static byte[] wrapSkillMd(byte[] skillMd) throws IOException {
        if (skillMd.length > SkillArchiveReader.MAX_FILE_BYTES) {
            throw new SkillValidationException(
                    "SKILL.md is too large - it can be at most "
                            + (SkillArchiveReader.MAX_FILE_BYTES / 1024)
                            + " KB.");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write(skillMd);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * {@code expectedSha256}: the content hash from the scan preview the admin confirmed (optional;
     * 409 on mismatch).
     */
    public record RegistryInstallRequest(
            String githubUrl, boolean replace, String expectedSha256) {}

    /** Public skills registry search - the skills counterpart of the MCP registry. */
    @GetMapping("/api/skills/registry/search")
    public SkillRegistryClient.SearchResult searchRegistry(
            @RequestParam(value = "q", defaultValue = "") String q,
            @RequestParam(value = "page", defaultValue = "1") int page) {
        return registry.search(q, page);
    }

    /**
     * One-click install: fetched from GitHub, then through the same import path as an uploaded
     * .zip.
     */
    @PostMapping("/api/skills/registry/install")
    public ResponseEntity<ImportResponse> installFromRegistry(
            @RequestBody RegistryInstallRequest request, Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        byte[] archive = registry.fetchAsArchive(request == null ? null : request.githubUrl());
        SkillService.ImportResult result =
                skillService.importArchive(
                        new java.io.ByteArrayInputStream(archive),
                        request.replace(),
                        subject(authentication),
                        request.expectedSha256());
        lifecycle.recordImport(result.skill().name(), archive);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        new ImportResponse(
                                detail(result.skill()), result.skippedPaths()));
    }

    /**
     * The exact folder-in-a-zip layout {@code /import} accepts, so a skill round-trips (and ports
     * to any other Agent Skills host).
     */
    @GetMapping(value = "/api/skills/{name}/export", produces = "application/zip")
    public ResponseEntity<byte[]> export(@PathVariable String name) throws IOException {
        SkillDefinition skill = resolveSkill(name);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(
                "SKILL.md",
                SkillMarkdownParser.render(
                        skill.name(),
                        skill.description(),
                        skill.body(),
                        lifecycle.extrasFor(skill.name())));
        skill.files().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> entries.put(e.getKey(), e.getValue()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(skill.name() + "/" + e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + skill.name() + ".zip\"")
                .body(bytes.toByteArray());
    }

    // ------------------------------------------------------------------ per-agent scope

    @GetMapping("/api/agents/{agentId}/skills")
    public AgentSkillsResponse agentSkills(@PathVariable UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return agentResponse(agentId, skillService.getAgentSettings(agentId));
    }

    @PutMapping("/api/agents/{agentId}/skills")
    public AgentSkillsResponse updateAgentSkills(
            @PathVariable UUID agentId, @RequestBody AgentSkillsRequest request) {
        // Same FREE-tier edit lock every other post-creation agent control enforces - without it
        // this endpoint would be the one way to keep reconfiguring a locked agent.
        agentService.assertEditable(agentService.requireAccess(agentId, PermissionLevel.EDITOR));
        boolean enabled = request.skillsEnabled() == null || request.skillsEnabled();
        return agentResponse(
                agentId,
                skillService.setAgentSettings(agentId, enabled, request.allowedSkillNames()));
    }

    // ---- skill acquisition: distill observed successful run patterns ----

    public record DistillPatternResponse(
            List<String> tools,
            int distinctSessions,
            int runs,
            double meanReward,
            List<String> sampleGoals) {
        static DistillPatternResponse from(SkillDistillationService.ToolPattern p) {
            return new DistillPatternResponse(
                    p.tools(), p.distinctSessions(), p.runs(), p.meanReward(), p.sampleGoals());
        }
    }

    public record DistillResponse(
            List<DistillPatternResponse> patterns,
            List<String> proposedSkillNames,
            Map<String, String> skipped) {}

    /**
     * GET .../skills/distill - the read-only preview: which repeated tool sequences this agent's
     * successful runs actually share (min 3 distinct sessions). Workspace-admin, same as every
     * other skill-catalog write - the preview reveals run goals, which are user data.
     */
    @GetMapping("/api/agents/{agentId}/skills/distill")
    public ResponseEntity<List<DistillPatternResponse>> distillPreview(
            @PathVariable UUID agentId, Authentication authentication, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        requireWorkspaceAdmin(authentication);
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return ListPaging.window(
            distillationService.detectPatterns(agentId).stream()
                .map(DistillPatternResponse::from)
                .toList(),
            limit, offset, 50, 500);
    }

    /**
     * POST .../skills/distill - stages each detected pattern as a DISABLED draft skill through
     * {@code SkillService.propose} (digest-pinned, approval-gated - the same review path as an
     * agent-authored proposal). Nothing this endpoint writes can reach a prompt until an admin
     * enables it.
     */
    @PostMapping("/api/agents/{agentId}/skills/distill")
    public DistillResponse distill(@PathVariable UUID agentId, Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        agentService.assertEditable(agentService.requireAccess(agentId, PermissionLevel.EDITOR));
        SkillDistillationService.DistillResult result = distillationService.distill(agentId);
        return new DistillResponse(
                result.patterns().stream().map(DistillPatternResponse::from).toList(),
                result.proposedSkillNames(),
                result.skipped());
    }

    private AgentSkillsResponse agentResponse(
            UUID agentId, SkillService.AgentSkillSettings settings) {
        Map<UUID, SkillLifecycleService.SkillView> views = lifecycle.views();
        List<SkillSummaryResponse> active =
                skillService.metadataForAgent(agentId).stream()
                        .map(m -> SkillSummaryResponse.from(m, m.id() == null ? null : views.get(m.id())))
                        .toList();
        return new AgentSkillsResponse(
                settings.skillsEnabled(), settings.allowedSkillNames(), active);
    }

    // ------------------------------------------------------------------ helpers

    private SkillDetailResponse detail(SkillDefinition d) {
        if (d.builtIn() || d.id() == null) {
            return SkillDetailResponse.from(d, null, Map.of());
        }
        return SkillDetailResponse.from(
                d, lifecycle.viewOf(d.id()).orElse(null), lifecycle.extrasFor(d.name()));
    }

    /** A skill by name, or - when no skill has that name and the text is a UUID - by id. */
    private SkillDefinition resolveSkill(String ref) {
        Optional<SkillDefinition> byName = skillService.find(ref);
        if (byName.isPresent()) {
            return byName.get();
        }
        try {
            return lifecycle
                    .nameOfId(UUID.fromString(ref))
                    .flatMap(skillService::find)
                    .orElseThrow(() -> new SkillNotFoundException(ref));
        } catch (IllegalArgumentException notAnId) {
            throw new SkillNotFoundException(ref);
        }
    }

    private static SkillStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SkillStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SkillValidationException(
                    "Unknown status \"" + raw + "\". Use PENDING, ACTIVE, REJECTED or ARCHIVED.");
        }
    }

    private static SkillMarkdownParser.Parsed resolve(
            SkillWriteRequest request, String fallbackName) {
        if (request == null) {
            throw new SkillValidationException("Request body is required.");
        }
        if (request.skillMd() != null && !request.skillMd().isBlank()) {
            return SkillMarkdownParser.parse(request.skillMd());
        }
        String name =
                request.name() == null || request.name().isBlank() ? fallbackName : request.name();
        return new SkillMarkdownParser.Parsed(
                SkillMarkdownParser.validateName(name),
                SkillMarkdownParser.validateDescription(request.description()),
                request.body() == null ? "" : request.body());
    }

    private static String subject(Authentication authentication) {
        return authentication == null ? null : authentication.getName();
    }

    static void requireWorkspaceAdmin(Authentication authentication) {
        boolean admin =
                authentication != null
                        && authentication.getAuthorities() != null
                        && authentication.getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority)
                                .anyMatch(
                                        a ->
                                                PERM_MANAGE_AGENTS.equals(a)
                                                        || ROLE_PLATFORM_ADMIN.equals(a));
        if (!admin) {
            throw new SkillWriteForbiddenException();
        }
    }

    static class SkillWriteForbiddenException extends RuntimeException {
        SkillWriteForbiddenException() {
            super(
                    "Only a workspace admin can add or change skills, because a skill applies to every agent in the workspace.");
        }
    }

    @ExceptionHandler(SkillNotFoundException.class)
    ResponseEntity<Map<String, Object>> handleNotFound(SkillNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(SkillWriteForbiddenException.class)
    ResponseEntity<Map<String, Object>> handleForbidden(SkillWriteForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
