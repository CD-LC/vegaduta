package com.agenticai.core.agents.tools;

/**
 * Task #959 ("Agent performance: response time + reliability + failure handling") - one agent's
 * all-time tool-call volume/failure-count, read straight off the {@code tool_call_log} rows {@link
 * LoggingToolCallback} already writes for every tool invocation (success or failure). A tool-call
 * failure doesn't always fail the LLM turn that triggered it (the model can recover and answer
 * anyway) - so this is tracked as its own signal alongside {@code AgentLlmCallStats}, not folded
 * into it, giving a tenant-admin visibility into "this agent's tools are flaky" even on turns that
 * ultimately still succeeded.
 */
public record AgentToolCallStats(long totalCalls, long failedCalls) {}
