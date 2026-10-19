package com.agenticai.core.agents.tools.script;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * #424 ("Dynamic MCP servers + user-created tools") - the "user-created tools (generated scripts)"
 * half, deliberately scoped down from what that name might suggest. There is no sandboxed
 * script-execution engine anywhere in this codebase (see {@code
 * WorkflowNodeExecutor#executeCodeNode}'s javadoc, which explicitly rules one out as new
 * infrastructure/security- review surface that feature didn't need), and this repo has no way to
 * verify a from-scratch one (GraalVM polyglot, containerized subprocess, etc.) actually behaves
 * correctly without running it - that's a real build, not a call to make blind.
 *
 * <p>Instead, a "user-created tool" here is a Spring Expression Language (SpEL) expression,
 * evaluated against this turn's JSON tool input via {@link
 * org.springframework.expression.spel.support.SimpleEvaluationContext} - see {@link
 * UserToolScriptCallback} for the actual evaluation. That context is sandboxed BY CONSTRUCTION, not
 * by convention: it refuses method invocation, constructor invocation, bean references, and type
 * references outright (not merely "we don't call untrusted methods" - the grammar itself has no
 * loop construct at all, so an expression cannot even attempt to run forever). A tenant can build
 * real data-transform tools this way (pull a field out of the input, format a string, do
 * arithmetic, branch with a ternary/elvis operator) without this platform ever executing arbitrary
 * tenant-authored code. This is a smaller capability than "write me a script that does anything" -
 * documented here honestly rather than implied to be more than it is.
 */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@Table(name = "user_tool_script", uniqueConstraints = @UniqueConstraint(columnNames = "tool_name"))
public class UserToolScript {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // The literal tool name an agent's LLM sees and calls by - must be a
    // schema-safe identifier (validated in UserToolScriptService), not
    // free text like displayName.
    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    // The SpEL expression itself - see class javadoc for the sandboxing
    // model. Evaluated fresh on every call against that call's parsed JSON
    // input as the SimpleEvaluationContext's root object.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String expression;

    // Optional - shown to the LLM as this tool's inputSchema so it knows
    // what shape of JSON to send. Free-form JSON Schema text the tenant
    // supplies themselves (not validated as JSON Schema server-side); a
    // generic permissive object schema is used when blank - see
    // UserToolScriptCallback.
    @Column(name = "input_schema_json", columnDefinition = "TEXT")
    private String inputSchemaJson;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserToolScript() {
        // JPA
    }

    public UserToolScript(
            String toolName,
            String displayName,
            String description,
            String expression,
            String inputSchemaJson,
            boolean enabled) {
        this.toolName = toolName;
        this.displayName = displayName;
        this.description = description;
        this.expression = expression;
        this.inputSchemaJson = inputSchemaJson;
        this.enabled = enabled;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(
            String displayName, String description, String expression, String inputSchemaJson) {
        this.displayName = displayName;
        this.description = description;
        this.expression = expression;
        this.inputSchemaJson = inputSchemaJson;
        this.updatedAt = Instant.now();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getToolName() {
        return toolName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getExpression() {
        return expression;
    }

    public String getInputSchemaJson() {
        return inputSchemaJson;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
