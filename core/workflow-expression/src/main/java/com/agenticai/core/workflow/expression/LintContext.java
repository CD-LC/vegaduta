package com.agenticai.core.workflow.expression;

import java.util.Map;
import java.util.Set;

/**
 * What the linter knows about the workflow around the template being checked. Every part is
 * optional: a null part disables the matching checks.
 *
 * @param labelToId upstream node label -> node id (drives UNKNOWN_NODE)
 * @param ambiguousLabels labels shared by more than one node (drives AMBIGUOUS_NODE)
 * @param knownInputFields top-level fields of the current node's input, if known (drives
 *     LEGACY_UNKNOWN_NAME)
 */
public record LintContext(
        Map<String, String> labelToId, Set<String> ambiguousLabels, Set<String> knownInputFields) {

    public static LintContext empty() {
        return new LintContext(null, null, null);
    }
}
