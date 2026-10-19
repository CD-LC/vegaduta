package io.vegaduta.agent.core;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalDecisionAuthorizer;
import io.vegaduta.agent.api.approval.ApprovalGate;
import io.vegaduta.agent.api.approval.ApprovalStore;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.budget.BudgetCharge;
import io.vegaduta.agent.api.budget.BudgetProvider;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.guardrail.ContentGuardrail;
import io.vegaduta.agent.api.journal.EffectJournal;
import io.vegaduta.agent.api.observe.ToolCallObserver;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.risk.ToolClassificationSource;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.core.approval.ApprovalNotifier;
import io.vegaduta.agent.core.approval.DefaultApprovalGate;
import io.vegaduta.agent.core.approval.ReceiptAuditSink;
import io.vegaduta.agent.core.approval.ReceiptSigner;
import io.vegaduta.agent.core.approval.ReceiptStore;
import io.vegaduta.agent.core.approval.ReceiptVerifier;
import io.vegaduta.agent.core.autonomy.AutonomyGate;
import io.vegaduta.agent.core.autonomy.AutonomyRegistry;
import io.vegaduta.agent.core.classification.ClassificationResolver;
import io.vegaduta.agent.core.classification.LethalTrifectaDetector;
import io.vegaduta.agent.core.cost.CostAwareBudgetProvider;
import io.vegaduta.agent.core.cost.CostLedger;
import io.vegaduta.agent.core.cost.CostModel;
import io.vegaduta.agent.core.cost.ToolCostEstimator;
import io.vegaduta.agent.core.evidence.HashChainedAuditSink;
import io.vegaduta.agent.core.evidence.Signer;
import io.vegaduta.agent.core.gate.CompositeToolDispatchGate;
import io.vegaduta.agent.core.gate.PolicyToolDispatchGate;
import io.vegaduta.agent.core.halt.HaltGate;
import io.vegaduta.agent.core.halt.HaltSwitch;
import io.vegaduta.agent.core.hook.HookEngine;
import io.vegaduta.agent.core.hook.HookRegistration;
import io.vegaduta.agent.core.journal.EffectReconciler;
import io.vegaduta.agent.core.journal.ReconcilingEffectJournal;
import io.vegaduta.agent.core.journal.UnknownEffectPolicy;
import io.vegaduta.agent.core.mandate.MandateGate;
import io.vegaduta.agent.core.memory.MemoryToolHook;
import io.vegaduta.agent.core.provenance.ManifestPinStore;
import io.vegaduta.agent.core.provenance.ProvenanceGate;
import io.vegaduta.agent.core.provenance.ProvenanceMode;
import io.vegaduta.agent.core.provenance.ToolManifest;
import io.vegaduta.agent.core.replay.ShadowGate;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The entry point. Build one per application (or per tenant), then {@link #govern(Tool)} every tool
 * before offering it to a model.
 *
 * <pre>{@code
 * Governance governance = Governance.builder()
 *     .policy(GovernancePolicy.builder().mode(GovernanceMode.STRICT).build())
 *     .classification(ToolClassification.builder("refund").risk(RiskLabel.HIGH).build())
 *     .build();
 * Tool governed = governance.govern(myRefundTool);
 * }</pre>
 *
 * <p>Every collaborator has an in-memory default, so the no-argument build works for a demo and the
 * host swaps in durable stores for production.
 *
 * <p><b>Gate composition.</b> The gate consulted by {@link GovernedTool} is a {@link
 * CompositeToolDispatchGate} over, in this order: {@link HaltGate} (when a {@link
 * Builder#haltSwitch(HaltSwitch)} is set), {@link MandateGate} ({@link Builder#mandates(boolean)}),
 * {@link AutonomyGate} ({@link Builder#autonomy(AutonomyRegistry)}), {@link ProvenanceGate} ({@link
 * Builder#provenance}), and finally the policy gate: {@link PolicyToolDispatchGate} or the gate
 * given to {@link Builder#gate(ToolDispatchGate)}, optionally wrapped in a {@link ShadowGate} by
 * {@link Builder#shadow(GovernancePolicy)}. The first DENY wins, then any REQUIRE_APPROVAL, else
 * ALLOW, so a host-supplied gate can never re-admit a halted or demoted agent.
 */
public final class Governance {

  private final GovernancePolicy policy;
  private final ClassificationResolver resolver;
  private final ToolDispatchGate gate;
  private final DefaultApprovalGate approvals;
  private final BudgetProvider budgets;
  private final EffectJournal journal;
  private final ContentGuardrail guardrails;
  private final HookEngine hooks;
  private final AuditSink audit;
  private final ToolCallObserver observer;
  private final LethalTrifectaDetector trifecta = new LethalTrifectaDetector();
  private final ToolCostEstimator estimator;
  private final Clock clock;
  private final @Nullable HaltSwitch haltSwitch;
  private final @Nullable AutonomyRegistry autonomy;
  private final @Nullable CostLedger costLedger;
  private final @Nullable ReceiptAuditSink receipts;
  private final @Nullable ProvenanceGate provenanceGate;

  private Governance(GovernanceAssembly a) {
    this.policy = a.policy;
    this.clock = a.clock;
    this.resolver = a.resolver;
    this.hooks = a.hooks;
    this.audit = a.audit;
    this.receipts = a.receipts;
    this.haltSwitch = a.haltSwitch;
    this.autonomy = a.autonomy;
    this.provenanceGate = a.provenanceGate;
    this.gate = a.gate;
    this.approvals = a.approvals;
    this.budgets = a.budgets;
    this.costLedger = a.costLedger;
    this.estimator = a.estimator;
    this.journal = a.journal;
    this.guardrails = a.guardrails;
    this.observer = a.observer;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** A governance with every default: warn mode, in-memory stores, logging audit. */
  public static Governance defaults() {
    return builder().build();
  }

  /** Wraps one tool and registers it as the executor for its approvals. */
  public GovernedTool govern(Tool tool) {
    Objects.requireNonNull(tool, "tool");
    if (tool instanceof GovernedTool already) {
      return already;
    }
    GovernedTool governed = new GovernedTool(tool, this);
    approvals.executors().register(governed);
    return governed;
  }

  /**
   * Wraps a set of tools that will be offered together to one agent, and applies the
   * lethal-trifecta rule to the set: in {@link GovernanceMode#STRICT} every exfiltration sink in a
   * set that also reads private data and consumes untrusted input is forced to require approval; in
   * {@link GovernanceMode#WARN} a warning is audited.
   */
  public List<GovernedTool> governAll(Collection<? extends Tool> tools) {
    return governAll(tools, RunContext.anonymous());
  }

  public List<GovernedTool> governAll(Collection<? extends Tool> tools, RunContext context) {
    List<GovernedTool> governed = tools.stream().map(this::govern).collect(Collectors.toList());
    new TrifectaEnforcer(trifecta, policy, resolver, audit).apply(governed, context);
    return List.copyOf(governed);
  }

  public GovernancePolicy policy() {
    return policy;
  }

  public ClassificationResolver resolver() {
    return resolver;
  }

  public ToolDispatchGate gate() {
    return gate;
  }

  public ApprovalGate approvals() {
    return approvals;
  }

  public BudgetProvider budgets() {
    return budgets;
  }

  public EffectJournal journal() {
    return journal;
  }

  public ContentGuardrail guardrails() {
    return guardrails;
  }

  public HookEngine hooks() {
    return hooks;
  }

  public AuditSink audit() {
    return audit;
  }

  public ToolCallObserver observer() {
    return observer;
  }

  public LethalTrifectaDetector trifecta() {
    return trifecta;
  }

  public Clock clock() {
    return clock;
  }

  /** The kill switch given to {@link Builder#haltSwitch(HaltSwitch)}, else {@code null}. */
  @Experimental
  public @Nullable HaltSwitch haltSwitch() {
    return haltSwitch;
  }

  /** The registry given to {@link Builder#autonomy(AutonomyRegistry)}, else {@code null}. */
  @Experimental
  public @Nullable AutonomyRegistry autonomy() {
    return autonomy;
  }

  /** The cost ledger when a {@link Builder#costModel(CostModel)} was set, else {@code null}. */
  @Experimental
  public @Nullable CostLedger costLedger() {
    return costLedger;
  }

  /** The receipt sink when {@link Builder#receipts()} was turned on, else {@code null}. */
  @Experimental
  public @Nullable ReceiptAuditSink receipts() {
    return receipts;
  }

  /**
   * The provenance gate when {@link Builder#provenance} was set, so a host can {@link
   * ProvenanceGate#refresh()} or {@link ProvenanceGate#repin()} after a review; else empty.
   */
  @Experimental
  public java.util.Optional<ProvenanceGate> provenanceGate() {
    return java.util.Optional.ofNullable(provenanceGate);
  }

  /**
   * The reconciling journal when {@link Builder#reconciler} was set. Hosts with a durable journal
   * seed interrupted calls through it at start-up (see {@link ReconcilingEffectJournal}).
   */
  @Experimental
  public java.util.Optional<ReconcilingEffectJournal> reconcilingJournal() {
    return journal instanceof ReconcilingEffectJournal r
        ? java.util.Optional.of(r)
        : java.util.Optional.empty();
  }

  BudgetCharge estimateFor(ToolClassification classification) {
    return estimator.estimateFor(classification);
  }

  public static final class Builder {
    private final GovernanceSpec spec = new GovernanceSpec();

    private Builder() {}

    public Builder policy(GovernancePolicy value) {
      spec.policy = Objects.requireNonNull(value, "policy");
      return this;
    }

    public Builder classification(ToolClassification value) {
      spec.registered.register(value);
      return this;
    }

    public Builder classifications(Collection<ToolClassification> values) {
      values.forEach(spec.registered::register);
      return this;
    }

    public Builder classificationSource(ToolClassificationSource value) {
      spec.sources.add(Objects.requireNonNull(value, "source"));
      return this;
    }

    /**
     * Replaces the policy gate. The halt, mandate, autonomy and provenance gates configured on this
     * builder still run in front of it.
     */
    public Builder gate(ToolDispatchGate value) {
      spec.gate = value;
      return this;
    }

    /**
     * Evaluates {@code candidate} alongside the live policy on every call without enforcing it, and
     * audits every divergence as {@code shadow.decision} for {@link
     * io.vegaduta.agent.core.replay.ShadowReport}.
     *
     * <p>Shadow mode compares <em>policies</em>: the candidate gate is a {@link
     * PolicyToolDispatchGate} that shares the live resolver's classification sources and run-time
     * overrides, and copies the allow and deny lists of a host {@link #gate(ToolDispatchGate)} when
     * that gate is a {@code PolicyToolDispatchGate}. Any other logic in a custom host gate is not
     * mirrored. The candidate runs synchronously on every call, so it adds one more classification
     * lookup per call.
     */
    @Experimental
    public Builder shadow(GovernancePolicy candidate) {
      spec.shadowPolicy = Objects.requireNonNull(candidate, "candidate");
      return this;
    }

    /**
     * Installs a kill switch: while a matching halt is engaged on {@code value}, every tool call is
     * denied before any other gate runs.
     */
    @Experimental
    public Builder haltSwitch(HaltSwitch value) {
      spec.haltSwitch = Objects.requireNonNull(value, "haltSwitch");
      return this;
    }

    /**
     * Checks the mandate attached to the {@link RunContext} ({@link
     * io.vegaduta.agent.api.mandate.Mandates#attach}) against the tool and its risk. With {@code
     * require} true, a run without a mandate is denied.
     */
    @Experimental
    public Builder mandates(boolean require) {
      spec.mandates = true;
      spec.requireMandate = require;
      return this;
    }

    /**
     * Enforces tiered autonomy: an {@link AutonomyGate} runs in front of the policy gate. Demote an
     * agent on the registry at run time and the next tool call obeys.
     */
    @Experimental
    public Builder autonomy(AutonomyRegistry value) {
      spec.autonomy = Objects.requireNonNull(value, "autonomy");
      return this;
    }

    /**
     * Pins the tool manifest supplied by {@code manifest} under {@code key} in {@code store} and
     * denies (ENFORCE) or audits (WARN) calls to tools that were added or changed since the pin
     * (OWASP ASI04). Governance wraps one tool at a time, so the host supplies the full manifest,
     * for example {@code () -> ToolManifest.of(registry.descriptors())}.
     *
     * <p>Fails closed: in {@link ProvenanceMode#ENFORCE} nothing is pinned on first use, so every
     * call is denied ({@code unpinned_manifest}) until a reviewed lockfile exists or {@link
     * ProvenanceGate#repin()} is called on {@link Governance#provenanceGate()}. In {@link
     * ProvenanceMode#WARN} the first manifest seen becomes the pin (trust on first use). Use {@link
     * #provenance(ManifestPinStore, String, ProvenanceMode, Supplier, Consumer)} to change either,
     * or to set a custom {@code ToolDriftDetector}.
     */
    @Experimental
    public Builder provenance(
        ManifestPinStore store, String key, ProvenanceMode mode, Supplier<ToolManifest> manifest) {
      return provenance(store, key, mode, manifest, null);
    }

    /**
     * Like {@link #provenance(ManifestPinStore, String, ProvenanceMode, Supplier)} with a hook on
     * the {@link ProvenanceGate.Builder} (for {@code pinOnFirstUse}, a detector, ...). The audit
     * sink is always this governance's.
     */
    @Experimental
    public Builder provenance(
        ManifestPinStore store,
        String key,
        ProvenanceMode mode,
        Supplier<ToolManifest> manifest,
        @Nullable Consumer<ProvenanceGate.Builder> customize) {
      spec.provenanceStore = Objects.requireNonNull(store, "store");
      spec.provenanceKey = Objects.requireNonNull(key, "key");
      spec.provenanceMode = Objects.requireNonNull(mode, "mode");
      spec.provenanceManifest = Objects.requireNonNull(manifest, "manifest");
      spec.provenancePinOnFirstUse = mode == ProvenanceMode.WARN;
      spec.provenanceCustomizer = customize;
      return this;
    }

    public Builder approvalStore(ApprovalStore value) {
      spec.approvalStore = Objects.requireNonNull(value, "approvalStore");
      return this;
    }

    public Builder approvalAuthorizer(ApprovalDecisionAuthorizer value) {
      spec.authorizer = value;
      return this;
    }

    public Builder budgets(BudgetProvider value) {
      spec.budgets = value;
      return this;
    }

    /** Reserves the given estimate per tool instead of the policy's constant. */
    @Experimental
    public Builder toolCostEstimator(ToolCostEstimator value) {
      spec.estimator = Objects.requireNonNull(value, "estimator");
      return this;
    }

    /**
     * Turns on cost attribution: records every tool call and approval decision in a {@link
     * CostLedger} priced by {@code model}, and reserves the model's per-tool estimate from the run
     * budget. When no {@link #budgets(BudgetProvider)} was set, the in-memory budget is wrapped in
     * a {@link CostAwareBudgetProvider}; otherwise the given provider is kept and only the estimate
     * changes. Read the ledger back with {@link Governance#costLedger()}.
     */
    @Experimental
    public Builder costModel(CostModel model) {
      spec.costModel = Objects.requireNonNull(model, "model");
      return this;
    }

    public Builder effectJournal(EffectJournal value) {
      spec.journal = value;
      return this;
    }

    /**
     * Wraps the effect journal in a {@link ReconcilingEffectJournal}: when a side-effecting call
     * was interrupted, {@code value} is asked whether the effect happened before the call is
     * replayed or retried. With a reconciler every side-effecting tool takes part in the journal,
     * not only replay-safe ones (see {@link GovernedTool}).
     *
     * <p>Interrupted state is in memory. Calls interrupted by a crash of an earlier process are
     * only seen when the {@link #effectJournal(EffectJournal) journal} implements {@link
     * io.vegaduta.agent.core.journal.InterruptedCallSource} (the JDBC journal does) or when the
     * host seeds {@link Governance#reconcilingJournal()}'s tracker at start-up.
     */
    @Experimental
    public Builder reconciler(EffectReconciler value) {
      spec.reconciler = Objects.requireNonNull(value, "reconciler");
      return this;
    }

    /** What to do when the reconciler cannot tell: retry (default) or hold and fail closed. */
    @Experimental
    public Builder unknownEffectPolicy(UnknownEffectPolicy value) {
      spec.unknownEffectPolicy = Objects.requireNonNull(value, "unknownEffectPolicy");
      return this;
    }

    public Builder guardrails(ContentGuardrail value) {
      spec.guardrails = value;
      return this;
    }

    public Builder hook(HookRegistration value) {
      spec.hooks.register(value);
      return this;
    }

    /**
     * Treats {@code memoryToolNames} as long-term memory writers: their arguments are screened for
     * injection patterns before the call (fail closed).
     */
    @Experimental
    public Builder memory(Set<String> memoryToolNames) {
      spec.hooks.register(new MemoryToolHook(memoryToolNames).registration());
      return this;
    }

    public Builder auditSink(AuditSink value) {
      spec.auditSinks.add(Objects.requireNonNull(value, "auditSink"));
      return this;
    }

    /**
     * Hash-chains every audit event (and signs the chain head when {@code signer} is given) so an
     * auditor can prove the trail was not edited: {@link HashChainedAuditSink#verify}.
     *
     * <p>The chain state is in memory and starts at seq 1: a process that restarts must call {@link
     * #tamperEvidentAudit(Signer, long, String)} with the last stored {@code chain.seq} and {@code
     * chain.hash}, or a verifier over the whole log reports a break at the restart. Each writer
     * owns its chain; two replicas must not share one sink without tagging per writer. Hashing and
     * signing are serialised under one lock; the sinks are called outside it.
     */
    @Experimental
    public Builder tamperEvidentAudit(@Nullable Signer signer) {
      spec.tamperEvident = true;
      spec.chainSigner = signer;
      return this;
    }

    /**
     * Like {@link #tamperEvidentAudit(Signer)}, resuming the chain after {@code lastSeq} and {@code
     * lastHash} as read back from the durable sink's newest event.
     */
    @Experimental
    public Builder tamperEvidentAudit(@Nullable Signer signer, long lastSeq, String lastHash) {
      tamperEvidentAudit(signer);
      // Validate eagerly with the same rules the sink applies.
      new HashChainedAuditSink(AuditSink.discard(), null, lastSeq, lastHash);
      spec.chainLastSeq = lastSeq;
      spec.chainLastHash = Objects.requireNonNull(lastHash, "lastHash");
      return this;
    }

    public Builder observer(ToolCallObserver value) {
      spec.observers.add(Objects.requireNonNull(value, "observer"));
      return this;
    }

    public Builder clock(Clock value) {
      spec.clock = Objects.requireNonNull(value, "clock");
      return this;
    }

    /**
     * Posts approval requests and decisions to a chat channel. The notifier is built with this
     * builder's approval store and registered at both approval hook points, fail-open.
     */
    @Experimental
    public Builder approvalNotifier(ApprovalNotifier.Builder value) {
      spec.notifierBuilders.add(Objects.requireNonNull(value, "notifier"));
      return this;
    }

    /** Registers an already built notifier at both approval hook points, fail-open. */
    @Experimental
    public Builder approvalNotifier(ApprovalNotifier value) {
      spec.hooks.register(
          Objects.requireNonNull(value, "notifier").registration("approval-notifier"));
      return this;
    }

    /** Stores an unsigned (digest-only) receipt for every approval decision. */
    @Experimental
    public Builder receipts() {
      spec.receipts = true;
      return this;
    }

    /**
     * Stores a signed receipt for every approval decision. HMAC signers verify themselves; an
     * asymmetric signer (Ed25519) must come with its verifier through {@link
     * #receipts(ReceiptSigner, ReceiptVerifier)}.
     *
     * @throws IllegalArgumentException when {@code signer} is not also a {@link ReceiptVerifier}
     */
    @Experimental
    public Builder receipts(ReceiptSigner signer) {
      Objects.requireNonNull(signer, "signer");
      if (!(signer instanceof ReceiptVerifier) && spec.receiptVerifier == null) {
        throw new IllegalArgumentException(
            "signer '" + signer.keyId() + "' cannot verify; use receipts(signer, verifier)");
      }
      spec.receipts = true;
      spec.receiptSigner = signer;
      return this;
    }

    /**
     * Signed receipts with an explicit verifier (Ed25519: private key signs, public key checks).
     */
    @Experimental
    public Builder receipts(ReceiptSigner signer, ReceiptVerifier verifier) {
      spec.receiptVerifier = Objects.requireNonNull(verifier, "verifier");
      receipts(signer);
      return this;
    }

    /** Where receipts go; in-memory when not set. */
    @Experimental
    public Builder receiptStore(ReceiptStore value) {
      spec.receiptStore = Objects.requireNonNull(value, "receiptStore");
      return this;
    }

    /**
     * Builds the governance. Derived collaborators (cost ledger, cost and receipt sinks, the
     * cost-aware budget) are computed per build and not written back, so the builder can be reused;
     * the hook engine is shared by every build of this builder.
     */
    public Governance build() {
      if (!spec.notifiersRegistered) {
        for (ApprovalNotifier.Builder notifier : spec.notifierBuilders) {
          spec.hooks.register(
              notifier.approvalStore(spec.approvalStore).build().registration("approval-notifier"));
        }
        spec.notifiersRegistered = true;
      }
      return new Governance(new GovernanceAssembly(spec));
    }
  }
}
