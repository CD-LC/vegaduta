package com.agenticai.core.agents.tools.script;

import java.util.UUID;

/**
 * What a chat turn needs from one enabled {@link UserToolScript}: the tool definition the model
 * sees and the expression to evaluate (the expression is re-read by id when the tool is called). An
 * immutable value built inside the loading transaction, so it is safe to cache per pod and to hand
 * to another thread (a detached JPA entity is neither).
 */
public record UserToolScriptSnapshot(
        UUID id, String toolName, String description, String expression, String inputSchemaJson) {

    static UserToolScriptSnapshot from(UserToolScript script) {
        return new UserToolScriptSnapshot(
                script.getId(),
                script.getToolName(),
                script.getDescription(),
                script.getExpression(),
                script.getInputSchemaJson());
    }
}
