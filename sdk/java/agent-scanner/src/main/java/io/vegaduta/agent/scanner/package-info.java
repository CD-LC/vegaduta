/**
 * Zero-integration risk-map scanner. Point {@link io.vegaduta.agent.scanner.RiskMapScanner} (or the
 * {@link io.vegaduta.agent.scanner.Main} CLI) at a package, a jar or a list of classes and get
 * every tool the agent can call, its classification or {@code UNCLASSIFIED}, approval coverage, the
 * lethal trifecta, OWASP ASI exposure and a readiness score, without changing the application.
 */
@NullMarked
@Experimental
package io.vegaduta.agent.scanner;

import io.vegaduta.agent.api.Experimental;
import org.jspecify.annotations.NullMarked;
