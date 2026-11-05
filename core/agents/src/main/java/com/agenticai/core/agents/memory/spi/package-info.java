/**
 * Ports of the unified agent memory (ADR-0028). Vocabulary and interfaces only: no agents-turn,
 * knowledge or persistence types. Implementations live in {@code core/agents-memory} (merge, store)
 * and {@code core/agents-turn} (the semantic adapter over {@code agent_memory} and {@code
 * tenant_memory}).
 *
 * <p>The read port ({@link com.agenticai.core.agents.memory.spi.MemoryRecallSource}) can only
 * return TRUSTED + ACTIVE items; the write port ({@link
 * com.agenticai.core.agents.memory.spi.MemoryProposalSink}) can only create PENDING or QUARANTINED
 * proposals. Both guarantees are enforced by the types, not by convention.
 */
package com.agenticai.core.agents.memory.spi;
