package com.agenticai.core.brief;

import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The narrow persistence seam the apply engine, the version service and the trust ladder share:
 * load a manifest, append a new version, move the brief's status / rung. No apply or trust logic
 * lives here, which is what lets those services depend on it without depending on each other.
 * Every method is its own short transaction (the apply engine deliberately holds none across the
 * calls it makes to the real services).
 *
 * <p>The rung on a version's manifest ({@code trust.rung}) is always forced to the rung the brief
 * really is at when the version is written, so a stored manifest can never claim more autonomy than
 * the envoy has earned.
 */
@Service
public class BriefVersionStore {

    private final BriefRepository briefRepository;
    private final BriefVersionRepository versionRepository;
    private final ObjectMapper objectMapper;

    public BriefVersionStore(
        BriefRepository briefRepository, BriefVersionRepository versionRepository, ObjectMapper objectMapper
    ) {
        this.briefRepository = briefRepository;
        this.versionRepository = versionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public BriefEntity require(UUID id) {
        return briefRepository.findById(id).orElseThrow(() -> new BriefNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Brief load(UUID briefId, int version) {
        BriefVersionEntity v = versionRepository.findByBriefIdAndVersion(briefId, version)
            .orElseThrow(() -> new BriefNotFoundException(briefId));
        return read(v);
    }

    @Transactional(readOnly = true)
    public Brief loadCurrent(BriefEntity entity) {
        return load(entity.getId(), entity.getCurrentVersion());
    }

    @Transactional(readOnly = true)
    public List<BriefVersionEntity> versions(UUID briefId) {
        return versionRepository.findByBriefIdOrderByVersionDesc(briefId);
    }

    /** Appends {@code brief} as the next version, makes it current and returns its number. */
    @Transactional
    public int append(UUID briefId, Brief brief, String note, String actor, TrustRung rung) {
        return appendAndMove(briefId, brief, note, actor, rung, null);
    }

    /** As {@link #append}, and in the same transaction sets the brief's rung (one version + one rung change). */
    @Transactional
    public int appendAndMove(UUID briefId, Brief brief, String note, String actor, TrustRung rung, TrustRung newRung) {
        BriefEntity entity = require(briefId);
        Brief stamped = withRung(brief, newRung != null ? newRung : rung);
        int parent = entity.getCurrentVersion();
        int next = versionRepository.findByBriefIdOrderByVersionDesc(briefId).stream()
            .mapToInt(BriefVersionEntity::getVersion).max().orElse(parent) + 1;
        versionRepository.save(new BriefVersionEntity(briefId, next, toJson(stamped), parent, note, actor));
        entity.touch(stamped.title(), stamped.goal(), stamped.locale(), next);
        if (newRung != null) {
            entity.setRung(newRung.name());
        }
        briefRepository.save(entity);
        return next;
    }

    /** Points the brief back at an earlier version (its manifest is what is really applied); the newer rows stay as history. */
    @Transactional
    public void setCurrent(UUID briefId, int version) {
        BriefEntity entity = require(briefId);
        Brief brief = load(briefId, version);
        entity.touch(brief.title(), brief.goal(), brief.locale(), version);
        briefRepository.save(entity);
    }

    @Transactional
    public void setStatus(UUID briefId, String status) {
        BriefEntity entity = require(briefId);
        entity.setStatus(status);
        briefRepository.save(entity);
    }

    // ------------------------------------------------------------------ helpers

    public String toJson(Brief brief) {
        try {
            return objectMapper.writeValueAsString(brief);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Brief could not be serialised", e);
        }
    }

    private Brief read(BriefVersionEntity v) {
        try {
            return objectMapper.readValue(v.getManifestJson(), Brief.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored brief manifest " + v.getBriefId() + " v" + v.getVersion()
                + " could not be read", e);
        }
    }

    /** A copy of {@code brief} whose trust section carries {@code rung} (irreversible / humanMustDecide kept). */
    public static Brief withRung(Brief brief, TrustRung rung) {
        Brief.Trust trust = brief.trust();
        Brief.Trust next = new Brief.Trust(rung.name(),
            trust == null ? List.of() : trust.irreversible(),
            trust == null ? List.of() : trust.humanMustDecide());
        return new Brief(brief.briefVersion(), brief.title(), brief.goal(), brief.locale(), brief.clarifications(),
            next, brief.agents(), brief.procedures(), brief.workflows(), brief.tools(), brief.mcpServers(),
            brief.knowledge(), brief.secrets(), brief.channels(), brief.evalSet(), brief.rehearsal(), brief.narrative());
    }

    public static String actor() {
        return TenantContext.current().map(TenantContext::getSubject).filter(s -> s != null && !s.isBlank()).orElse("unknown");
    }
}
