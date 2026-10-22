package com.agenticai.core.brief;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.brief.dto.ApplyDtos.ApplyResult;
import com.agenticai.core.brief.dto.ApplyDtos.VersionDiff;
import com.agenticai.core.brief.dto.BriefDtos.VersionSummary;
import com.agenticai.core.brief.model.Brief;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Version history of a brief: list, JSON-patch diff between two versions, and rollback.
 *
 * <p>Rollback is not a special path: it appends the older manifest as a NEW version (history is
 * never rewritten) and re-applies it through {@link BriefApplyService}, so it gets the same
 * diff-against-bindings update, compensation and job log as any apply. It can never raise autonomy:
 * the new version is written at the rung the brief is at now, whatever rung the old manifest
 * carried - a rung is only ever earned through {@link TrustLadderService}.
 */
@Service
public class BriefVersionService {

    private static final Logger log = LoggerFactory.getLogger(BriefVersionService.class);

    private final BriefVersionStore store;
    private final BriefApplyService applyService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public BriefVersionService(
            BriefVersionStore store,
            BriefApplyService applyService,
            AuditLogService auditLogService,
            ObjectMapper objectMapper) {
        this.store = store;
        this.applyService = applyService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    public List<VersionSummary> list(UUID briefId) {
        store.require(briefId);
        return store.versions(briefId).stream()
                .map(
                        v ->
                                new VersionSummary(
                                        v.getVersion(),
                                        v.getParentVersion(),
                                        v.getNote(),
                                        v.getCreatedBy(),
                                        v.getCreatedAt()))
                .toList();
    }

    /** RFC 6902 patch that turns version {@code from} into version {@code to}. */
    public VersionDiff diff(UUID briefId, int from, int to) {
        store.require(briefId);
        Brief a = store.load(briefId, from);
        Brief b = store.load(briefId, to);
        return new VersionDiff(
                from,
                to,
                BriefJsonPatch.diffAsArray(
                        objectMapper.valueToTree(a), objectMapper.valueToTree(b)));
    }

    /**
     * Re-applies version {@code version}'s manifest as a new version. Only an applied solution can
     * be rolled back; a draft is simply edited.
     */
    public ApplyResult rollback(UUID briefId, int version, boolean background) {
        BriefEntity entity = store.require(briefId);
        if (!BriefEntity.APPLIED.equals(entity.getStatus())) {
            throw new BriefConflictException(
                    "Only an applied solution can be rolled back. A draft can be edited directly.");
        }
        if (version == entity.getCurrentVersion()) {
            throw new BriefConflictException(
                    "Version " + version + " is already the current version.");
        }
        int versionBefore = entity.getCurrentVersion();
        Brief old = store.load(briefId, version);
        TrustRung rung = TrustRung.parseOrLowest(entity.getRung());
        int created =
                store.append(
                        briefId,
                        old,
                        "Rollback to version " + version,
                        BriefVersionStore.actor(),
                        rung);
        audit(briefId, "version " + version + " re-applied as version " + created);
        try {
            return applyService.applyCurrent(briefId, background);
        } catch (RuntimeException e) {
            // Refused before a job started (invalid today, another apply running): nothing changed,
            // so the brief
            // must keep pointing at what is really applied. The appended version stays in the
            // history.
            store.setCurrent(briefId, versionBefore);
            throw e;
        }
    }

    private void audit(UUID briefId, String detail) {
        try {
            auditLogService.record(
                    BriefVersionStore.actor(),
                    "BRIEF_ROLLBACK",
                    "brief",
                    briefId.toString(),
                    AuditOutcome.SUCCESS,
                    detail);
        } catch (RuntimeException e) {
            log.warn("Could not audit the rollback of brief {}", briefId, e);
        }
    }
}
