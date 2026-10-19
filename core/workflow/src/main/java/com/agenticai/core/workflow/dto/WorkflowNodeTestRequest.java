package com.agenticai.core.workflow.dto;

import java.util.Map;

/**
 * Body of {@code POST /api/workflows/{id}/nodes/{nodeId}/test}.
 *
 * @param input        the text the node should receive. When null, the input
 *                     is taken from {@code pinnedInputs} (see below).
 * @param pinnedInputs upstream node id -> that node's pinned output (a string, or any JSON value, which is serialized), as the
 *                     canvas holds them. Used only when {@code input} is null:
 *                     the first incoming edge (graph order) whose source has a
 *                     pinned value supplies the input. Anything else is ignored.
 */
public record WorkflowNodeTestRequest(String input, Map<String, Object> pinnedInputs) {
}
