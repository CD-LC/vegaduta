package io.vegaduta.agent.core;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.core.classification.ClassificationResolver;
import io.vegaduta.agent.core.classification.LethalTrifectaDetector;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the lethal-trifecta rule to a set of tools offered together to one agent (see {@link
 * Governance#governAll(java.util.Collection, RunContext)}): in {@link GovernanceMode#STRICT} every
 * exfiltration sink in a set that also reads private data and consumes untrusted input is forced to
 * require approval; in {@link GovernanceMode#WARN} a warning is audited.
 */
final class TrifectaEnforcer {

  // Logged under Governance's category, where these warnings have always appeared.
  private static final Logger log = LoggerFactory.getLogger(Governance.class);

  private final LethalTrifectaDetector detector;
  private final GovernancePolicy policy;
  private final ClassificationResolver resolver;
  private final AuditSink audit;

  TrifectaEnforcer(
      LethalTrifectaDetector detector,
      GovernancePolicy policy,
      ClassificationResolver resolver,
      AuditSink audit) {
    this.detector = detector;
    this.policy = policy;
    this.resolver = resolver;
    this.audit = audit;
  }

  void apply(List<GovernedTool> governed, RunContext context) {
    List<ToolClassification> classifications =
        governed.stream().map(GovernedTool::classification).collect(Collectors.toList());
    LethalTrifectaDetector.Report report = detector.analyse(classifications);
    if (!report.holds()) {
      return;
    }
    String detail =
        "lethal trifecta: private readers "
            + report.privateReaders()
            + ", untrusted consumers "
            + report.untrustedConsumers()
            + ", sinks "
            + report.sinks();
    if (policy.mode() == GovernanceMode.STRICT) {
      for (GovernedTool tool : governed) {
        ToolClassification c = tool.classification();
        if (c.exfiltrationSink() && !c.requiresApproval()) {
          resolver.override(c.toBuilder().requiresApproval(true).build());
        }
      }
      audit.emit(
          trifectaEvent(context, AuditOutcome.WARNING, detail + "; approval forced on sinks"));
      log.warn("Lethal trifecta detected; approval forced on sinks {}", report.sinks());
    } else {
      audit.emit(trifectaEvent(context, AuditOutcome.WARNING, detail));
      log.warn("Lethal trifecta detected (warn mode): {}", detail);
    }
  }

  private static AuditEvent trifectaEvent(RunContext context, AuditOutcome outcome, String detail) {
    return AuditEvent.builder("agent.toolset", "agent", String.valueOf(context.agentId()))
        .context(context)
        .outcome(outcome)
        .detail(detail)
        .risk(AsiRisk.ASI01)
        .risk(AsiRisk.ASI06)
        .build();
  }
}
