package com.agenticai.core.agents.skills;

import java.io.ByteArrayInputStream;
import org.springframework.http.ResponseEntity;
import com.agenticai.core.tenancy.ListPaging;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Skill supply-chain API: scan reports, the "needs review" queue and owner approval.
 *
 * <p>Reads are open to any workspace member (the skills list itself is). Anything that records an
 * approval, or fetches a candidate skill from outside to scan it, is workspace-admin only - the
 * same {@code MANAGE_AGENTS} bar as every other skill write, because an approved skill reaches
 * every agent's prompt. Tenant comes from the request's TenantContext (schema-per-tenant); nothing
 * here accepts a tenant or user id from a body.
 *
 * <p>Errors are {@link ResponseStatusException} / {@link SkillValidationException} so {@code
 * GlobalExceptionHandler} maps them (404, 403, 409, 400) - no new exception type. With {@code
 * app.skills.supply-chain.enabled=false} every endpoint answers 404.
 */
@RestController
@RequestMapping("/api/skill-security")
public class SkillSupplyChainController {

    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    private static final String PERM_MANAGE_AGENTS = "PERM_MANAGE_AGENTS";
    private static final String ROLE_PLATFORM_ADMIN = "ROLE_platform-admin";

    private final SkillSupplyChainService supplyChain;
    private final SkillRegistryClient registry;

    public SkillSupplyChainController(
            SkillSupplyChainService supplyChain, SkillRegistryClient registry) {
        this.supplyChain = supplyChain;
        this.registry = registry;
    }

    public record ApproveRequest(String expectedSha256, boolean acknowledgeHighRisk) {}

    public record DraftPreviewRequest(String skillMd) {}

    public record RegistryPreviewRequest(String githubUrl) {}

    /**
     * Per-skill status + risk for list badges; {@code enabled:false} when scanning is switched off.
     */
    @GetMapping("/summary")
    public SkillSupplyChainService.SecurityOverview summary() {
        return supplyChain.overview();
    }

    /**
     * Custom skills whose content changed since approval, or that scan high and were never
     * approved.
     */
    @GetMapping("/reviews")
    public ResponseEntity<List<SkillSupplyChainService.SkillReviewItem>> reviews(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(supplyChain.reviews(), limit, offset, 50, 200);
    }

    @GetMapping("/skills/{name}/report")
    public SkillSupplyChainService.SkillSecurityReport report(@PathVariable String name) {
        return supplyChain.report(name);
    }

    /**
     * Records who approved which hash, when. 409 if the skill changed after the reviewer loaded it.
     */
    @PostMapping("/skills/{name}/approve")
    public SkillSupplyChainService.SkillSecurityReport approve(
            @PathVariable String name,
            @RequestBody(required = false) ApproveRequest request,
            Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        ApproveRequest body = request == null ? new ApproveRequest(null, false) : request;
        return supplyChain.approve(
                name,
                body.expectedSha256(),
                body.acknowledgeHighRisk(),
                authentication == null ? null : authentication.getName());
    }

    /** Scan SKILL.md text before it is saved. Nothing is stored. */
    @PostMapping("/preview/draft")
    public SkillSupplyChainService.PreviewResult previewDraft(
            @RequestBody DraftPreviewRequest request, Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        if (request == null) {
            throw new SkillValidationException("Request body is required.");
        }
        return supplyChain.previewDraft(request.skillMd());
    }

    /** Scan an uploaded skill .zip before it is imported. Nothing is stored. */
    @PostMapping(value = "/preview/archive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SkillSupplyChainService.PreviewResult previewArchive(
            @RequestParam("file") MultipartFile file, Authentication authentication)
            throws IOException {
        requireWorkspaceAdmin(authentication);
        if (file == null || file.isEmpty()) {
            throw new SkillValidationException("Choose a skill .zip file to scan.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new SkillValidationException(
                    "That file is too large - a skill archive can be at most 5 MB.");
        }
        try (InputStream in = file.getInputStream()) {
            return supplyChain.previewArchive(in);
        }
    }

    /** Fetch a registry skill exactly as the installer would and scan it. Nothing is stored. */
    @PostMapping("/preview/registry")
    public SkillSupplyChainService.PreviewResult previewRegistry(
            @RequestBody RegistryPreviewRequest request, Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        if (request == null || request.githubUrl() == null || request.githubUrl().isBlank()) {
            throw new SkillValidationException("A GitHub folder URL is required.");
        }
        byte[] archive = registry.fetchAsArchive(request.githubUrl());
        return supplyChain.previewArchive(new ByteArrayInputStream(archive));
    }

    private static void requireWorkspaceAdmin(Authentication authentication) {
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
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only a workspace admin can approve skills or scan a skill before installing it.");
        }
    }
}
