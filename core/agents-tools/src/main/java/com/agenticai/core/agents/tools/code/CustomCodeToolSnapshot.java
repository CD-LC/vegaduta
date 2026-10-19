package com.agenticai.core.agents.tools.code;

import java.util.UUID;

/**
 * What a chat turn needs from one runnable {@link CustomCodeTool}: the tool definition the model
 * sees and the id to run it by. The source code, secret allow-list and limits are deliberately not
 * here: a call re-reads the row ({@link CustomCodeToolService#executeById}), so an edit or a
 * disable takes effect on the next call and nothing sensitive sits in a cache. An immutable value
 * built inside the loading transaction, so it is safe to cache per pod.
 */
public record CustomCodeToolSnapshot(
        UUID id,
        String toolName,
        String description,
        String inputSchemaJson,
        String outputDescription,
        CustomCodeToolLanguage language) {

    static CustomCodeToolSnapshot from(CustomCodeTool tool) {
        return new CustomCodeToolSnapshot(
                tool.getId(),
                tool.getToolName(),
                tool.getDescription(),
                tool.getInputSchemaJson(),
                tool.getOutputDescription(),
                tool.getLanguage());
    }
}
