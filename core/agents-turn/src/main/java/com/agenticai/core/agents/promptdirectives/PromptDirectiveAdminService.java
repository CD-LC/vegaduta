package com.agenticai.core.agents.promptdirectives;

import com.agenticai.core.agents.turn.PromptDirectiveRegistry;
import com.agenticai.core.agents.turn.PromptDirectiveSettings;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.logging.LogSafe;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-facing read and write of the tenant's prompt-directive settings. Every write validates (a
 * security-critical directive cannot be switched off or reworded without an explicit
 * acknowledgement), evicts the chat path's cache and leaves an audit entry that names the key but
 * never the text.
 */
@Service
public class PromptDirectiveAdminService {

    static final int MAX_DIRECTIVE_TEXT = 4000;
    static final int MAX_CUSTOM_TEXT = 8000;
    static final String ACTION_UPDATED = "PROMPT_DIRECTIVE_UPDATED";
    static final String ACTION_RESET = "PROMPT_DIRECTIVE_RESET";
    private static final Logger log = LoggerFactory.getLogger(PromptDirectiveAdminService.class);

    /** One built-in directive as the console shows it. */
    public record DirectiveView(
            String key,
            String title,
            String description,
            boolean securityCritical,
            List<String> placeholders,
            boolean enabled,
            boolean overridden,
            String defaultText,
            String overrideText,
            String effectiveText) {}

    /** The owner's free-text block appended for every agent. */
    public record CustomInstructions(boolean enabled, String text) {}

    public record Overview(
            List<DirectiveView> directives,
            CustomInstructions customInstructions,
            String assembledPreview) {}

    private final PromptDirectiveSettingRepository repository;
    private final PromptDirectiveSettings settings;
    private final AuditLogService audit;

    public PromptDirectiveAdminService(
            PromptDirectiveSettingRepository repository,
            PromptDirectiveSettings settings,
            AuditLogService audit) {
        this.repository = repository;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        return overview(LocalDate.now(ZoneOffset.UTC));
    }

    Overview overview(LocalDate sampleDate) {
        Map<String, PromptDirectiveSetting> rows = new HashMap<>();
        repository.findAll().forEach(r -> rows.put(r.getDirectiveKey(), r));
        List<DirectiveView> views = new ArrayList<>();
        List<String> preview = new ArrayList<>();
        for (PromptDirectiveRegistry.Entry e : PromptDirectiveRegistry.all()) {
            PromptDirectiveSetting row = rows.get(e.key());
            boolean enabled = row == null || row.isEnabled();
            String override =
                    row != null && row.getTextOverride() != null && !row.getTextOverride().isBlank()
                            ? row.getTextOverride()
                            : null;
            String effective = null;
            if (enabled && e.overridable()) {
                effective = (override != null ? override : e.defaultText());
                if (PromptDirectiveRegistry.CURRENT_DATE.equals(e.key())) {
                    effective = effective.replace("{date}", sampleDate.toString());
                }
                preview.add(effective);
            }
            views.add(
                    new DirectiveView(
                            e.key(),
                            e.title(),
                            e.description(),
                            e.securityCritical(),
                            e.placeholders(),
                            enabled,
                            override != null,
                            e.defaultText(),
                            override,
                            effective));
        }
        PromptDirectiveSetting custom = rows.get(PromptDirectiveRegistry.CUSTOM_INSTRUCTIONS_KEY);
        CustomInstructions customView =
                custom == null
                        ? new CustomInstructions(true, "")
                        : new CustomInstructions(
                                custom.isEnabled(),
                                custom.getTextOverride() == null ? "" : custom.getTextOverride());
        if (customView.enabled() && !customView.text().isBlank()) {
            preview.add(customView.text().strip());
        }
        return new Overview(views, customView, String.join("\n\n", preview));
    }

    /**
     * Switches a built-in on or off and sets or clears its wording ({@code text} blank or null =
     * the built-in wording).
     */
    @Transactional
    public void put(String key, boolean enabled, String text, boolean acknowledgeRisk) {
        PromptDirectiveRegistry.Entry entry =
                PromptDirectiveRegistry.find(key)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Unknown directive: " + key));
        String clean = text == null || text.isBlank() ? null : text.strip();
        if (clean != null && clean.length() > MAX_DIRECTIVE_TEXT) {
            throw bad("Directive text is limited to " + MAX_DIRECTIVE_TEXT + " characters.");
        }
        if (clean != null && !entry.overridable()) {
            throw bad(entry.title() + " sends no text today, so there is nothing to reword.");
        }
        if (clean != null && clean.equals(entry.defaultText())) {
            clean = null;
        }
        if (entry.securityCritical() && (!enabled || clean != null) && !acknowledgeRisk) {
            throw bad(
                    entry.title()
                            + " is a security directive. Switching it off or rewording it can make"
                            + " the agent obey instructions hidden in tool output. Send"
                            + " acknowledgeRisk=true to confirm.");
        }
        if (enabled && clean == null) {
            repository.findById(key).ifPresent(repository::delete);
        } else {
            save(key, enabled, clean);
        }
        afterWrite(ACTION_UPDATED, key, "enabled=" + enabled + ", reworded=" + (clean != null));
    }

    /** Back to the built-in wording, on. */
    @Transactional
    public void reset(String key) {
        boolean known =
                PromptDirectiveRegistry.CUSTOM_INSTRUCTIONS_KEY.equals(key)
                        || PromptDirectiveRegistry.find(key).isPresent();
        if (!known) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown directive: " + key);
        }
        repository.findById(key).ifPresent(repository::delete);
        afterWrite(ACTION_RESET, key, "reset to default");
    }

    @Transactional
    public void putCustomInstructions(boolean enabled, String text) {
        String clean = text == null || text.isBlank() ? null : text.strip();
        if (clean != null && clean.length() > MAX_CUSTOM_TEXT) {
            throw bad("Custom instructions are limited to " + MAX_CUSTOM_TEXT + " characters.");
        }
        if (clean == null && enabled) {
            repository
                    .findById(PromptDirectiveRegistry.CUSTOM_INSTRUCTIONS_KEY)
                    .ifPresent(repository::delete);
        } else {
            save(PromptDirectiveRegistry.CUSTOM_INSTRUCTIONS_KEY, enabled, clean);
        }
        afterWrite(
                ACTION_UPDATED,
                PromptDirectiveRegistry.CUSTOM_INSTRUCTIONS_KEY,
                "enabled=" + enabled + ", chars=" + (clean == null ? 0 : clean.length()));
    }

    private void save(String key, boolean enabled, String text) {
        PromptDirectiveSetting row = repository.findById(key).orElse(null);
        if (row == null) {
            repository.save(new PromptDirectiveSetting(key, enabled, text, actor()));
        } else {
            row.apply(enabled, text, actor());
            repository.save(row);
        }
    }

    private void afterWrite(String action, String key, String detail) {
        settings.evictCurrentTenant();
        log.info(
                "Prompt directive {} {} by {} ({})",
                LogSafe.clean(key),
                LogSafe.clean(action),
                LogSafe.clean(actor()),
                LogSafe.clean(detail));
        try {
            audit.record(actor(), action, "prompt_directive", key, AuditOutcome.SUCCESS, detail);
        } catch (RuntimeException e) {
            log.warn("Audit write failed for {} on {} - the change itself stands", action, key, e);
        }
    }

    private static String actor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("system");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
