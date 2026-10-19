package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;

/** A report over an {@link EvidenceBundle} in two shapes: for people and for machines. */
@Experimental
public interface EvidenceExporter {

  /** Human-readable Markdown. */
  String toMarkdown();

  /** One JSON object per line (JSONL), each with a {@code "section"} field. */
  String toJsonLines();
}
