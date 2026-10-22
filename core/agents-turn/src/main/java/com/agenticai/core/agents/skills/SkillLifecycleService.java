package com.agenticai.core.agents.skills;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Review lifecycle, version history and usage statistics of custom skills (migration V590).
 *
 * <p>The runtime is untouched by anything here: a skill reaches a prompt only while {@code
 * skill.enabled} is true, and every transition below keeps {@code enabled == (status == ACTIVE)}
 * through the entity's own methods. {@link SkillService} calls {@link #afterWrite} and {@link
 * #markPending} through an optional setter, so a context without this bean behaves exactly as
 * before the lifecycle existed.
 *
 * <p>Built-in skills have no row and therefore no lifecycle: they are reported ACTIVE or ARCHIVED
 * from their override flag by the controller and cannot be approved, rejected or versioned.
 */
@Service
public class SkillLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SkillLifecycleService.class);

    /** Versions kept per skill; older snapshots are pruned when a new one is written. */
    public static final int MAX_VERSIONS_KEPT = 20;

    /** Outcomes needed before the success rate is allowed to call a skill ineffective. */
    static final long MIN_RATED_FOR_VERDICT = 5;

    static final double INEFFECTIVE_BELOW = 0.4;

    private final SkillRepository skills;
    private final SkillVersionRepository versions;
    private final AgentSkillConfigRepository agentConfigs;
    private final ObjectProvider<SkillSupplyChainService> supplyChain;
    private final int staleDays;

    public SkillLifecycleService(
            SkillRepository skills,
            SkillVersionRepository versions,
            AgentSkillConfigRepository agentConfigs,
            ObjectProvider<SkillSupplyChainService> supplyChain,
            @Value("${app.skills.learning.stale-days:90}") int staleDays) {
        this.skills = skills;
        this.versions = versions;
        this.agentConfigs = agentConfigs;
        this.supplyChain = supplyChain;
        this.staleDays = Math.max(1, staleDays);
    }

    /**
     * What the console shows next to a custom skill. {@code effectiveness} is null until an outcome
     * was observed; {@code stale} only ever applies to an ACTIVE skill.
     */
    public record SkillView(
            UUID id,
            SkillStatus status,
            SkillOrigin origin,
            int version,
            long uses,
            Double effectiveness,
            boolean stale,
            String staleReason,
            List<UUID> agentIds,
            Instant updatedAt,
            Instant lastUsedAt,
            String reviewNote,
            UUID learnedForAgentId,
            SelfCheck selfCheck) {}

    /**
     * The learning loop's check of a drafted skill (V653); null in a view when it was not checked.
     */
    public record SelfCheck(String verdict, String reason, Instant checkedAt) {}

    /**
     * The outcome of a review action as the controller needs it: the list-row fields and the
     * lifecycle view. Controllers get this, never the entity (audit F5).
     */
    public record Reviewed(SkillService.SkillMetadata metadata, SkillView view) {}

    // ---------------------------------------------------------------- read side

    /** Every custom skill's lifecycle view by id, in two queries (skills, agent scopes). */
    @Transactional(readOnly = true)
    public Map<UUID, SkillView> views() {
        Map<String, List<UUID>> agentsBySkill = agentsBySkillName();
        Instant now = Instant.now();
        Map<UUID, SkillView> out = new LinkedHashMap<>();
        for (Skill s : skills.findAllByOrderByNameAsc()) {
            out.put(s.getId(), view(s, agentsBySkill.getOrDefault(s.getName(), List.of()), now));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<SkillView> viewOf(UUID id) {
        return skills.findById(id)
                .map(
                        s ->
                                view(
                                        s,
                                        agentsBySkillName().getOrDefault(s.getName(), List.of()),
                                        Instant.now()));
    }

    SkillView view(Skill s, List<UUID> agentIds, Instant now) {
        long rated = s.getSuccesses() + s.getFailures();
        Double effectiveness = effectiveness(s.getSuccesses(), s.getFailures());
        String reason = staleReason(s, rated, effectiveness, now);
        return new SkillView(
                s.getId(),
                s.getStatus(),
                s.getOrigin(),
                s.getContentVersion(),
                s.getUses(),
                effectiveness,
                reason != null,
                reason,
                agentIds,
                s.getUpdatedAt(),
                s.getLastUsedAt(),
                s.getReviewNote(),
                s.getLearnedForAgentId(),
                s.getSelfCheckVerdict() == null
                        ? null
                        : new SelfCheck(
                                s.getSelfCheckVerdict(),
                                s.getSelfCheckReason(),
                                s.getSelfCheckedAt()));
    }

    /**
     * Laplace-smoothed success rate, so one lucky turn does not read as 100%: (s + 1) / (s + f +
     * 2). Null when no outcome was observed yet - "unknown" is not "bad".
     */
    public static Double effectiveness(long successes, long failures) {
        long rated = successes + failures;
        return rated == 0 ? null : (successes + 1.0) / (rated + 2.0);
    }

    private String staleReason(Skill s, long rated, Double effectiveness, Instant now) {
        if (s.getStatus() != SkillStatus.ACTIVE) {
            return null;
        }
        if (rated >= MIN_RATED_FOR_VERDICT
                && effectiveness != null
                && effectiveness < INEFFECTIVE_BELOW) {
            return "Low success rate: "
                    + Math.round(effectiveness * 100)
                    + "% over "
                    + rated
                    + " observed turns";
        }
        Instant since = s.getLastUsedAt() != null ? s.getLastUsedAt() : s.getUpdatedAt();
        if (since != null && Duration.between(since, now).toDays() >= staleDays) {
            return s.getLastUsedAt() != null
                    ? "Not used for " + staleDays + " days or more"
                    : "Never used in " + staleDays + " days or more";
        }
        return null;
    }

    @Transactional(readOnly = true)
    public List<SkillVersion> versions(UUID skillId) {
        if (!skills.existsById(skillId)) {
            throw new SkillNotFoundException(skillId.toString());
        }
        return versions.findBySkillIdOrderByVersionDesc(skillId);
    }

    /** Optional frontmatter keys kept at import; empty for built-ins and unknown skills. */
    @Transactional(readOnly = true)
    public Map<String, Object> extrasFor(String name) {
        return skills.findByName(name)
                .map(s -> SkillMarkdownParser.extrasFromJson(s.getFrontmatterExtra()))
                .orElse(Map.of());
    }

    /** The id of a custom skill by name, for resolving a name or id path segment. */
    @Transactional(readOnly = true)
    public Optional<UUID> idOfName(String name) {
        return skills.findByName(name).map(Skill::getId);
    }

    @Transactional(readOnly = true)
    public Optional<String> nameOfId(UUID id) {
        return skills.findById(id).map(Skill::getName);
    }

    // ---------------------------------------------------------------- transitions

    /**
     * Reviewer approves a draft (or restores a rejected/archived skill): it becomes ACTIVE and is
     * advertised from the next turn. The first approval pins the content hash for the supply-chain
     * gate, like any human switching a skill on.
     */
    @Transactional
    public Reviewed approve(UUID id) {
        Skill skill = require(id);
        if (skill.getStatus() == SkillStatus.ACTIVE) {
            return reviewed(skill);
        }
        skill.setEnabled(true);
        SkillSupplyChainService chain = supplyChain.getIfAvailable();
        if (chain != null) {
            chain.pinIfUnpinned(skill, "approve:" + currentSubject());
        }
        Skill saved = skills.save(skill);
        afterWrite(saved, "approved by " + currentSubject());
        return reviewed(saved);
    }

    /** Declines a draft. Only a PENDING draft can be rejected; archive a live skill instead. */
    @Transactional
    public Reviewed reject(UUID id, String note) {
        Skill skill = require(id);
        if (skill.getStatus() == SkillStatus.REJECTED) {
            return reviewed(skill);
        }
        if (skill.getStatus() != SkillStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only a draft waiting for review can be rejected. Archive an active skill instead.");
        }
        skill.reject(truncate(note, 1000));
        return reviewed(skills.save(skill));
    }

    /** Switches a skill off but keeps it, its history and its agent bindings. */
    @Transactional
    public Reviewed archive(UUID id) {
        Skill skill = require(id);
        if (skill.getStatus() != SkillStatus.ARCHIVED) {
            skill.archive();
            skills.save(skill);
        }
        return reviewed(skill);
    }

    /**
     * Makes {@code agentIds} exactly the agents whose allow-list names this skill. An agent with an
     * empty allow-list already sees every enabled skill, so adding to it would silently
     * <em>narrow</em> it to this one skill: those agents are left alone and reported in {@link
     * AgentBinding#unrestricted}. Removing the last name from an allow-list would do the opposite
     * (widen the agent to every skill), so that case is refused.
     */
    @Transactional
    public AgentBinding setAgents(UUID skillId, Collection<UUID> agentIds) {
        Skill skill = require(skillId);
        String name = skill.getName();
        Set<UUID> desired = new HashSet<>(agentIds == null ? List.of() : agentIds);
        List<UUID> unrestricted = new ArrayList<>();
        Map<UUID, AgentSkillConfig> configs = new HashMap<>();
        // Only the rows this change can touch: the requested agents and the current holders.
        Set<UUID> touched = new HashSet<>(desired);
        touched.addAll(agentsBySkillName().getOrDefault(name, List.of()));
        agentConfigs.findAllById(touched).forEach(c -> configs.put(c.getAgentId(), c));
        for (UUID agentId : desired) {
            AgentSkillConfig config = configs.get(agentId);
            Set<String> allowed = config == null ? Set.of() : config.getAllowedSkillNames();
            if (allowed.isEmpty()) {
                unrestricted.add(agentId);
                continue;
            }
            if (!allowed.contains(name)) {
                Set<String> next = new HashSet<>(allowed);
                next.add(name);
                config.update(config.isSkillsEnabled(), next);
                agentConfigs.save(config);
            }
        }
        for (AgentSkillConfig config : configs.values()) {
            if (desired.contains(config.getAgentId())
                    || !config.getAllowedSkillNames().contains(name)) {
                continue;
            }
            Set<String> next = new HashSet<>(config.getAllowedSkillNames());
            next.remove(name);
            if (next.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Agent "
                                + config.getAgentId()
                                + " allows only this skill; removing it would give that agent every"
                                + " skill. Change its skill scope on the agent's Skills page.");
            }
            config.update(config.isSkillsEnabled(), next);
            agentConfigs.save(config);
        }
        return new AgentBinding(agentsBySkillName().getOrDefault(name, List.of()), unrestricted);
    }

    public record AgentBinding(List<UUID> agentIds, List<UUID> unrestricted) {}

    // ---------------------------------------------------------------- write-side hooks
    // (SkillService)

    /**
     * After a skill row was written: snapshot the live content. A draft is not versioned until it
     * goes live; an ACTIVE skill whose content hash differs from its newest snapshot is a new
     * version. Runs inside the caller's transaction and never throws into it.
     */
    @Transactional
    public void afterWrite(Skill saved, String note) {
        try {
            if (saved == null || saved.getStatus() != SkillStatus.ACTIVE) {
                return;
            }
            Optional<SkillVersion> latest =
                    versions.findFirstBySkillIdOrderByVersionDesc(saved.getId());
            if (latest.isPresent()
                    && Objects.equals(latest.get().getContentSha256(), saved.getContentSha256())) {
                return;
            }
            int version = latest.isPresent() ? saved.bumpVersion() : saved.getContentVersion();
            versions.save(
                    new SkillVersion(
                            saved.getId(),
                            version,
                            saved.getDescription(),
                            saved.getBody(),
                            filesJson(saved),
                            saved.getContentSha256(),
                            currentSubject(),
                            note));
            prune(saved.getId());
        } catch (RuntimeException e) {
            log.warn(
                    "Could not snapshot skill {}: {}",
                    saved == null ? null : saved.getName(),
                    e.getMessage());
        }
    }

    /**
     * Parks a freshly proposed skill as a PENDING LEARNED draft (SkillService#propose). {@code
     * note} is why it was drafted; a reviewer's rejection note replaces it.
     */
    @Transactional
    public void markPending(String name, UUID learnedForAgentId, String note) {
        Skill skill = skills.findByName(name).orElseThrow(() -> new SkillNotFoundException(name));
        skill.markPending(SkillOrigin.LEARNED, learnedForAgentId, truncate(note, 1000));
        skills.save(skill);
        // SkillService#create snapshots every new row while it is still ACTIVE for a moment. A
        // draft is not a version until a reviewer approves it, so drop that snapshot; approving
        // writes version 1 from the content as approved (including any edit made while pending).
        versions.deleteBySkillId(skill.getId());
    }

    /**
     * Stores the learning loop's self-check next to a PENDING draft. Review information only; never
     * throws.
     */
    @Transactional
    public void recordSelfCheck(String name, String verdict, String reason) {
        try {
            skills.recordSelfCheck(
                    name, verdict, truncate(reason, 300), Instant.now(), SkillStatus.PENDING);
        } catch (RuntimeException e) {
            log.debug("Could not record the self-check of skill {}: {}", name, e.getMessage());
        }
    }

    /** Records how a skill arrived: the archive's optional frontmatter keys and IMPORTED origin. */
    @Transactional
    public void recordImport(String name, byte[] zipBytes) {
        try {
            Skill skill = skills.findByName(name).orElse(null);
            if (skill == null) {
                return;
            }
            skill.setOrigin(SkillOrigin.IMPORTED);
            if (zipBytes != null) {
                Map<String, Object> extras =
                        SkillArchiveReader.read(new ByteArrayInputStream(zipBytes)).extras();
                skill.setFrontmatterExtra(SkillMarkdownParser.extrasToJson(extras));
            }
            skills.save(skill);
        } catch (RuntimeException e) {
            log.warn("Could not record the import of skill {}: {}", name, e.getMessage());
        }
    }

    /** load_skill returned this skill: count the use. Bulk update; never throws. */
    @Transactional
    public void recordUse(String name) {
        try {
            skills.recordUse(name, Instant.now());
        } catch (RuntimeException e) {
            log.debug("Could not count a use of skill {}: {}", name, e.getMessage());
        }
    }

    /** A turn that loaded these skills finished well (or not). Used by the learning loop. */
    @Transactional
    public void recordOutcome(Collection<String> names, boolean success) {
        if (names == null || names.isEmpty()) {
            return;
        }
        try {
            if (success) {
                skills.recordSuccess(names);
            } else {
                skills.recordFailure(names);
            }
        } catch (RuntimeException e) {
            log.debug("Could not record a skill outcome: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private Reviewed reviewed(Skill skill) {
        SkillService.SkillMetadata metadata =
                new SkillService.SkillMetadata(
                        skill.getId(),
                        skill.getName(),
                        skill.getDescription(),
                        SkillSource.CUSTOM,
                        skill.isEnabled(),
                        skill.getFiles().size(),
                        skill.getCreatedBy(),
                        skill.hasStagedImprovement());
        List<UUID> agentIds = agentsBySkillName().getOrDefault(skill.getName(), List.of());
        return new Reviewed(metadata, view(skill, agentIds, Instant.now()));
    }

    private Skill require(UUID id) {
        return skills.findByIdWithFiles(id)
                .orElseThrow(() -> new SkillNotFoundException(id.toString()));
    }

    private Map<String, List<UUID>> agentsBySkillName() {
        Map<String, List<UUID>> out = new HashMap<>();
        for (Object[] pair : agentConfigs.findAllowedPairs()) {
            out.computeIfAbsent((String) pair[1], k -> new ArrayList<>()).add((UUID) pair[0]);
        }
        out.values().forEach(l -> l.sort(null));
        return out;
    }

    private void prune(UUID skillId) {
        List<SkillVersion> all = versions.findBySkillIdOrderByVersionDesc(skillId);
        if (all.size() > MAX_VERSIONS_KEPT) {
            versions.deleteAll(all.subList(MAX_VERSIONS_KEPT, all.size()));
        }
    }

    private static String filesJson(Skill skill) {
        Map<String, String> files = skill.filesAsMap();
        if (files.isEmpty()) {
            return null;
        }
        try {
            return JsonMappers.plain().writeValueAsString(files);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String currentSubject() {
        return TenantContext.current()
                .map(TenantContext::getSubject)
                .filter(s -> s != null && !s.isBlank())
                .orElse("unknown");
    }
}
