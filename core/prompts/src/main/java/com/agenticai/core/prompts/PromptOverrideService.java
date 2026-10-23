package com.agenticai.core.prompts;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every change to a stored prompt goes through here: validation (placeholders, length, risk
 * acknowledgement, scope and tenant rules), the new version, the immutable history row, the audit
 * entry with the old-to-new diff, and the cache eviction.
 *
 * <p>Who may write what: PLATFORM needs {@code ROLE_platform-admin}; TENANT and AGENT need a
 * tenant admin of the calling thread's tenant (the controller enforces the role, the stored rows are
 * in that tenant's schema, and an agent id must belong to that tenant).
 */
@Service
public class PromptOverrideService {

    private static final Logger log = LoggerFactory.getLogger(PromptOverrideService.class);

    public static final String ACTION_UPDATED = "PROMPT_OVERRIDE_UPDATED";
    public static final String ACTION_REVERTED = "PROMPT_OVERRIDE_REVERTED";
    public static final String AUDIT_TARGET = "prompt_override";

    static final String ROLE_PLATFORM_ADMIN = "ROLE_platform-admin";
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*");

    /** A requested change. {@code scopeId} is the agent id for AGENT scope, else ignored. */
    public record Write(
            PromptScope scope,
            UUID agentId,
            String key,
            String locale,
            String text,
            boolean enabled,
            boolean acknowledgeRisk,
            String reason,
            Integer expectedVersion) {}

    /** What a change did. {@code override} is null after a revert or a no-op. */
    public record Applied(PromptOverride override, PromptHistoryEntry history, boolean unchanged) {}

    public record Validation(
            boolean valid, List<String> problems, boolean securityCritical, boolean requiresAcknowledgement, int length) {}

    /** One stored override as the console shows it. */
    public record OverrideView(
            PromptScope scope,
            String scopeId,
            String locale,
            String text,
            boolean enabled,
            int version,
            String createdBy,
            Instant createdAt) {}

    /** One key: its definition, its default, what is effective for the asked context, what is stored. */
    public record PromptView(
            PromptDefinition definition,
            String defaultText,
            ResolvedPrompt effective,
            List<OverrideView> overrides) {}

    private final PromptOverrideStore store;
    private final PromptResolver resolver;
    private final PromptCacheControl cache;
    private final AuditLogService audit;
    private final ObjectProvider<PromptAgentDirectory> agents;
    private final PromptProperties properties;

    public PromptOverrideService(
            PromptOverrideStore store,
            PromptResolver resolver,
            PromptCacheControl cache,
            AuditLogService audit,
            ObjectProvider<PromptAgentDirectory> agents,
            PromptProperties properties) {
        this.store = store;
        this.resolver = resolver;
        this.cache = cache;
        this.audit = audit;
        this.agents = agents;
        this.properties = properties;
    }

    /** The locale of the shipped texts and of an override that names none. */
    public String defaultLocale() {
        return resolver.catalog().defaultLocale();
    }

    // ------------------------------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<PromptView> list(UUID agentId, String locale, String family, String query) {
        PromptContext ctx = contextFor(agentId, locale);
        Map<String, List<OverrideView>> overrides = overridesByKey(agentId);
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<PromptView> out = new ArrayList<>();
        for (PromptDefinition def : resolver.catalog().all()) {
            if (family != null && !family.isBlank() && !def.key().startsWith(family.strip() + ".")) {
                continue;
            }
            if (!q.isEmpty() && !(def.key().contains(q) || def.title().toLowerCase(Locale.ROOT).contains(q))) {
                continue;
            }
            out.add(view(def, ctx, overrides));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public PromptView get(String key, UUID agentId, String locale) {
        PromptDefinition def = definition(key);
        return view(def, contextFor(agentId, locale), overridesByKey(agentId));
    }

    @Transactional(readOnly = true)
    public List<PromptHistoryEntry> history(PromptScope scope, UUID agentId, String key, String locale, Integer limit) {
        String scopeId = scopeId(scope, agentId);
        requireReadable(scope);
        int cap = properties.getHistoryMaxLimit();
        int n = limit == null ? properties.getHistoryDefaultLimit() : Math.min(Math.max(1, limit), cap);
        return store.history(scope, scopeId, key, locale == null || locale.isBlank() ? null : normalizeLocale(locale), n);
    }

    public Validation validate(String key, String text, boolean enabled) {
        PromptDefinition def = definition(key);
        String clean = text == null || text.isBlank() ? null : text.strip();
        List<String> problems = new ArrayList<>();
        if (def.retired()) {
            problems.add(def.title() + " sends no text today, so there is nothing to reword.");
        }
        if (!enabled && !def.disableable()) {
            problems.add(def.title() + " cannot be switched off; use revert to remove an override.");
        }
        if (enabled && clean == null) {
            problems.add("Text is required (use revert to go back to the lower scope or the default).");
        }
        problems.addAll(PromptTemplates.validate(def, clean));
        boolean ack = def.securityCritical() && (!enabled || clean != null);
        return new Validation(problems.isEmpty(), problems, def.securityCritical(), ack, clean == null ? 0 : clean.length());
    }

    // ------------------------------------------------------------------------------------------ writes

    /**
     * Creates or replaces the override at one scope. Text equal to the shipped default (when
     * enabled) removes the override instead, so a stale copy never pins old wording, unless a
     * lower scope overrides the key: then the default is stored to pin it at this scope.
     */
    @Transactional
    public Applied put(Write w) {
        PromptDefinition def = definition(w.key());
        requireWritable(w.scope());
        String locale = normalizeLocale(w.locale());
        String scopeId = scopeId(w.scope(), w.agentId());
        requireAgentInTenant(w.scope(), w.agentId());
        String clean = w.text() == null || w.text().isBlank() ? null : w.text().strip();
        if (def.retired()) {
            throw bad(def.title() + " sends no text today, so there is nothing to reword.");
        }
        if (!w.enabled() && !def.disableable()) {
            throw bad(def.title() + " cannot be switched off; revert the override instead.");
        }
        if (w.enabled() && clean == null) {
            throw bad("Text is required; use revert to remove an override.");
        }
        List<String> problems = PromptTemplates.validate(def, clean);
        if (!problems.isEmpty()) {
            throw bad(String.join(" ", problems));
        }
        if (def.securityCritical() && (!w.enabled() || clean != null) && !w.acknowledgeRisk()) {
            throw bad(
                    def.title()
                            + " is a security directive. Switching it off or rewording it can make the agent obey"
                            + " instructions hidden in tool output. Send acknowledgeRisk=true to confirm.");
        }
        Optional<PromptOverride> existing = store.find(w.scope(), scopeId, def.key(), locale);
        checkExpectedVersion(w.expectedVersion(), existing.map(PromptOverride::version).orElse(0));
        if (w.enabled()
                && clean != null
                && clean.equals(resolver.catalog().defaultText(def.key(), locale).orElse(null))
                && !lowerScopeOverrides(w.scope(), def.key(), locale)) {
            if (existing.isPresent()) {
                return new Applied(null, revertRow(existing.get(), def, w.reason()), false);
            }
            return new Applied(null, null, true);
        }
        int version = store.latestVersion(w.scope(), scopeId, def.key(), locale) + 1;
        Instant now = Instant.now();
        String actor = actor();
        PromptOverride row = new PromptOverride(w.scope(), scopeId, def.key(), locale, clean, w.enabled(), version, actor, now);
        store.saveCurrent(row);
        PromptHistoryEntry entry =
                new PromptHistoryEntry(
                        0,
                        w.scope(),
                        scopeId,
                        def.key(),
                        locale,
                        version,
                        existing.isPresent() ? PromptHistoryEntry.ACTION_UPDATE : PromptHistoryEntry.ACTION_CREATE,
                        clean,
                        w.enabled(),
                        existing.map(PromptOverride::text).orElse(null),
                        existing.map(PromptOverride::enabled).orElse(true),
                        trimReason(w.reason()),
                        actor,
                        now);
        store.appendHistory(entry);
        afterWrite(ACTION_UPDATED, def, entry, existing.orElse(null));
        return new Applied(row, entry, false);
    }

    /**
     * True when a lower scope (tenant below an agent, platform below a tenant or an agent) holds
     * a row for the key in this locale or the default locale. Then the shipped default wording
     * must be stored at the higher scope as a real row: dropping it would let the lower
     * override keep applying although the admin asked for the default.
     */
    private boolean lowerScopeOverrides(PromptScope scope, String key, String locale) {
        List<PromptScope> lower =
                switch (scope) {
                    case AGENT -> List.of(PromptScope.TENANT, PromptScope.PLATFORM);
                    case TENANT -> List.of(PromptScope.PLATFORM);
                    default -> List.of();
                };
        String defaultLocale = resolver.catalog().defaultLocale();
        for (PromptScope s : lower) {
            if (store.find(s, "", key, locale).isPresent()
                    || (!defaultLocale.equals(locale) && store.find(s, "", key, defaultLocale).isPresent())) {
                return true;
            }
        }
        return false;
    }

    /** Removes the override at the scope (the next lower scope or the default applies again). */
    @Transactional
    public Applied revert(PromptScope scope, UUID agentId, String key, String locale, String reason, Integer expectedVersion) {
        PromptDefinition def = definition(key);
        requireWritable(scope);
        String loc = normalizeLocale(locale);
        String scopeId = scopeId(scope, agentId);
        requireAgentInTenant(scope, agentId);
        PromptOverride existing =
                store.find(scope, scopeId, def.key(), loc)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No override of " + key + " at " + scope.dbValue() + " scope."));
        checkExpectedVersion(expectedVersion, existing.version());
        return new Applied(null, revertRow(existing, def, reason), false);
    }

    /** Re-applies the wording of an earlier version as a new version. */
    @Transactional
    public Applied restore(
            PromptScope scope, UUID agentId, String key, String locale, int toVersion, boolean acknowledgeRisk, String reason) {
        PromptDefinition def = definition(key);
        requireWritable(scope);
        String loc = normalizeLocale(locale);
        String scopeId = scopeId(scope, agentId);
        PromptHistoryEntry old =
                store.history(scope, scopeId, def.key(), loc, properties.getHistoryMaxLimit()).stream()
                        .filter(h -> h.version() == toVersion)
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No version " + toVersion + " of " + key + " at this scope."));
        if (old.text() == null && old.enabled()) {
            throw bad("Version " + toVersion + " was a revert; it has no wording to restore.");
        }
        return put(
                new Write(
                        scope,
                        agentId,
                        key,
                        loc,
                        old.text(),
                        old.enabled(),
                        acknowledgeRisk,
                        reason == null || reason.isBlank() ? "restored version " + toVersion : reason,
                        null));
    }

    // ------------------------------------------------------------------------------------------ internals

    private PromptHistoryEntry revertRow(PromptOverride existing, PromptDefinition def, String reason) {
        int version = store.latestVersion(existing.scope(), existing.scopeId(), def.key(), existing.locale()) + 1;
        Instant now = Instant.now();
        String actor = actor();
        store.deleteCurrent(existing.scope(), existing.scopeId(), def.key(), existing.locale());
        PromptHistoryEntry entry =
                new PromptHistoryEntry(
                        0,
                        existing.scope(),
                        existing.scopeId(),
                        def.key(),
                        existing.locale(),
                        version,
                        PromptHistoryEntry.ACTION_REVERT,
                        null,
                        true,
                        existing.text(),
                        existing.enabled(),
                        trimReason(reason),
                        actor,
                        now);
        store.appendHistory(entry);
        afterWrite(ACTION_REVERTED, def, entry, existing);
        return entry;
    }

    private void afterWrite(String action, PromptDefinition def, PromptHistoryEntry entry, PromptOverride before) {
        runAfterCommit(
                () -> {
                    if (entry.scope() == PromptScope.PLATFORM) {
                        cache.evictPlatform();
                    } else {
                        cache.evictCurrentTenant();
                    }
                });
        String detail = auditDetail(def, entry);
        log.info(
                "Prompt {} {} at {} scope by {} (v{})",
                def.key(),
                action,
                entry.scope().dbValue(),
                entry.createdBy(),
                entry.version());
        try {
            audit.record(entry.createdBy(), action, AUDIT_TARGET, def.key(), AuditOutcome.SUCCESS, detail);
        } catch (RuntimeException e) {
            log.warn("Audit write failed for {} on {} - the change itself stands", action, def.key(), e);
        }
    }

    /** Who, scope, key, versions, hashes and the line diff of the wording. */
    static String auditDetail(PromptDefinition def, PromptHistoryEntry e) {
        String oldText = e.previousText() == null ? "" : e.previousText();
        String newText = e.text() == null ? "" : e.text();
        StringBuilder sb = new StringBuilder();
        sb.append("key=").append(def.key());
        sb.append(" scope=").append(e.scope().dbValue());
        if (!e.scopeId().isEmpty()) {
            sb.append(" scopeId=").append(e.scopeId());
        }
        sb.append(" locale=").append(e.locale());
        sb.append(" action=").append(e.action());
        sb.append(" version=").append(e.version());
        sb.append(" enabled=").append(e.previousEnabled()).append("->").append(e.enabled());
        sb.append(" oldSha256=").append(ResolvedPrompt.hashOf(oldText));
        sb.append(" newSha256=").append(ResolvedPrompt.hashOf(newText));
        if (e.reason() != null && !e.reason().isBlank()) {
            sb.append(" reason=").append(e.reason());
        }
        sb.append("\n").append(PromptDiff.unified(oldText, newText));
        return sb.toString();
    }

    private static void runAfterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            r.run();
                        }
                    });
        } else {
            r.run();
        }
    }

    private PromptView view(PromptDefinition def, PromptContext ctx, Map<String, List<OverrideView>> overrides) {
        String locale = ctx.locale() == null ? resolver.catalog().defaultLocale() : ctx.locale();
        return new PromptView(
                def,
                resolver.catalog().defaultText(def.key(), locale).orElse(""),
                resolver.resolve(def.key(), ctx),
                overrides.getOrDefault(def.key(), List.of()));
    }

    private Map<String, List<OverrideView>> overridesByKey(UUID agentId) {
        Map<String, List<OverrideView>> out = new HashMap<>();
        List<PromptOverride> rows = new ArrayList<>(store.list(PromptScope.PLATFORM, ""));
        if (TenantContext.current().isPresent()) {
            rows.addAll(store.list(PromptScope.TENANT, ""));
            if (agentId != null) {
                rows.addAll(store.list(PromptScope.AGENT, agentId.toString()));
            }
        }
        for (PromptOverride r : rows) {
            out.computeIfAbsent(r.key(), k -> new ArrayList<>())
                    .add(new OverrideView(r.scope(), r.scopeId(), r.locale(), r.text(), r.enabled(), r.version(), r.createdBy(), r.createdAt()));
        }
        return out;
    }

    private PromptContext contextFor(UUID agentId, String locale) {
        PromptContext ctx = PromptContext.none();
        if (agentId != null) {
            ctx = ctx.withAgent(agentId);
        }
        if (locale != null && !locale.isBlank()) {
            ctx = ctx.withLocale(normalizeLocale(locale));
        }
        return ctx;
    }

    private PromptDefinition definition(String key) {
        return resolver.catalog()
                .find(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown prompt key: " + key));
    }

    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return resolver.catalog().defaultLocale();
        }
        String l = locale.strip();
        if (!LOCALE.matcher(l).matches()) {
            throw bad("Locale must look like en or pt-BR.");
        }
        return l;
    }

    private static String scopeId(PromptScope scope, UUID agentId) {
        if (scope == PromptScope.AGENT) {
            if (agentId == null) {
                throw bad("agentId is required for agent scope.");
            }
            return agentId.toString();
        }
        return "";
    }

    private void requireAgentInTenant(PromptScope scope, UUID agentId) {
        if (scope != PromptScope.AGENT) {
            return;
        }
        PromptAgentDirectory directory = agents.getIfAvailable();
        if (directory == null || !directory.existsInCurrentTenant(agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such agent in this workspace.");
        }
    }

    private static void requireWritable(PromptScope scope) {
        TenantContext ctx = TenantContext.current().orElse(null);
        if (scope == PromptScope.PLATFORM) {
            if (ctx == null || !ctx.hasRole(ROLE_PLATFORM_ADMIN)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a platform admin can change platform-wide prompts.");
            }
            return;
        }
        if (ctx == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A workspace is required to change tenant or agent prompts.");
        }
    }

    private static void requireReadable(PromptScope scope) {
        requireWritable(scope);
    }

    private static void checkExpectedVersion(Integer expected, int actual) {
        if (expected != null && expected != actual) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The override changed meanwhile (now version " + actual + ", you edited " + expected + ").");
        }
    }

    private static String trimReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String r = reason.strip();
        return r.length() > 500 ? r.substring(0, 500) : r;
    }

    private static String actor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("system");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
