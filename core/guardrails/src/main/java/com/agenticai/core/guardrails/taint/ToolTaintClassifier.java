package com.agenticai.core.guardrails.taint;

/**
 * Answers the two taint questions about a tool call. Supplied by {@code core/agents-tools}, which
 * can see the tool-classification registry that this module cannot depend on. Called on the
 * tool-execution thread with the turn's tenant context restored. An implementation that throws is
 * treated as {@link ToolTaintProfile#UNKNOWN} by {@link TaintGate} (conservative: side-effecting).
 */
@FunctionalInterface
public interface ToolTaintClassifier {

    ToolTaintProfile profile(String toolName, String toolInput);
}
