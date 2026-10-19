package com.agenticai.core.agents.memory.importer;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.Capacity;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportFact;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportRequest;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportResponse;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.PreviewFact;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.PreviewRequest;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.PreviewResponse;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.PreviewStats;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.SkippedFact;
import com.agenticai.core.agents.usermemory.UserMemoryController;
import com.agenticai.core.agents.usermemory.UserMemoryFactService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Bring your memory from another assistant" (ChatGPT / Claude / Gemini): the user pastes that assistant's own
 * answer to a "list everything you remember about me" prompt; this turns it into reviewable facts and saves
 * the ones the user keeps. Deterministic - there is no model call anywhere in this path.
 *
 * <p>{@link #preview} has no side effects. {@link #importFacts} saves each selected fact through
 * {@link UserMemoryFactService#addFact} - the very method behind {@code POST /api/memory/facts} - so the
 * per-caller caps ({@code app.memory.self-service.*}), scope rules, agent authorization (an agent the caller
 * may run, else 404) and embedding all behave exactly as for a hand-added fact. Every fact is re-screened
 * and re-deduped here; the client's preview is never trusted.
 *
 * <p>The memory tables carry no provenance column, so "imported from &lt;source&gt;" is recorded as an audit
 * log entry per import (counts + source, never the fact text). Gated by {@code app.memory.import.enabled}
 * (default true) and, like the facts endpoint it reuses, by {@code app.memory.self-service.enabled}:
 * either off = 404.
 */
@Service
public class MemoryImportService {

    private static final Logger log = LoggerFactory.getLogger(MemoryImportService.class);

    static final List<String> SOURCES = List.of("chatgpt", "claude", "gemini", "other");
    static final String SCOPE_SHARED = "shared";
    static final String SCOPE_AGENT = "agent";
    static final String AUDIT_ACTION = "MEMORY_IMPORT";
    /** Rejected lines listed in a preview; the rest are only counted. */
    static final int MAX_LISTED_REJECTED = 50;
    private static final int DUPLICATE_DISPLAY_CHARS = 200;

    /** Skip reason codes of {@code POST /api/memory/import} beyond the parser's own. */
    static final String S_EMPTY = "empty";
    static final String S_INVALID_SCOPE = "invalid_scope";
    static final String S_AGENT_REQUIRED = "agent_required";
    static final String S_DUPLICATE = "duplicate";
    static final String S_LIMIT = "limit_reached";
    static final String S_AGENT_UNAVAILABLE = "agent_unavailable";
    static final String S_UNAVAILABLE = "unavailable";
    static final String S_INVALID = "invalid";
    static final String S_ERROR = "error";

    private final ObjectProvider<AgentMemoryService> agentMemoryService;
    private final ObjectProvider<TenantMemoryService> tenantMemoryService;
    private final UserMemoryFactService factService;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final boolean enabled;
    private final boolean selfServiceEnabled;
    private final int maxFacts;
    private final int maxChars;
    private final int maxFactsPerAgent;
    private final int maxSharedFacts;

    @Autowired
    public MemoryImportService(
        ObjectProvider<AgentMemoryService> agentMemoryService,
        ObjectProvider<TenantMemoryService> tenantMemoryService,
        UserMemoryFactService factService,
        ObjectProvider<AuditLogService> auditLogService,
        @Value("${app.memory.import.enabled:true}") boolean enabled,
        @Value("${app.memory.self-service.enabled:true}") boolean selfServiceEnabled,
        @Value("${app.memory.import.max-facts:200}") int maxFacts,
        @Value("${app.memory.import.max-chars:20000}") int maxChars,
        @Value("${app.memory.self-service.max-facts-per-agent:25}") int maxFactsPerAgent,
        @Value("${app.memory.self-service.max-shared-facts:50}") int maxSharedFacts
    ) {
        this.agentMemoryService = agentMemoryService;
        this.tenantMemoryService = tenantMemoryService;
        this.factService = factService;
        this.auditLogService = auditLogService;
        this.enabled = enabled;
        this.selfServiceEnabled = selfServiceEnabled;
        this.maxFacts = Math.max(1, maxFacts);
        this.maxChars = Math.max(1, maxChars);
        this.maxFactsPerAgent = Math.max(0, maxFactsPerAgent);
        this.maxSharedFacts = Math.max(0, maxSharedFacts);
    }

    /** The configured limits; 404 when the feature is off, so the page can hide the card before the user tries it. */
    public MemoryImportDtos.ImportLimits limits() {
        requireEnabled();
        return new MemoryImportDtos.ImportLimits(maxFacts, maxChars);
    }

    /** Parses and screens the paste and flags repeats of what the caller already has. Writes nothing. */
    public PreviewResponse preview(String subject, PreviewRequest request) {
        requireEnabled();
        String text = request == null ? null : request.text();
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste the other assistant's answer first.");
        }
        if (text.length() > maxChars) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "That paste is too long. Paste at most " + maxChars + " characters.");
        }
        AgentMemoryService agent = agentMemoryService.getIfAvailable();
        TenantMemoryService tenant = tenantMemoryService.getIfAvailable();
        if (agent == null && tenant == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory is not available.");
        }
        List<TenantMemoryEntry> sharedFacts = tenant == null ? List.of() : tenant.listFactsForSubject(subject);
        List<AgentMemoryEntry> agentFacts = agent == null ? List.of() : agent.listAllForSubject(subject);
        Map<String, String> existing = new HashMap<>();
        for (TenantMemoryEntry m : sharedFacts) {
            existing.putIfAbsent(MemoryImportParser.dedupeKey(m.content()), m.content());
        }
        for (AgentMemoryEntry m : agentFacts) {
            existing.putIfAbsent(MemoryImportParser.dedupeKey(m.content()), m.content());
        }

        MemoryImportParser.ParseResult parsed = MemoryImportParser.parse(text, maxFacts, MemoryImportParser.DEFAULT_MAX_FACT_CHARS);
        List<PreviewFact> facts = new ArrayList<>();
        int kept = 0;
        int duplicates = parsed.pasteDuplicates();
        int rejected = 0;
        int rejectedListed = 0;
        for (MemoryImportParser.Candidate c : parsed.candidates()) {
            if (c.rejected()) {
                rejected++;
                if (rejectedListed < MAX_LISTED_REJECTED) {
                    rejectedListed++;
                    facts.add(new PreviewFact(c.index(), c.text(), null, null, c.rejectedReason()));
                }
                continue;
            }
            String dup = existing.get(MemoryImportParser.dedupeKey(c.text()));
            if (dup != null) {
                duplicates++;
                facts.add(new PreviewFact(c.index(), c.text(), SCOPE_SHARED, shorten(dup), null));
            } else {
                kept++;
                facts.add(new PreviewFact(c.index(), c.text(), SCOPE_SHARED, null, null));
            }
        }
        Capacity capacity = new Capacity(tenant == null ? 0 : Math.max(0, maxSharedFacts - sharedFacts.size()), maxFactsPerAgent);
        return new PreviewResponse(facts, new PreviewStats(parsed.lines(), kept, duplicates, rejected), capacity,
            normalizeSource(request.sourceHint()));
    }

    /** Saves the selected facts one by one; a fact that cannot be saved is reported in {@code skipped}, never fatal. */
    public ImportResponse importFacts(String subject, ImportRequest request) {
        requireEnabled();
        List<ImportFact> requested = request == null ? null : request.facts();
        if (requested == null || requested.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one memory to import.");
        }
        if (requested.size() > maxFacts) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Import at most " + maxFacts + " memories at a time.");
        }
        String source = normalizeSource(request.source());
        AgentMemoryService agent = agentMemoryService.getIfAvailable();
        TenantMemoryService tenant = tenantMemoryService.getIfAvailable();
        if (agent == null && tenant == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory is not available.");
        }
        // Dedupe keys per target: "shared" or "agent:<id>". Existing rows are loaded lazily, once.
        Map<String, Set<String>> keysByTarget = new HashMap<>();
        // A target that already refused (cap reached, agent not runnable) refuses the rest without more writes.
        Map<String, String> blockedTargets = new HashMap<>();
        Map<UUID, Set<String>> existingAgentKeys = null;
        Principal caller = () -> subject;
        int saved = 0;
        List<SkippedFact> skipped = new ArrayList<>();
        for (ImportFact f : requested) {
            String scope = f == null || f.scope() == null ? "" : f.scope();
            MemoryImportParser.Screened screened = MemoryImportParser.screen(f == null ? null : f.text(), MemoryImportParser.DEFAULT_MAX_FACT_CHARS);
            if (f == null || f.text() == null || f.text().isBlank()) {
                skipped.add(new SkippedFact("", S_EMPTY));
                continue;
            }
            if (screened.rejectedReason() != null) {
                skipped.add(new SkippedFact(display(screened.text()), screened.rejectedReason()));
                continue;
            }
            String target;
            if (SCOPE_SHARED.equals(scope)) {
                target = SCOPE_SHARED;
            } else if (SCOPE_AGENT.equals(scope)) {
                if (f.agentId() == null) {
                    skipped.add(new SkippedFact(display(screened.text()), S_AGENT_REQUIRED));
                    continue;
                }
                target = SCOPE_AGENT + ":" + f.agentId();
            } else {
                skipped.add(new SkippedFact(display(screened.text()), S_INVALID_SCOPE));
                continue;
            }
            String blocked = blockedTargets.get(target);
            if (blocked != null) {
                skipped.add(new SkippedFact(display(screened.text()), blocked));
                continue;
            }
            Set<String> keys = keysByTarget.get(target);
            if (keys == null) {
                keys = new HashSet<>();
                if (SCOPE_SHARED.equals(scope)) {
                    if (tenant != null) {
                        for (TenantMemoryEntry m : tenant.listFactsForSubject(subject)) {
                            keys.add(MemoryImportParser.dedupeKey(m.content()));
                        }
                    }
                } else {
                    if (existingAgentKeys == null) {
                        existingAgentKeys = new HashMap<>();
                        if (agent != null) {
                            for (AgentMemoryEntry m : agent.listAllForSubject(subject)) {
                                existingAgentKeys.computeIfAbsent(m.agentId(), k -> new HashSet<>())
                                    .add(MemoryImportParser.dedupeKey(m.content()));
                            }
                        }
                    }
                    keys.addAll(existingAgentKeys.getOrDefault(f.agentId(), Set.of()));
                }
                keysByTarget.put(target, keys);
            }
            String key = MemoryImportParser.dedupeKey(screened.text());
            if (keys.contains(key)) {
                skipped.add(new SkippedFact(display(screened.text()), S_DUPLICATE));
                continue;
            }
            try {
                // Same path as POST /api/memory/facts: caps, scope rules, agent authz (404) and dedicated embedding.
                factService.addFact(new UserMemoryController.FactCreateRequest(screened.text(), scope, f.agentId()), caller);
                saved++;
                keys.add(key);
            } catch (ResponseStatusException e) {
                String reason = reasonFor(e.getStatusCode().value(), scope);
                if (S_LIMIT.equals(reason) || S_AGENT_UNAVAILABLE.equals(reason) || S_UNAVAILABLE.equals(reason)) {
                    blockedTargets.put(target, reason);
                }
                skipped.add(new SkippedFact(display(screened.text()), reason));
            } catch (AgentNotFoundException | ResourceAccessDeniedException e) {
                // Same answer for "no such agent" and "not yours" so the import cannot probe for agent ids.
                blockedTargets.put(target, S_AGENT_UNAVAILABLE);
                skipped.add(new SkippedFact(display(screened.text()), S_AGENT_UNAVAILABLE));
            } catch (RuntimeException e) {
                log.warn("Memory import: saving one fact failed - continuing with the rest", e);
                skipped.add(new SkippedFact(display(screened.text()), S_ERROR));
            }
        }
        recordAudit(subject, source, saved, skipped.size());
        return new ImportResponse(saved, skipped, source);
    }

    static String reasonFor(int httpStatus, String scope) {
        return switch (httpStatus) {
            case 409 -> S_LIMIT;
            case 404 -> SCOPE_AGENT.equals(scope) ? S_AGENT_UNAVAILABLE : S_UNAVAILABLE;
            case 400 -> S_INVALID;
            default -> S_ERROR;
        };
    }

    static String normalizeSource(String raw) {
        if (raw == null) {
            return "other";
        }
        String s = raw.strip().toLowerCase(Locale.ROOT);
        return SOURCES.contains(s) ? s : "other";
    }

    private static String display(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > DUPLICATE_DISPLAY_CHARS ? text.substring(0, DUPLICATE_DISPLAY_CHARS) + "…" : text;
    }

    private static String shorten(String text) {
        return display(text);
    }

    private void requireEnabled() {
        if (!enabled || !selfServiceEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory import is disabled.");
        }
    }

    /** The only provenance the memory tables allow: an audit entry per import. Never throws, never carries fact text. */
    private void recordAudit(String subject, String source, int saved, int skippedCount) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse("unknown");
        log.info("Memory import: tenant={} source={} saved={} skipped={}", tenantId, source, saved, skippedCount);
        try {
            AuditLogService audit = auditLogService.getIfAvailable();
            if (audit != null) {
                audit.record(subject, AUDIT_ACTION, "Memory", null, AuditOutcome.SUCCESS,
                    "Imported " + saved + " memories from " + source + " (" + skippedCount + " skipped).");
            }
        } catch (RuntimeException e) {
            log.warn("Memory import: audit entry could not be written", e);
        }
    }
}
