package com.agenticai.core.agents.job.operator;

import com.agenticai.core.agents.job.AgentJobStatus;

/**
 * One {@code (status, count)} pair out of the operator roll-up query.
 *
 * <p>A JPQL constructor-expression projection rather than {@code Object[]} or a
 * client-side {@code groupingBy} over loaded rows, for the same reason {@code
 * AgentFallbackTotals} is one: the alternative is dragging every {@code
 * agent_job} row - goal, result text, trace - across the wire purely to add up
 * six numbers, and the terminal statuses on that table grow without bound.
 *
 * <p>{@code count} is a primitive {@code long} even though JPQL's {@code
 * count()} yields a {@code Long}; Hibernate unboxes into the constructor, which
 * is already how {@code AgentRepository#sumFallbackCounts} works in this module.
 */
public record AgentJobStatusCount(AgentJobStatus status, long count) {
}
