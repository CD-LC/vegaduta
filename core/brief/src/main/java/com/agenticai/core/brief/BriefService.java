package com.agenticai.core.brief;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.brief.BriefValidationResult.Issue;
import com.agenticai.core.brief.dto.ApplyDtos.ApplyResult;
import com.agenticai.core.brief.dto.BriefDtos.BindingView;
import com.agenticai.core.brief.dto.BriefDtos.CreateResponse;
import com.agenticai.core.brief.dto.BriefDtos.Detail;
import com.agenticai.core.brief.dto.BriefDtos.ListItem;
import com.agenticai.core.brief.dto.BriefDtos.UpdateResponse;
import com.agenticai.core.brief.dto.BriefDtos.VersionSummary;
import com.agenticai.core.brief.dto.LedgerDtos.LedgerSummary;
import com.agenticai.core.brief.dto.ReadinessRow;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence + lifecycle for briefs: create (version 1), list, read with live readiness, edit as
 * a new version, delete. A brief is only ever SAVED valid ({@link BriefValidator}) and always at
 * the UNDER_WATCH rung - autonomy is earned through the trust engine (a later layer), never
 * chosen on a form, so create/update refuse any other rung. Apply, tune, rehearse, trust, rollback
 * and the ledger build on the tables this service writes and are not here.
 */
@Service
public class BriefService {

    private final BriefRepository briefRepository;
    private final BriefVersionRepository versionRepository;
    private final BriefBindingRepository bindingRepository;
    private final BriefValidator validator;
    private final BriefReadinessService readinessService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final BriefApplyService applyService;
    private final ProofLedgerService proofLedger;

    public BriefService(
        BriefRepository briefRepository,
        BriefVersionRepository versionRepository,
        BriefBindingRepository bindingRepository,
        BriefValidator validator,
        BriefReadinessService readinessService,
        AuditLogService auditLogService,
        ObjectMapper objectMapper,
        BriefApplyService applyService,
        ProofLedgerService proofLedger
    ) {
        this.briefRepository = briefRepository;
        this.versionRepository = versionRepository;
        this.bindingRepository = bindingRepository;
        this.validator = validator;
        this.readinessService = readinessService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.applyService = applyService;
        this.proofLedger = proofLedger;
    }

    @Transactional
    public CreateResponse create(Brief brief, String note) {
        requireSavable(brief, true, "UNDER_WATCH");
        String actor = actor();
        BriefEntity entity = new BriefEntity(UUID.randomUUID(), brief.title(), brief.goal(), brief.locale(),
            "UNDER_WATCH", actor);
        briefRepository.save(entity);
        versionRepository.save(new BriefVersionEntity(entity.getId(), 1, toJson(brief), null, note, actor));
        audit(actor, "BRIEF_CREATED", entity.getId(), "version 1");
        return new CreateResponse(entity.getId(), 1, entity.getStatus());
    }

    @Transactional(readOnly = true)
    public List<ListItem> list() {
        List<ListItem> out = new ArrayList<>();
        for (BriefEntity b : briefRepository.findAllByOrderByUpdatedAtDesc()) {
            out.add(toListItem(b));
        }
        return out;
    }

    /**
     * The briefs that own a real entity (an agent or workflow id) - one {@code brief_binding} lookup so
     * a caller can find "the envoy behind this" without fetching every detail just to read its bindings.
     */
    @Transactional(readOnly = true)
    public List<ListItem> listBoundTo(String entityId) {
        List<UUID> ids = bindingRepository.findByEntityId(entityId).stream()
            .map(BriefBindingEntity::getBriefId).distinct().toList();
        return briefRepository.findAllById(ids).stream()
            .sorted(Comparator.comparing(BriefEntity::getUpdatedAt).reversed())
            .map(this::toListItem)
            .toList();
    }

    private ListItem toListItem(BriefEntity b) {
        // baseline / lastDelta come from the proof ledger (null unless measured); readinessSummary would
        // need a live vault + registry lookup per row and stays null.
        LedgerSummary proof = BriefEntity.APPLIED.equals(b.getStatus()) ? summaryQuietly(b.getId(), false) : null;
        return new ListItem(b.getId(), b.getTitle(), b.getRung(), b.getStatus(), b.getCurrentVersion(),
            proof == null ? null : proof.baseline(), proof == null ? null : proof.lastDelta(), null, b.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public Detail get(UUID id) {
        BriefEntity entity = require(id);
        Brief brief = current(entity);
        List<BindingView> bindings = bindingRepository.findByBriefId(id).stream()
            .map(b -> new BindingView(b.getRef(), b.getKind(), b.getEntityId())).toList();
        Map<String, String> bindingMap = new LinkedHashMap<>();
        bindings.forEach(b -> bindingMap.put(b.ref(), b.entityId()));
        List<ReadinessRow> readiness = readinessService.compute(brief, bindingMap);
        return new Detail(id, entity.getStatus(), brief, readiness, versions(id), entity.getRung(), bindings,
            summaryQuietly(id, true));
    }

    /** A ledger that cannot be read must never make the brief itself unreadable: null means "no summary". */
    private LedgerSummary summaryQuietly(UUID id, boolean full) {
        try {
            return proofLedger.summary(id, full);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<ReadinessRow> readiness(UUID id) {
        BriefEntity entity = require(id);
        Map<String, String> bindingMap = new LinkedHashMap<>();
        bindingRepository.findByBriefId(id).forEach(b -> bindingMap.put(b.getRef(), b.getEntityId()));
        return readinessService.compute(current(entity), bindingMap);
    }

    /** Edit as a new version. Only a brief that has not been applied (DRAFT) or whose apply failed can be edited. */
    @Transactional
    public UpdateResponse update(UUID id, Brief brief, String note) {
        BriefEntity entity = require(id);
        if (!BriefEntity.DRAFT.equals(entity.getStatus()) && !BriefEntity.FAILED.equals(entity.getStatus())) {
            throw new BriefConflictException("This brief is " + entity.getStatus()
                + ". Only a draft can be edited directly; use tune to change an applied brief.");
        }
        requireSavable(brief, true, entity.getRung());
        int parent = entity.getCurrentVersion();
        int next = versionRepository.findByBriefIdOrderByVersionDesc(id).stream()
            .mapToInt(BriefVersionEntity::getVersion).max().orElse(parent) + 1;
        String actor = actor();
        versionRepository.save(new BriefVersionEntity(id, next, toJson(brief), parent, note, actor));
        entity.touch(brief.title(), brief.goal(), brief.locale(), next);
        briefRepository.save(entity);
        audit(actor, "BRIEF_UPDATED", id, "version " + next);
        return new UpdateResponse(id, next, entity.getStatus());
    }

    /** Deliberately NOT one transaction: a purge is a series of calls to the real services, each its own commit. */
    public void delete(UUID id, boolean purgeEntities) {
        BriefEntity entity = require(id);
        if (purgeEntities && !bindingRepository.findByBriefId(id).isEmpty()) {
            ApplyResult purged = applyService.purge(id);
            if (!"SUCCEEDED".equals(purged.status())) {
                throw new BriefConflictException("Some of what this brief created could not be removed (" + purged.error()
                    + "). The brief was kept so you can try again.");
            }
        }
        briefRepository.delete(entity);
        audit(actor(), "BRIEF_DELETED", id, "purgeEntities=" + purgeEntities);
    }

    // ------------------------------------------------------------------ helpers

    private void requireSavable(Brief brief, boolean checkCaps, String requiredRung) {
        if (brief != null && brief.isClarificationOnly()) {
            throw new BriefValidationException("clarifications",
                "This is a set of questions, not a plan - answer them and plan again before saving.");
        }
        BriefValidationResult result = validator.validate(brief, checkCaps);
        List<Issue> errors = new ArrayList<>(result.errors());
        if (brief != null && brief.trust() != null && brief.trust().rung() != null
            && !requiredRung.equals(brief.trust().rung())) {
            errors.add(new Issue("trust.rung", "Autonomy is earned: a brief is saved at " + requiredRung
                + " and only promoted through the trust step."));
        }
        if (!errors.isEmpty()) {
            throw new BriefValidationException(errors);
        }
    }

    private BriefEntity require(UUID id) {
        return briefRepository.findById(id).orElseThrow(() -> new BriefNotFoundException(id));
    }

    private Brief current(BriefEntity entity) {
        BriefVersionEntity version = versionRepository.findByBriefIdAndVersion(entity.getId(), entity.getCurrentVersion())
            .orElseThrow(() -> new BriefNotFoundException(entity.getId()));
        try {
            return objectMapper.readValue(version.getManifestJson(), Brief.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored brief manifest " + entity.getId() + " v" + entity.getCurrentVersion()
                + " could not be read", e);
        }
    }

    private List<VersionSummary> versions(UUID id) {
        return versionRepository.findByBriefIdOrderByVersionDesc(id).stream()
            .map(v -> new VersionSummary(v.getVersion(), v.getParentVersion(), v.getNote(), v.getCreatedBy(), v.getCreatedAt()))
            .toList();
    }

    private String toJson(Brief brief) {
        try {
            return objectMapper.writeValueAsString(brief);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Brief could not be serialised", e);
        }
    }

    private static String actor() {
        return TenantContext.current().map(TenantContext::getSubject).filter(s -> s != null && !s.isBlank()).orElse("unknown");
    }

    private void audit(String actor, String action, UUID id, String detail) {
        auditLogService.record(actor, action, "brief", id.toString(), AuditOutcome.SUCCESS, detail);
    }
}
