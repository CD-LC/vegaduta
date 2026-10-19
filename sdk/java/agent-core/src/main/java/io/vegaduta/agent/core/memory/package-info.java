/**
 * Memory-write governance (OWASP ASI06, Memory and Context Poisoning).
 *
 * <p>In a multi-agent system one agent writes a hallucinated or injected "fact" to shared memory
 * and every later agent treats it as verified. The damage builds slowly, so this package treats a
 * memory write as a privileged event with provenance: {@link
 * io.vegaduta.agent.core.memory.GovernedMemoryStore} screens values with the built-in guardrails,
 * quarantines entries from untrusted sources until a human or a trusted process promotes them, caps
 * the number of writes per run and audits everything. {@link
 * io.vegaduta.agent.core.memory.MemoryToolHook} blocks memory-writing tool calls whose arguments
 * carry injection phrases, and {@link io.vegaduta.agent.core.memory.PoisonProbe} is a cheap canary
 * for delayed-trigger poisoning.
 */
@NullMarked
@Experimental
package io.vegaduta.agent.core.memory;

import io.vegaduta.agent.api.Experimental;
import org.jspecify.annotations.NullMarked;
