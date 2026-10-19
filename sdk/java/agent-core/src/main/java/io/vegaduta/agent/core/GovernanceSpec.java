package io.vegaduta.agent.core;

import io.vegaduta.agent.api.approval.ApprovalDecisionAuthorizer;
import io.vegaduta.agent.api.approval.ApprovalStore;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.budget.BudgetProvider;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.guardrail.ContentGuardrail;
import io.vegaduta.agent.api.journal.EffectJournal;
import io.vegaduta.agent.api.observe.ToolCallObserver;
import io.vegaduta.agent.api.risk.ToolClassificationSource;
import io.vegaduta.agent.core.approval.ApprovalNotifier;
import io.vegaduta.agent.core.approval.InMemoryApprovalStore;
import io.vegaduta.agent.core.approval.ReceiptSigner;
import io.vegaduta.agent.core.approval.ReceiptStore;
import io.vegaduta.agent.core.approval.ReceiptVerifier;
import io.vegaduta.agent.core.autonomy.AutonomyRegistry;
import io.vegaduta.agent.core.classification.StaticClassificationSource;
import io.vegaduta.agent.core.cost.CostModel;
import io.vegaduta.agent.core.cost.ToolCostEstimator;
import io.vegaduta.agent.core.evidence.HashChainedAuditSink;
import io.vegaduta.agent.core.evidence.Signer;
import io.vegaduta.agent.core.halt.HaltSwitch;
import io.vegaduta.agent.core.hook.HookEngine;
import io.vegaduta.agent.core.journal.EffectReconciler;
import io.vegaduta.agent.core.journal.UnknownEffectPolicy;
import io.vegaduta.agent.core.provenance.ManifestPinStore;
import io.vegaduta.agent.core.provenance.ProvenanceGate;
import io.vegaduta.agent.core.provenance.ProvenanceMode;
import io.vegaduta.agent.core.provenance.ToolManifest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Everything a {@link Governance.Builder} collects, as plain mutable state. The builder validates
 * and writes it; {@link GovernanceAssembly} reads it once per {@link Governance.Builder#build()}.
 * Package-private: the builder's fluent methods are the public surface.
 */
final class GovernanceSpec {
  GovernancePolicy policy = GovernancePolicy.defaults();
  final StaticClassificationSource registered = new StaticClassificationSource();
  final List<ToolClassificationSource> sources = new ArrayList<>();
  @Nullable ToolDispatchGate gate;
  @Nullable GovernancePolicy shadowPolicy;
  @Nullable HaltSwitch haltSwitch;
  boolean mandates;
  boolean requireMandate;
  @Nullable AutonomyRegistry autonomy;
  @Nullable ManifestPinStore provenanceStore;
  @Nullable String provenanceKey;
  @Nullable Supplier<ToolManifest> provenanceManifest;
  ProvenanceMode provenanceMode = ProvenanceMode.ENFORCE;
  boolean provenancePinOnFirstUse;
  @Nullable Consumer<ProvenanceGate.Builder> provenanceCustomizer;
  ApprovalStore approvalStore = new InMemoryApprovalStore();
  @Nullable ApprovalDecisionAuthorizer authorizer;
  @Nullable BudgetProvider budgets;
  @Nullable ToolCostEstimator estimator;
  @Nullable CostModel costModel;
  @Nullable EffectJournal journal;
  @Nullable EffectReconciler reconciler;
  UnknownEffectPolicy unknownEffectPolicy = UnknownEffectPolicy.RETRY;
  @Nullable ContentGuardrail guardrails;
  final HookEngine hooks = new HookEngine();
  final List<AuditSink> auditSinks = new ArrayList<>();
  final List<ToolCallObserver> observers = new ArrayList<>();
  Clock clock = Clock.systemUTC();
  boolean tamperEvident;
  @Nullable Signer chainSigner;
  long chainLastSeq;
  String chainLastHash = HashChainedAuditSink.GENESIS;
  boolean receipts;
  @Nullable ReceiptSigner receiptSigner;
  @Nullable ReceiptVerifier receiptVerifier;
  @Nullable ReceiptStore receiptStore;
  final List<ApprovalNotifier.Builder> notifierBuilders = new ArrayList<>();
  boolean notifiersRegistered;
}
