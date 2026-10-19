/**
 * Tool supply-chain pinning and drift detection (OWASP ASI04, Agentic Supply Chain
 * Vulnerabilities).
 *
 * <p>An MCP server or plugin registry can change the tools it offers, their schemas and their
 * descriptions at any time, under a running agent. A package that shipped clean for fifteen
 * releases can add an exfiltration tool in the sixteenth; a description edit can carry injected
 * instructions the model will follow. The types here let a host:
 *
 * <ul>
 *   <li>{@link io.vegaduta.agent.core.provenance.ToolManifest}: hash the tool list into a canonical
 *       manifest with a per-tool and an overall SHA-256, serialised as a line-based lockfile
 *       ({@code vegaduta-tools.lock}) that can be committed next to the code
 *   <li>{@link io.vegaduta.agent.core.provenance.ManifestPinStore}: keep the manifest an agent was
 *       approved against (in memory or on disk)
 *   <li>{@link io.vegaduta.agent.core.provenance.ToolDriftDetector}: compare the pinned manifest to
 *       the one currently offered and classify what changed
 *   <li>{@link io.vegaduta.agent.core.provenance.ProvenanceGate}: a {@link
 *       io.vegaduta.agent.api.gate.ToolDispatchGate} that denies, or warns about, calls to tools
 *       that were added or changed since the pin
 *   <li>{@link io.vegaduta.agent.core.provenance.ToolDescriptionGuard}: scan tool descriptions for
 *       prompt-injection phrases
 * </ul>
 */
@Experimental
@NullMarked
package io.vegaduta.agent.core.provenance;

import io.vegaduta.agent.api.Experimental;
import org.jspecify.annotations.NullMarked;
