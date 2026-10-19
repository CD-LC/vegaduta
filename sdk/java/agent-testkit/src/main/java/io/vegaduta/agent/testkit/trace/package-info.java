/**
 * Record/replay traces for deterministic regression tests of nondeterministic agents.
 *
 * <p>{@link io.vegaduta.agent.testkit.trace.TraceRecorder} captures every governed tool call into a
 * {@link io.vegaduta.agent.testkit.trace.Trace}, which is saved as JSON Lines. {@link
 * io.vegaduta.agent.testkit.trace.ReplayToolbox} turns that file back into tools that serve the
 * recorded outputs, {@link io.vegaduta.agent.testkit.trace.TraceDiff} and {@link
 * io.vegaduta.agent.testkit.trace.TraceAssert} compare runs, and {@link
 * io.vegaduta.agent.testkit.trace.TraceKit} wires it into {@link
 * io.vegaduta.agent.testkit.GovernanceTestKit} in one call.
 */
@Experimental
@NullMarked
package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;
import org.jspecify.annotations.NullMarked;
