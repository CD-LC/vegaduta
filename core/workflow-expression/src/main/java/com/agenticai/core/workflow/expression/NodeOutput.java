package com.agenticai.core.workflow.expression;

/**
 * An upstream node's result as the resolver sees it: the raw output string plus lazily parsed
 * value. Immutable and thread-safe (the parsed value is computed at most a few times,
 * idempotently).
 */
public final class NodeOutput {

    private final String id;
    private final String label;
    private final String output;
    private final String status;
    private final String summary;
    private volatile Object[] parsed;

    public NodeOutput(String id, String label, String output, String status, String summary) {
        this.id = id;
        this.label = label;
        this.output = output == null ? "" : output;
        this.status = status;
        this.summary = summary;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** The node's raw output text. */
    public String output() {
        return output;
    }

    public String status() {
        return status;
    }

    public String summary() {
        return summary;
    }

    /** Output parsed as JSON when it is JSON, else the raw string. */
    public Object value() {
        Object[] p = parsed;
        if (p == null) {
            p = new Object[] {Values.parseLoose(output)};
            parsed = p;
        }
        return p[0];
    }
}
