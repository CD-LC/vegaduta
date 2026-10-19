package io.vegaduta.agent.core;

import io.vegaduta.agent.api.approval.ApprovalDecisionAuthorizer;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.budget.BudgetCharge;
import io.vegaduta.agent.api.budget.BudgetProvider;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.guardrail.ContentGuardrail;
import io.vegaduta.agent.api.journal.EffectJournal;
import io.vegaduta.agent.api.observe.ToolCallObserver;
import io.vegaduta.agent.api.risk.ToolClassificationSource;
import io.vegaduta.agent.core.approval.ApprovalActionExecutorRegistry;
import io.vegaduta.agent.core.approval.DefaultApprovalGate;
import io.vegaduta.agent.core.approval.FourEyesAuthorizer;
import io.vegaduta.agent.core.approval.InMemoryReceiptStore;
import io.vegaduta.agent.core.approval.ReceiptAuditSink;
import io.vegaduta.agent.core.approval.ReceiptVerifier;
import io.vegaduta.agent.core.audit.CompositeAuditSink;
import io.vegaduta.agent.core.audit.LoggingAuditSink;
import io.vegaduta.agent.core.autonomy.AutonomyGate;
import io.vegaduta.agent.core.autonomy.AutonomyRegistry;
import io.vegaduta.agent.core.autonomy.AutonomyResolver;
import io.vegaduta.agent.core.budget.InMemoryBudgetProvider;
import io.vegaduta.agent.core.classification.ClassificationResolver;
import io.vegaduta.agent.core.classification.CompositeClassificationSource;
import io.vegaduta.agent.core.cost.CostAuditSink;
import io.vegaduta.agent.core.cost.CostAwareBudgetProvider;
import io.vegaduta.agent.core.cost.CostLedger;
import io.vegaduta.agent.core.cost.CostObserver;
import io.vegaduta.agent.core.cost.ToolCostEstimator;
import io.vegaduta.agent.core.evidence.HashChainedAuditSink;
import io.vegaduta.agent.core.gate.CompositeToolDispatchGate;
import io.vegaduta.agent.core.gate.PolicyToolDispatchGate;
import io.vegaduta.agent.core.guardrail.GuardrailChain;
import io.vegaduta.agent.core.halt.HaltGate;
import io.vegaduta.agent.core.halt.HaltSwitch;
import io.vegaduta.agent.core.hook.HookEngine;
import io.vegaduta.agent.core.journal.InMemoryEffectJournal;
import io.vegaduta.agent.core.journal.ReconcilingEffectJournal;
import io.vegaduta.agent.core.mandate.MandateGate;
import io.vegaduta.agent.core.observe.CompositeToolCallObserver;
import io.vegaduta.agent.core.provenance.ProvenanceGate;
import io.vegaduta.agent.core.replay.ShadowGate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The collaborators one {@link Governance} is built from, derived from a {@link GovernanceSpec}.
 *
 * <p>Derived collaborators (cost ledger, cost and receipt sinks, the cost-aware budget) are
 * computed here per build and never written back into the spec, so a builder reused for a second
 * build does not carry the first build's ledger or sinks. Order matters: the audit sink is
 * assembled first because the shadow and provenance gates, the approval gate and the reconciling
 * journal all emit to it.
 */
final class GovernanceAssembly {

  final GovernancePolicy policy;
  final Clock clock;
  final ClassificationResolver resolver;
  final HookEngine hooks;
  final AuditSink audit;
  final @Nullable ReceiptAuditSink receipts;
  final @Nullable HaltSwitch haltSwitch;
  final @Nullable AutonomyRegistry autonomy;
  final ToolDispatchGate gate;
  final @Nullable ProvenanceGate provenanceGate;
  final DefaultApprovalGate approvals;
  final BudgetProvider budgets;
  final @Nullable CostLedger costLedger;
  final ToolCostEstimator estimator;
  final EffectJournal journal;
  final ContentGuardrail guardrails;
  final ToolCallObserver observer;

  GovernanceAssembly(GovernanceSpec s) {
    this.policy = s.policy;
    this.clock = s.clock;

    // Derived wiring: cost attribution and receipts add sinks and observers of their own.
    List<AuditSink> auditSinks = new ArrayList<>(s.auditSinks);
    List<ToolCallObserver> observers = new ArrayList<>(s.observers);
    BudgetProvider wiredBudgets = s.budgets;
    ToolCostEstimator wiredEstimator = s.estimator;
    CostLedger ledger = null;
    if (s.costModel != null) {
      ledger = new CostLedger(s.costModel);
      observers.add(new CostObserver(ledger));
      auditSinks.add(new CostAuditSink(ledger));
      if (wiredBudgets == null) {
        wiredBudgets = CostAwareBudgetProvider.inMemory(s.costModel, runCap(s.policy));
      }
      if (wiredEstimator == null && !(wiredBudgets instanceof ToolCostEstimator)) {
        wiredEstimator = new CostAwareBudgetProvider(wiredBudgets, s.costModel);
      }
    }
    ReceiptAuditSink receiptSink = null;
    if (s.receipts) {
      ReceiptVerifier verifier =
          s.receiptVerifier != null
              ? s.receiptVerifier
              : s.receiptSigner instanceof ReceiptVerifier v ? v : null;
      // Must read decided requests back from the same store the approval gate writes to.
      receiptSink =
          new ReceiptAuditSink(
              s.approvalStore,
              s.receiptStore != null ? s.receiptStore : new InMemoryReceiptStore(),
              s.receiptSigner,
              verifier);
      auditSinks.add(receiptSink);
    }
    // CostAuditSink and ReceiptAuditSink are derived views, not an audit trail.
    if (auditSinks.stream()
        .allMatch(sink -> sink instanceof CostAuditSink || sink instanceof ReceiptAuditSink)) {
      auditSinks.add(new LoggingAuditSink());
    }

    List<ToolClassificationSource> sources = new ArrayList<>();
    sources.add(s.registered);
    sources.addAll(s.sources);
    CompositeClassificationSource source = new CompositeClassificationSource(sources);
    this.resolver = new ClassificationResolver(source, policy);
    this.hooks = s.hooks;
    AuditSink composite = CompositeAuditSink.of(auditSinks.toArray(new AuditSink[0]));
    // One hash chain over every sink, so the chain covers whatever the host persists. The chained
    // sink is itself wrapped so a signer or canonicalisation failure never reaches the pipeline.
    this.audit =
        s.tamperEvident
            ? CompositeAuditSink.safe(
                new HashChainedAuditSink(composite, s.chainSigner, s.chainLastSeq, s.chainLastHash))
            : composite;
    this.receipts = receiptSink;
    this.haltSwitch = s.haltSwitch;
    this.autonomy = s.autonomy;
    this.provenanceGate = provenanceGate(s, audit);
    this.gate = composeGate(s, resolver, audit, provenanceGate);
    ApprovalActionExecutorRegistry executors = new ApprovalActionExecutorRegistry();
    ApprovalDecisionAuthorizer authorizer =
        s.authorizer != null
            ? s.authorizer
            : (policy.fourEyes()
                ? new FourEyesAuthorizer()
                : ApprovalDecisionAuthorizer.permitAll());
    this.approvals =
        new DefaultApprovalGate(
            s.approvalStore, authorizer, executors, audit, hooks, policy.approvalTtl(), s.clock);
    this.budgets = wiredBudgets != null ? wiredBudgets : new InMemoryBudgetProvider(runCap(policy));
    this.costLedger = ledger;
    if (wiredEstimator != null) {
      this.estimator = wiredEstimator;
    } else if (this.budgets instanceof ToolCostEstimator costAware) {
      this.estimator = costAware;
    } else {
      this.estimator = ToolCostEstimator.constant(policy.estimatedUsdPerToolCall());
    }
    EffectJournal base =
        s.journal != null ? s.journal : new InMemoryEffectJournal(policy.effectJournalTtl());
    this.journal =
        s.reconciler == null
            ? base
            : ReconcilingEffectJournal.wrap(base)
                .reconciler(s.reconciler)
                .auditSink(audit)
                .clock(clock)
                .unknownPolicy(s.unknownEffectPolicy)
                .build();
    this.guardrails = s.guardrails != null ? s.guardrails : GuardrailChain.builtIn();
    this.observer = CompositeToolCallObserver.of(observers);
  }

  private static BudgetCharge runCap(GovernancePolicy policy) {
    return new BudgetCharge(
        policy.maxToolCallsPerRun(), policy.maxTokensPerRun(), policy.maxUsdPerRun());
  }

  private static @Nullable ProvenanceGate provenanceGate(GovernanceSpec s, AuditSink audit) {
    if (s.provenanceStore == null) {
      return null;
    }
    ProvenanceGate.Builder pg =
        ProvenanceGate.builder(s.provenanceStore, Objects.requireNonNull(s.provenanceKey))
            .manifest(Objects.requireNonNull(s.provenanceManifest))
            .mode(s.provenanceMode)
            .pinOnFirstUse(s.provenancePinOnFirstUse)
            .auditSink(audit);
    if (s.provenanceCustomizer != null) {
      s.provenanceCustomizer.accept(pg);
    }
    return pg.build();
  }

  /**
   * Halt, mandate, autonomy and provenance gates in front of the policy gate; see the {@link
   * Governance} class Javadoc.
   */
  private static ToolDispatchGate composeGate(
      GovernanceSpec s,
      ClassificationResolver resolver,
      AuditSink audit,
      @Nullable ProvenanceGate provenanceGate) {
    ToolDispatchGate policyGate = s.gate != null ? s.gate : new PolicyToolDispatchGate(resolver);
    if (s.shadowPolicy != null) {
      // The candidate shares the live resolver's sources and run-time overrides (lethal trifecta)
      // and, when the host gate is a PolicyToolDispatchGate, its allow and deny lists, so only the
      // policy knobs differ between the two verdicts.
      PolicyToolDispatchGate candidate =
          new PolicyToolDispatchGate(resolver.sharing(s.shadowPolicy));
      if (policyGate instanceof PolicyToolDispatchGate live) {
        candidate.allow(live.allowList().toArray(new String[0]));
        candidate.deny(live.denyList().toArray(new String[0]));
      }
      policyGate = new ShadowGate(policyGate, candidate, audit);
    }
    List<ToolDispatchGate> gates = new ArrayList<>();
    if (s.haltSwitch != null) {
      gates.add(new HaltGate(s.haltSwitch));
    }
    if (s.mandates) {
      gates.add(new MandateGate(resolver, s.requireMandate, null, s.clock));
    }
    if (s.autonomy != null) {
      gates.add(new AutonomyGate(new AutonomyResolver(s.autonomy), resolver));
    }
    if (provenanceGate != null) {
      gates.add(provenanceGate);
    }
    gates.add(policyGate);
    return CompositeToolDispatchGate.of(gates);
  }
}
