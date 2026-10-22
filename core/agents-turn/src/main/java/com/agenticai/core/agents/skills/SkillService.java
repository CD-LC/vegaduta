package com.agenticai.core.agents.skills;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agent Skills - the one place that knows a skill can come from two sources (the classpath catalog
 * and this tenant's {@code skill} table) and merges them into one namespace.
 *
 * <p>Progressive disclosure is preserved end to end, not just in the prompt: {@link
 * #metadataForAgent} (Level 1, every chat turn) reads names and descriptions only and never touches
 * a body or a bundled file; {@link #loadForAgent} (Levels 2/3, only when the model triggers a
 * skill) is the first point a body or file is read from the database.
 */
@Service
public class SkillService {

    public static final int MAX_CUSTOM_SKILLS_PER_TENANT = 50;
    public static final int MAX_BODY_CHARS = 100_000;

    private final BuiltInSkillCatalog builtIns;
    private final SkillRepository skills;
    private final SkillBuiltInOverrideRepository overrides;
    private final AgentSkillConfigRepository agentConfigs;

    public SkillService(
            BuiltInSkillCatalog builtIns,
            SkillRepository skills,
            SkillBuiltInOverrideRepository overrides,
            AgentSkillConfigRepository agentConfigs) {
        this.builtIns = builtIns;
        this.skills = skills;
        this.overrides = overrides;
        this.agentConfigs = agentConfigs;
    }

    /**
     * Supply-chain scan/pin/gate (skillsec). Optional and setter-injected so the four-argument
     * constructor stays as it was; absent (or app.skills.supply-chain.enabled=false) means every
     * path below behaves exactly as before this feature existed.
     */
    private SkillSupplyChainService supplyChain;

    @Autowired(required = false)
    public void setSupplyChain(SkillSupplyChainService supplyChain) {
        this.supplyChain = supplyChain;
    }

    /** Lifecycle, versions and usage (V590); absent means the pre-lifecycle behaviour. */
    private SkillLifecycleService lifecycle;

    @Autowired(required = false)
    public void setLifecycle(SkillLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    private SkillDefinition saveVersioned(Skill skill, String note) {
        Skill saved = java.util.Objects.requireNonNullElse(skills.save(skill), skill);
        if (lifecycle != null) {
            lifecycle.afterWrite(saved, note);
        }
        return saved.toDefinition();
    }

    /**
     * Whether {@code load_skill} counts uses. Wired from {@code app.skills.learning.enabled} (false
     * by default): the counter is one extra bulk UPDATE of the same skill row per load, so installs
     * that do not use the learning loop keep the pre-lifecycle hot path. The field defaults to true
     * only for code that builds the service by hand (tests).
     */
    private boolean countUsage = true;

    @org.springframework.beans.factory.annotation.Value("${app.skills.learning.enabled:false}")
    public void setCountUsage(boolean countUsage) {
        this.countUsage = countUsage;
    }

    /**
     * A custom skill was loaded by an agent: count the use (only while {@link #setCountUsage} is
     * on). Best effort, never throws.
     */
    public void recordUse(String name) {
        if (countUsage && lifecycle != null) {
            lifecycle.recordUse(name);
        }
    }

    /**
     * Level 1 view of one skill - everything the model (and the list UI) sees before a trigger.
     * {@code createdBy}/{@code pendingImprovement} are the REVIEW provenance the management list
     * needs ("who wrote this - a human, or an agent whose draft is awaiting approval?") and are
     * populated only by {@link #listAll}; the per-agent prompt path ({@link #metadataForAgent})
     * leaves them unset - what an agent's prompt needs is the content, not the audit trail.
     */
    public record SkillMetadata(
            UUID id,
            String name,
            String description,
            SkillSource source,
            boolean enabled,
            int fileCount,
            String createdBy,
            boolean pendingImprovement) {
        /** Prompt-side construction without review provenance - see the record javadoc. */
        public SkillMetadata(
                UUID id,
                String name,
                String description,
                SkillSource source,
                boolean enabled,
                int fileCount) {
            this(id, name, description, source, enabled, fileCount, null, false);
        }
    }

    public record AgentSkillSettings(boolean skillsEnabled, Set<String> allowedSkillNames) {}

    // ---------------------------------------------------------------- catalog (management UI)

    @Transactional(readOnly = true)
    public List<SkillMetadata> listAll() {
        Map<String, Boolean> overridden =
                overrides.findAll().stream()
                        .collect(
                                Collectors.toMap(
                                        SkillBuiltInOverride::getName,
                                        SkillBuiltInOverride::isEnabled));
        List<SkillMetadata> out = new ArrayList<>();
        for (SkillDefinition b : builtIns.all()) {
            out.add(
                    new SkillMetadata(
                            null,
                            b.name(),
                            b.description(),
                            SkillSource.BUILT_IN,
                            overridden.getOrDefault(b.name(), true),
                            b.files().size()));
        }
        for (Skill s : skills.findAllByOrderByNameAsc()) {
            out.add(
                    new SkillMetadata(
                            s.getId(),
                            s.getName(),
                            s.getDescription(),
                            SkillSource.CUSTOM,
                            s.isEnabled(),
                            s.getFiles().size(),
                            s.getCreatedBy(),
                            s.hasStagedImprovement()));
        }
        return out;
    }

    /** Full content by name, enabled or not - for the management UI's detail/editor view. */
    @Transactional(readOnly = true)
    public Optional<SkillDefinition> find(String name) {
        Optional<SkillDefinition> builtIn = builtIns.find(name);
        if (builtIn.isPresent()) {
            return builtIn;
        }
        return skills.findByNameWithFiles(name).map(Skill::toDefinition);
    }

    @Transactional
    public SkillDefinition create(
            String name,
            String description,
            String body,
            Map<String, String> files,
            String createdBy) {
        String validName = SkillMarkdownParser.validateName(name);
        String validDescription = SkillMarkdownParser.validateDescription(description);
        String validBody = SkillContentRules.validateBody(body);
        if (builtIns.contains(validName)) {
            throw new SkillValidationException(
                    "\"" + validName + "\" is a built-in skill - choose a different name.");
        }
        if (skills.existsByName(validName)) {
            throw new SkillValidationException(
                    "This workspace already has a skill named \"" + validName + "\".");
        }
        if (skills.count() >= MAX_CUSTOM_SKILLS_PER_TENANT) {
            throw new SkillValidationException(
                    "This workspace already has the maximum of "
                            + MAX_CUSTOM_SKILLS_PER_TENANT
                            + " custom skills.");
        }
        Skill skill = new Skill(validName, validDescription, validBody, createdBy);
        skill.replaceFiles(SkillContentRules.validateFiles(files));
        rehash(skill);
        return saveVersioned(skill, "created");
    }

    /**
     * How many agent-written drafts may wait for review at once - a chatty agent must not fill the
     * workspace quota.
     */
    public static final int MAX_PENDING_PROPOSALS = 10;

    /**
     * An agent-written skill. Same validation as {@link #create}, but stored DISABLED: a skill's
     * description reaches every agent's system prompt, so a human has to read it and switch it on.
     */
    @Transactional
    public SkillDefinition propose(
            String name, String description, String body, String proposedBy) {
        if (skills.countByStatus(SkillStatus.PENDING) >= MAX_PENDING_PROPOSALS) {
            throw new SkillValidationException(
                    "There are already "
                            + MAX_PENDING_PROPOSALS
                            + " draft skills waiting for an admin to review.");
        }
        SkillDefinition created = create(name, description, body, Map.of(), proposedBy);
        if (lifecycle != null) {
            lifecycle.markPending(created.name(), null, null);
        } else {
            setEnabled(created.name(), false);
        }
        return created;
    }

    /**
     * Self-improvement loop (V243) - the {@code improve_skill} tool's service half. Stages an
     * agent's proposed rewrite of an EXISTING skill as a pending improvement on the skill row: the
     * live description/body/files every agent's prompt advertises stay exactly as reviewed until
     * {@link #applyImprovement} runs under an admin call. Same validation and size limits as {@link
     * #update}, plus the same pending-proposal bound as {@link #propose} - an agent may overwrite
     * its own staged draft (newest proposal wins) but may not pile up unbounded review debt.
     *
     * <p>Built-in skills are rejected: they ship on the classpath, not this table, so there is no
     * live row to stage against - the tool's error tells the agent to {@code propose_skill} a new
     * variant instead.
     */
    @Transactional
    public SkillDefinition proposeImprovement(
            String name,
            String description,
            String body,
            Map<String, String> files,
            String rationale,
            String proposedBy) {
        String validName = SkillMarkdownParser.validateName(name);
        String validDescription = SkillMarkdownParser.validateDescription(description);
        String validBody = SkillContentRules.validateBody(body);
        Map<String, String> validFiles = SkillContentRules.validateFiles(files);
        String cleanRationale = rationale == null ? null : rationale.strip();
        if (cleanRationale != null && cleanRationale.length() > 4000) {
            cleanRationale = cleanRationale.substring(0, 4000);
        }
        if (builtIns.contains(validName)) {
            throw new SkillValidationException(
                    "\""
                            + validName
                            + "\" is a built-in skill and can't be edited - propose_skill a new variant under a different name instead.");
        }
        Skill skill =
                skills.findByNameWithFiles(validName)
                        .orElseThrow(() -> new SkillNotFoundException(validName));
        long pending = skills.findAll().stream().filter(Skill::hasStagedImprovement).count();
        if (pending >= MAX_PENDING_PROPOSALS && !skill.hasStagedImprovement()) {
            throw new SkillValidationException(
                    "There are already "
                            + MAX_PENDING_PROPOSALS
                            + " staged skill improvements waiting for an admin to review.");
        }
        String filesJson;
        try {
            filesJson =
                    validFiles.isEmpty()
                            ? null
                            : JsonMappers.plain().writeValueAsString(validFiles);
        } catch (JsonProcessingException e) {
            throw new SkillValidationException("The staged files could not be serialized.");
        }
        skill.stageImprovement(validDescription, validBody, filesJson, cleanRationale, proposedBy);
        return skills.save(skill).toDefinition();
    }

    /**
     * The staged improvement waiting on one skill, if any - the review surface for the admin
     * endpoint.
     */
    @Transactional(readOnly = true)
    public Optional<PendingImprovement> pendingImprovement(UUID id) {
        return skills.findByIdWithFiles(id)
                .filter(Skill::hasStagedImprovement)
                .map(SkillContentRules::toPendingImprovement);
    }

    /**
     * Admin review gate, approve half: promotes the staged description/body (and files, when the
     * proposal carried any) into the live columns via the same code path {@link #update} uses, then
     * re-hashes. Returns the updated definition; throws {@link SkillValidationException} when
     * nothing is staged.
     */
    @Transactional
    public SkillDefinition applyImprovement(UUID id) {
        Skill skill =
                skills.findByIdWithFiles(id)
                        .orElseThrow(() -> new SkillNotFoundException(id.toString()));
        if (!skill.hasStagedImprovement()) {
            throw new SkillValidationException(
                    "Skill \"" + skill.getName() + "\" has no staged improvement to apply.");
        }
        Map<String, String> stagedFiles = null;
        if (skill.getPendingFilesJson() != null && !skill.getPendingFilesJson().isBlank()) {
            try {
                stagedFiles =
                        SkillContentRules.validateFiles(
                                JsonMappers.plain()
                                        .readValue(
                                                skill.getPendingFilesJson(),
                                                new TypeReference<Map<String, String>>() {}));
            } catch (JsonProcessingException e) {
                throw new SkillValidationException(
                        "The staged files are unreadable - discard this improvement instead.");
            }
        }
        skill.applyStagedImprovement(stagedFiles);
        rehash(skill);
        if (supplyChain != null) {
            // The admin applying a staged rewrite has just reviewed it (with its scan) on the
            // Skills
            // page; carry an existing approval over to it instead of demanding a second click.
            supplyChain.reapproveAfterAdminEdit(skill, "apply:" + currentSubject());
        }
        return saveVersioned(skill, "improvement applied");
    }

    /**
     * Admin review gate, discard half: drops the staged improvement, leaving the live skill
     * untouched.
     */
    @Transactional
    public void discardImprovement(UUID id) {
        Skill skill =
                skills.findById(id).orElseThrow(() -> new SkillNotFoundException(id.toString()));
        skill.clearStagedImprovement();
        skills.save(skill);
    }

    /**
     * One staged improvement as the review UI/API sees it - the proposed replacement, never the
     * live content.
     */
    public record PendingImprovement(
            UUID skillId,
            String name,
            String description,
            String body,
            Map<String, String> files,
            String rationale,
            String proposedBy,
            Instant proposedAt) {}

    /**
     * {@code files == null} leaves the bundled files untouched (the inline editor only edits
     * SKILL.md).
     */
    @Transactional
    public SkillDefinition update(
            UUID id, String description, String body, Map<String, String> files) {
        Skill skill =
                skills.findByIdWithFiles(id)
                        .orElseThrow(() -> new SkillNotFoundException(id.toString()));
        skill.updateContent(
                SkillMarkdownParser.validateDescription(description),
                SkillContentRules.validateBody(body));
        if (files != null) {
            skill.replaceFiles(SkillContentRules.validateFiles(files));
        }
        rehash(skill);
        return saveVersioned(skill, "edited");
    }

    /**
     * Imports an uploaded skill .zip. With {@code replace}, an existing custom skill of the same
     * name is overwritten in place (same id, so per-agent allow-lists keep pointing at it).
     */
    @Transactional
    public ImportResult importArchive(InputStream zip, boolean replace, String createdBy) {
        return importArchive(zip, replace, createdBy, null);
    }

    /**
     * Same, but when {@code expectedSha256} is given the archive must hash to it ({@link
     * SkillSupplyChainService#archiveHash}) or the import is refused with 409. The registry install
     * passes the hash from the scan the admin confirmed, so a source that serves different bytes
     * the second time cannot get past the scan that was shown.
     */
    @Transactional
    public ImportResult importArchive(
            InputStream zip, boolean replace, String createdBy, String expectedSha256) {
        SkillArchiveReader.Archive archive = SkillArchiveReader.read(zip);
        if (expectedSha256 != null
                && !expectedSha256.isBlank()
                && !expectedSha256
                        .trim()
                        .equalsIgnoreCase(SkillSupplyChainService.archiveHash(archive))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This skill's content changed after it was scanned. Scan it again and review the result before installing.");
        }
        SkillMarkdownParser.Parsed parsed = archive.skill();
        Optional<Skill> existing = skills.findByNameWithFiles(parsed.name());
        SkillDefinition saved;
        if (existing.isPresent() && replace) {
            saved =
                    update(
                            existing.get().getId(),
                            parsed.description(),
                            parsed.body(),
                            archive.files());
        } else {
            saved =
                    create(
                            parsed.name(),
                            parsed.description(),
                            parsed.body(),
                            archive.files(),
                            createdBy);
        }
        return new ImportResult(saved, archive.skippedPaths());
    }

    public record ImportResult(SkillDefinition skill, List<String> skippedPaths) {}

    @Transactional
    public void delete(UUID id) {
        Skill skill =
                skills.findById(id).orElseThrow(() -> new SkillNotFoundException(id.toString()));
        skills.delete(skill);
    }

    /** Works for both kinds: a custom skill flips its own flag, a built-in gets an override row. */
    @Transactional
    public void setEnabled(String name, boolean enabled) {
        if (builtIns.contains(name)) {
            SkillBuiltInOverride override =
                    overrides
                            .findById(name)
                            .orElseGet(() -> new SkillBuiltInOverride(name, enabled));
            override.setEnabled(enabled);
            overrides.save(override);
            return;
        }
        Skill skill =
                skills.findByNameWithFiles(name)
                        .orElseThrow(() -> new SkillNotFoundException(name));
        skill.setEnabled(enabled);
        if (enabled && supplyChain != null) {
            // A human switching a skill on is accepting its current content (trust on first use).
            supplyChain.pinIfUnpinned(skill, "enable:" + currentSubject());
        }
        saveVersioned(skill, "enabled");
    }

    // ---------------------------------------------------------------- per-agent scope

    @Transactional(readOnly = true)
    public AgentSkillSettings getAgentSettings(UUID agentId) {
        return agentConfigs
                .findById(agentId)
                .map(c -> new AgentSkillSettings(c.isSkillsEnabled(), c.getAllowedSkillNames()))
                .orElseGet(() -> new AgentSkillSettings(true, Set.of()));
    }

    @Transactional
    public AgentSkillSettings setAgentSettings(
            UUID agentId, boolean skillsEnabled, Set<String> allowedSkillNames) {
        Set<String> cleaned = new HashSet<>();
        if (allowedSkillNames != null) {
            for (String name : allowedSkillNames) {
                if (name != null && (builtIns.contains(name) || skills.existsByName(name))) {
                    cleaned.add(name);
                }
            }
        }
        AgentSkillConfig config =
                agentConfigs.findById(agentId).orElseGet(() -> new AgentSkillConfig(agentId));
        config.update(skillsEnabled, cleaned);
        agentConfigs.save(config);
        if (supplyChain != null) {
            for (String skillName : cleaned) {
                if (!builtIns.contains(skillName)) {
                    skills.findByNameWithFiles(skillName)
                            .ifPresent(
                                    s ->
                                            supplyChain.pinIfUnpinned(
                                                    s, "attach:" + currentSubject()));
                }
            }
        }
        return new AgentSkillSettings(skillsEnabled, cleaned);
    }

    // ---------------------------------------------------------------- runtime (chat turn)

    /**
     * Level 1: the skills this agent may trigger, as names + descriptions only. Runs on every chat
     * turn, so it is two small indexed reads and zero body/file I/O. Sorted by name so the prompt
     * block is byte-stable turn to turn (it sits in the cacheable prefix of the system prompt).
     */
    @Transactional(readOnly = true)
    public List<SkillMetadata> metadataForAgent(UUID agentId) {
        AgentSkillSettings settings = getAgentSettings(agentId);
        if (!settings.skillsEnabled()) {
            return List.of();
        }
        Set<String> disabledBuiltIns =
                overrides.findAll().stream()
                        .filter(o -> !o.isEnabled())
                        .map(SkillBuiltInOverride::getName)
                        .collect(Collectors.toSet());
        Map<String, SkillMetadata> merged = new TreeMap<>();
        for (SkillDefinition b : builtIns.all()) {
            if (!disabledBuiltIns.contains(b.name())) {
                merged.put(
                        b.name(),
                        new SkillMetadata(
                                null,
                                b.name(),
                                b.description(),
                                SkillSource.BUILT_IN,
                                true,
                                b.files().size()));
            }
        }
        for (Skill s : skills.findAllByEnabledTrueOrderByNameAsc()) {
            if (supplyChain != null && supplyChain.blocks(s)) {
                continue; // paused by the supply-chain gate (app.skills.supply-chain.enforce),
                // stored columns only
            }
            // fileCount deliberately 0 here: counting would initialise the lazy collection.
            merged.putIfAbsent(
                    s.getName(),
                    new SkillMetadata(
                            s.getId(),
                            s.getName(),
                            s.getDescription(),
                            SkillSource.CUSTOM,
                            true,
                            0));
        }
        Set<String> allowed = settings.allowedSkillNames();
        return merged.values().stream()
                .filter(m -> allowed.isEmpty() || allowed.contains(m.name()))
                .toList();
    }

    /**
     * Read-only transaction for the per-turn snapshots below. Setter-injected like {@code
     * supplyChain} so the constructor stays as it was; without it (plain unit tests with mocked
     * repositories) the snapshots read directly.
     */
    private TransactionTemplate readTx;

    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        this.readTx = template;
    }

    private <T> T inReadTx(java.util.function.Supplier<T> read) {
        return readTx == null ? read.get() : readTx.execute(status -> read.get());
    }

    /**
     * {@link #metadataForAgent} for the chat-turn tool and prompt path, as an immutable snapshot
     * read through the tenant lookup cache (the same skills are asked about by the tool gate and
     * the prompt block of one turn, on every turn). A write to a skill, a built-in override or an
     * agent's skill config on this pod evicts it; another pod sees it within {@code
     * app.chat.hotpath.config-lookup-ttl}; a TTL of 0 reads the tables every time. Authorization
     * does not use this: {@link #loadForAgent} re-reads.
     */
    public List<SkillMetadata> metadataForTurn(UUID agentId) {
        return HotpathLookups.tenant(
                "skills.metadata",
                agentId.toString(),
                () -> List.copyOf(inReadTx(() -> metadataForAgent(agentId))));
    }

    /** {@link #getAgentSettings} as a cached snapshot for the chat-turn path; see above. */
    public AgentSkillSettings settingsForTurn(UUID agentId) {
        return HotpathLookups.tenant(
                "skills.settings",
                agentId.toString(),
                () -> {
                    AgentSkillSettings read = inReadTx(() -> getAgentSettings(agentId));
                    return new AgentSkillSettings(
                            read.skillsEnabled(), Set.copyOf(read.allowedSkillNames()));
                });
    }

    /**
     * Levels 2/3: the full skill, but only if this agent is actually allowed to trigger it - the
     * model choosing a name is not authorisation, so the same scope check as Level 1 is re-run here
     * rather than trusting that the name came from the prompt block.
     */
    @Transactional(readOnly = true)
    public Optional<SkillDefinition> loadForAgent(UUID agentId, String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.trim();
        boolean available =
                metadataForAgent(agentId).stream().anyMatch(m -> m.name().equals(wanted));
        return available ? find(wanted) : Optional.empty();
    }

    /**
     * Why a skill the agent asked for is not loadable right now - only when the supply-chain gate
     * is enforcing and paused a custom skill that is otherwise in this agent's scope. Lets
     * load_skill say "paused for review" instead of a misleading "no such skill".
     */
    @Transactional(readOnly = true)
    public Optional<String> blockedReason(UUID agentId, String name) {
        if (supplyChain == null || name == null || !supplyChain.isEnforcing()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        if (builtIns.contains(wanted)) {
            return Optional.empty();
        }
        AgentSkillSettings settings = getAgentSettings(agentId);
        if (!settings.skillsEnabled()) {
            return Optional.empty();
        }
        Set<String> allowed = settings.allowedSkillNames();
        if (!allowed.isEmpty() && !allowed.contains(wanted)) {
            return Optional.empty();
        }
        return skills.findByName(wanted)
                .filter(Skill::isEnabled)
                .flatMap(supplyChain::blockedReason);
    }

    // ---------------------------------------------------------------- helpers

    /** Content changed: refresh the stored hash and, when scanning is on, the cached scan risk. */
    private void rehash(Skill skill) {
        skill.setContentSha256(SkillContentRules.sha256(skill));
        if (supplyChain != null) {
            supplyChain.stampScan(skill);
        }
    }

    private static String currentSubject() {
        return TenantContext.current()
                .map(TenantContext::getSubject)
                .filter(s -> s != null && !s.isBlank())
                .orElse("unknown");
    }
}
