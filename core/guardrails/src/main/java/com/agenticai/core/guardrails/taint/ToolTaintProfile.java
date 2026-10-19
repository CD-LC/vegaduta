package com.agenticai.core.guardrails.taint;

/**
 * What the taint layer needs to know about one tool call.
 *
 * @param producesTaint the tool's output can carry content an outsider authored (web page, MCP
 *     server result, retrieved document, inbound message)
 * @param sink the call has side effects (writes, sends, executes, pays, outbound traffic) and so
 *     must not run on the strength of tainted content without a human
 * @param label short, log-safe description (no arguments, no output)
 */
public record ToolTaintProfile(boolean producesTaint, boolean sink, String label) {

    /** A tool nobody classified: side-effecting until proven otherwise, source of nothing known. */
    public static final ToolTaintProfile UNKNOWN = new ToolTaintProfile(false, true, "unclassified");

    public ToolTaintProfile {
        label = label == null ? "" : label;
    }
}
