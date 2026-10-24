package com.agenticai.core.agents.promptdirectives;

import com.agenticai.core.agents.turn.PromptDirectiveRegistry;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptOverrideService;
import com.agenticai.core.prompts.PromptOverrideStore;
import com.agenticai.core.prompts.PromptScope;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The owner's original, tenant-wide view of the standing directives ({@code /api/prompt-directives}),
 * kept for compatibility and now a thin layer over the prompt registry ({@link
 * PromptOverrideService}, {@code /api/prompts}): every write becomes a tenant-scope override with
 * a version, an immutable history row and an audit entry carrying the old-to-new diff. A directive
 * with two wordings (tool_trust full and compact, context_compaction with and without the restore
 * tool) is written to both keys, as the single legacy override always applied to both. New code
 * should use {@code /api/prompts}, which also has the agent and platform scopes.
 */
@Service
public class PromptDirectiveAdminService {

    static final int MAX_DIRECTIVE_TEXT = 4000;
    static final int MAX_CUSTOM_TEXT = 8000;

    /** Where the real, assembled prompt of an agent can be read. */
    static final String PROMPT_PREVIEW_PATH = "/api/agents/{agentId}/prompt-preview";

    static final String PREVIEW_NOTE =
            "Illustrative only: the tenant-wide directives with a sample date, ignoring which tools an agent"
                    + " has, agent and platform overrides, the agent's own prompt, skills, memory and small-model"
                    + " mode. The real prompt of an agent is POST " + PROMPT_PREVIEW_PATH + ".";

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

    /**
     * {@code assembledPreview} is kept for existing clients but is NOT the prompt a model gets;
     * {@code assembledPreviewNote} says so and {@code promptPreviewPath} points at the real one.
     */
    public record Overview(
            List<DirectiveView> directives,
            CustomInstructions customInstructions,
            String assembledPreview,
            String assembledPreviewNote,
            String promptPreviewPath) {}

    private final PromptOverrideStore store;
    private final PromptOverrideService overrides;

    public PromptDirectiveAdminService(PromptOverrideStore store, PromptOverrideService overrides) {
        this.store = store;
        this.overrides = overrides;
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        return overview(LocalDate.now(ZoneOffset.UTC));
    }

    Overview overview(LocalDate sampleDate) {
        Map<String, PromptOverride> rows = new HashMap<>();
        for (PromptOverride r : store.list(PromptScope.TENANT, "")) {
            if (overrides.defaultLocale().equals(r.locale())) {
                rows.put(r.key(), r);
            }
        }
        List<DirectiveView> views = new ArrayList<>();
        List<String> preview = new ArrayList<>();
        for (PromptDirectiveRegistry.Entry e : PromptDirectiveRegistry.all()) {
            PromptOverride row = rows.get(e.primaryPromptKey());
            boolean enabled = row == null || row.enabled();
            String override = row != null && row.text() != null && !row.text().isBlank() ? row.text() : null;
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
        PromptOverride custom = rows.get(PromptDirectiveRegistry.Keys.CUSTOM_INSTRUCTIONS);
        CustomInstructions customView =
                custom == null
                        ? new CustomInstructions(true, "")
                        : new CustomInstructions(custom.enabled(), custom.text() == null ? "" : custom.text());
        if (customView.enabled() && !customView.text().isBlank()) {
            preview.add(customView.text().strip());
        }
        return new Overview(views, customView, String.join("\n\n", preview), PREVIEW_NOTE, PROMPT_PREVIEW_PATH);
    }

    /**
     * Switches a built-in on or off and sets or clears its wording ({@code text} blank or null =
     * the built-in wording).
     */
    @Transactional
    public void put(String key, boolean enabled, String text, boolean acknowledgeRisk) {
        PromptDirectiveRegistry.Entry entry =
                PromptDirectiveRegistry.find(key)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown directive: " + key));
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
        if (!entry.overridable()) {
            return; // retired: nothing is sent, so there is nothing to switch off either
        }
        writeAll(entry.promptKeys(), enabled, clean, acknowledgeRisk);
    }

    /** Back to the built-in wording, on. */
    @Transactional
    public void reset(String key) {
        List<String> keys = PromptDirectiveRegistry.promptKeys(key);
        if (keys.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown directive: " + key);
        }
        revertAll(keys);
    }

    @Transactional
    public void putCustomInstructions(boolean enabled, String text) {
        String clean = text == null || text.isBlank() ? null : text.strip();
        if (clean != null && clean.length() > MAX_CUSTOM_TEXT) {
            throw bad("Custom instructions are limited to " + MAX_CUSTOM_TEXT + " characters.");
        }
        writeAll(List.of(PromptDirectiveRegistry.Keys.CUSTOM_INSTRUCTIONS), enabled, clean, false);
    }

    private void writeAll(List<String> promptKeys, boolean enabled, String clean, boolean acknowledgeRisk) {
        if (enabled && clean == null) {
            revertAll(promptKeys);
            return;
        }
        for (String k : promptKeys) {
            overrides.put(
                    new PromptOverrideService.Write(
                            PromptScope.TENANT, null, k, null, clean, enabled, acknowledgeRisk, "via /api/prompt-directives", null));
        }
    }

    private void revertAll(List<String> promptKeys) {
        for (String k : promptKeys) {
            if (store.find(PromptScope.TENANT, "", k, overrides.defaultLocale()).isPresent()) {
                overrides.revert(PromptScope.TENANT, null, k, overrides.defaultLocale(), "via /api/prompt-directives", null);
            }
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
