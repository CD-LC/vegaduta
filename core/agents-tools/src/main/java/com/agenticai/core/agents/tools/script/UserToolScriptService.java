package com.agenticai.core.agents.tools.script;

import com.agenticai.core.security.HotpathLookups;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CRUD for tenant-authored {@link UserToolScript} rows - see that entity's javadoc for the
 * SpEL-sandbox scope decision. {@link #test} lets the frontend try an expression against sample
 * input before saving, using the exact same {@link UserToolScriptEvaluator} {@link
 * UserToolScriptCallback} uses at real call time, so "it worked in the tester" and "it works for
 * the agent" are guaranteed to mean the same thing.
 */
@Service
public class UserToolScriptService {

    // Mirrors the tool-name shape every other tool name in this codebase
    // already uses (google_drive, sarvam_translate, ask_agent_<slug> - see
    // AgentToolProvider#slugify) - lowercase, digits, underscores only, and
    // must start with a letter so it's never ambiguous with a purely
    // numeric/underscore string once prefixed/suffixed elsewhere.
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    private final UserToolScriptRepository repository;
    private final UserToolScriptEvaluator evaluator;

    public UserToolScriptService(
            UserToolScriptRepository repository, UserToolScriptEvaluator evaluator) {
        this.repository = repository;
        this.evaluator = evaluator;
    }

    public List<UserToolScript> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Only what {@link com.agenticai.core.agents.tools.AgentToolProvider} needs at chat-turn time -
     * enabled rows only, as immutable snapshots. Asked on every turn and changed a few times a
     * year, so it is read through the tenant lookup cache: a write to {@link UserToolScript} on
     * this pod evicts it, another pod sees it within {@code app.chat.hotpath.config-lookup-ttl},
     * and a TTL of 0 reads the table every time.
     */
    public List<UserToolScriptSnapshot> listEnabledSnapshots() {
        return HotpathLookups.tenant(
                "tools.user-scripts",
                () ->
                        repository.findAllByEnabledTrue().stream()
                                .map(UserToolScriptSnapshot::from)
                                .toList());
    }

    /**
     * The expression to run for the tool the model called, re-read from the row so an edit, a
     * disable or a delete since the turn started (or since the snapshot cache last refreshed) takes
     * effect on this call, like the custom-code and OpenAPI tools. Empty when the script is gone or
     * disabled.
     */
    public Optional<String> findRunnableExpression(UUID id) {
        return repository
                .findById(id)
                .filter(UserToolScript::isEnabled)
                .map(UserToolScript::getExpression);
    }

    @Transactional
    public UserToolScript create(
            String toolName,
            String displayName,
            String description,
            String expression,
            String inputSchemaJson) {
        validateToolName(toolName, null);
        validateExpression(expression);
        UserToolScript script =
                new UserToolScript(
                        toolName, displayName, description, expression, inputSchemaJson, true);
        return repository.save(script);
    }

    @Transactional
    public UserToolScript update(
            UUID id,
            String displayName,
            String description,
            String expression,
            String inputSchemaJson) {
        UserToolScript script = get(id);
        validateExpression(expression);
        script.update(displayName, description, expression, inputSchemaJson);
        return repository.save(script);
    }

    @Transactional
    public UserToolScript setEnabled(UUID id, boolean enabled) {
        UserToolScript script = get(id);
        script.setEnabled(enabled);
        return repository.save(script);
    }

    @Transactional
    public void delete(UUID id) {
        UserToolScript script = get(id);
        repository.delete(script);
    }

    /**
     * Dry-run: evaluates {@code expression} against {@code sampleInputJson} and returns the result
     * as a string (or the same "{"error": ...}" shape a real tool call would return) - never
     * persists anything, never requires a saved {@link UserToolScript}. Used by the frontend's "Try
     * it" button before Save.
     */
    public String test(String expression, String sampleInputJson) {
        validateExpression(expression);
        return evaluator.evaluate(expression, sampleInputJson);
    }

    private UserToolScript get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new UserToolScriptNotFoundException(id));
    }

    private void validateToolName(String toolName, UUID excludingId) {
        if (toolName == null || !TOOL_NAME_PATTERN.matcher(toolName).matches()) {
            throw new IllegalArgumentException(
                    "Tool name must be 3-64 lowercase letters, digits, or underscores, starting"
                            + " with a letter (e.g. my_custom_tool).");
        }
        boolean taken =
                excludingId == null
                        ? repository.existsByToolName(toolName)
                        : repository.existsByToolNameAndIdNot(toolName, excludingId);
        if (taken) {
            throw new IllegalArgumentException("A tool named \"" + toolName + "\" already exists.");
        }
    }

    private void validateExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Expression is required.");
        }
        // Fails fast on a syntactically-invalid expression at save/test time
        // rather than the first time an agent actually tries to call the
        // tool - same evaluator, just discarding the result. Re-thrown as
        // IllegalArgumentException (-> 400 with the real parse error, via
        // GlobalExceptionHandler's generic handler) rather than left as
        // Spring's own ParseException, which would otherwise fall through
        // to the generic 500 handler - this is a client mistake, not a
        // server fault.
        try {
            evaluator.validateSyntax(expression);
        } catch (org.springframework.expression.ParseException e) {
            throw new IllegalArgumentException("Invalid expression: " + e.getMessage());
        }
    }
}
