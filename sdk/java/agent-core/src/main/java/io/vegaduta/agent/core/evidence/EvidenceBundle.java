package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A snapshot of audit events for a period, plus what the exporters need to interpret them: the tool
 * classifications in force and an optional signature verifier. Build one from {@code
 * InMemoryAuditSink.events()} or from events read back from a durable sink, then hand it to {@link
 * EuAiActEvidenceExporter}, {@link OwaspAsiCoverageReport} or {@link UnderwritingReport}.
 *
 * <p>Event vocabulary (as emitted by {@code GovernedTool} and {@code DefaultApprovalGate}): action
 * type {@code tool.call} with attributes {@code risk}, {@code callId} and (after approval) {@code
 * approvalId}; {@code approval.requested} and {@code approval.decided} with {@code actionType} and
 * {@code status}; and any {@code halt.*} action type for an operator stop. Events from other
 * sources are kept in the log and counted where their outcome and risks apply.
 */
@Experimental
public final class EvidenceBundle {

  public static final String TOOL_CALL = "tool.call";
  public static final String APPROVAL_REQUESTED = "approval.requested";
  public static final String APPROVAL_DECIDED = "approval.decided";
  public static final String HALT_PREFIX = "halt.";
  public static final String RISK_ATTRIBUTE = "risk";
  public static final String APPROVAL_ID_ATTRIBUTE = "approvalId";
  public static final String REDACTION_DETAIL_PREFIX = "output redacted";

  private final List<AuditEvent> events;
  private final @Nullable Instant from;
  private final @Nullable Instant to;
  private final Instant generatedAt;
  private final Map<String, ToolClassification> classifications;
  private final ChainVerification chainVerification;
  private final boolean chained;
  private final int unchainedEvents;

  private EvidenceBundle(Builder b) {
    // The chain is verified over the FULL input in chain.seq order, before the period filter:
    // createdAt is taken on the calling thread while seq is assigned under the chain lock, so a
    // time window can drop a middle seq and would otherwise report an intact log as broken.
    List<AuditEvent> chainedAll = new ArrayList<>();
    int unchained = 0;
    for (AuditEvent e : b.events) {
      if (e.attributes().containsKey(HashChainedAuditSink.HASH)) {
        chainedAll.add(e);
      } else {
        unchained++;
      }
    }
    chainedAll.sort(Comparator.comparingLong(EvidenceBundle::seqOf));
    this.chained = !chainedAll.isEmpty();
    this.unchainedEvents = unchained;
    this.chainVerification =
        chained
            ? HashChainedAuditSink.verify(chainedAll, Optional.ofNullable(b.verifier))
            : new ChainVerification(false, -1, 0, "events are not hash-chained");

    List<AuditEvent> ordered = new ArrayList<>(b.events);
    if (chained && unchained == 0) {
      ordered = chainedAll;
    }
    List<AuditEvent> selected = new ArrayList<>();
    for (AuditEvent e : ordered) {
      boolean afterFrom = b.from == null || !e.createdAt().isBefore(b.from);
      boolean beforeTo = b.to == null || e.createdAt().isBefore(b.to);
      if (afterFrom && beforeTo) {
        selected.add(e);
      }
    }
    this.events = Collections.unmodifiableList(selected);
    this.from = b.from;
    this.to = b.to;
    this.generatedAt = b.generatedAt == null ? Instant.now() : b.generatedAt;
    this.classifications = Collections.unmodifiableMap(new TreeMap<>(b.classifications));
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Every event, no period, no classifications. */
  public static EvidenceBundle of(List<AuditEvent> events) {
    return builder().events(events).build();
  }

  public List<AuditEvent> events() {
    return events;
  }

  public Optional<Instant> from() {
    return Optional.ofNullable(from);
  }

  public Optional<Instant> to() {
    return Optional.ofNullable(to);
  }

  public Instant generatedAt() {
    return generatedAt;
  }

  /** Classifications registered on the bundle, by tool name. */
  public Map<String, ToolClassification> classifications() {
    return classifications;
  }

  /** True when the events carry {@code chain.hash} attributes. */
  public boolean chained() {
    return chained;
  }

  /**
   * The chain check over every chained event handed to the builder (not only the period), in {@code
   * chain.seq} order; not ok when no event is chained.
   */
  public ChainVerification chainVerification() {
    return chainVerification;
  }

  /**
   * Events handed to the builder that carry no chain attributes (written before the chained sink
   * was installed, or by another writer). They are kept in the log but are not covered by {@link
   * #chainVerification()}; a non-zero count next to an intact chain is worth a sentence in a
   * report.
   */
  public int unchainedEvents() {
    return unchainedEvents;
  }

  public String periodText() {
    String start = from == null ? "(start of log)" : from.toString();
    String end = to == null ? "(end of log)" : to.toString();
    return start + " to " + end;
  }

  // ---- selections -------------------------------------------------------------------------

  public List<AuditEvent> withAction(String actionType) {
    return events.stream().filter(e -> e.actionType().equals(actionType)).toList();
  }

  public List<AuditEvent> withActionPrefix(String prefix) {
    return events.stream().filter(e -> e.actionType().startsWith(prefix)).toList();
  }

  public List<AuditEvent> withOutcome(String actionType, AuditOutcome outcome) {
    return events.stream()
        .filter(e -> e.actionType().equals(actionType) && e.outcome() == outcome)
        .toList();
  }

  public List<AuditEvent> toolCalls() {
    return withAction(TOOL_CALL);
  }

  public List<AuditEvent> approvalsRequested() {
    return withAction(APPROVAL_REQUESTED);
  }

  public List<AuditEvent> approvalsDecided() {
    return withAction(APPROVAL_DECIDED);
  }

  public List<AuditEvent> halts() {
    return withActionPrefix(HALT_PREFIX);
  }

  public List<AuditEvent> withRisk(AsiRisk risk) {
    return events.stream().filter(e -> e.risks().contains(risk)).toList();
  }

  /** Tool call successes that ran after a human approval (carry an {@code approvalId}). */
  public List<AuditEvent> humanApprovedExecutions() {
    return toolCalls().stream()
        .filter(e -> e.outcome() == AuditOutcome.SUCCESS)
        .filter(e -> e.attributes().containsKey(APPROVAL_ID_ATTRIBUTE))
        .toList();
  }

  /** Tool call successes that ran with no human in the loop. */
  public List<AuditEvent> autonomousExecutions() {
    return toolCalls().stream()
        .filter(e -> e.outcome() == AuditOutcome.SUCCESS)
        .filter(e -> !e.attributes().containsKey(APPROVAL_ID_ATTRIBUTE))
        .toList();
  }

  /** Output-guardrail redactions: {@code tool.call} warnings whose detail says so. */
  public List<AuditEvent> redactions() {
    return toolCalls().stream()
        .filter(e -> e.outcome() == AuditOutcome.WARNING)
        .filter(e -> e.detail() != null && e.detail().startsWith(REDACTION_DETAIL_PREFIX))
        .toList();
  }

  /** Tool names seen in tool calls or registered as classifications, sorted. */
  public List<String> toolNames() {
    TreeSet<String> names = new TreeSet<>(classifications.keySet());
    toolCalls().forEach(e -> names.add(e.targetId()));
    return List.copyOf(names);
  }

  /**
   * The risk label of a tool: from its classification when registered, else from the most recent
   * {@code risk} attribute on its calls, else {@code "UNCLASSIFIED"}.
   */
  public String riskLabelOf(String toolName) {
    ToolClassification c = classifications.get(toolName);
    if (c != null) {
      return c.risk().name();
    }
    String label = null;
    for (AuditEvent e : toolCalls()) {
      if (e.targetId().equals(toolName) && e.attributes().containsKey(RISK_ATTRIBUTE)) {
        label = e.attributes().get(RISK_ATTRIBUTE);
      }
    }
    return label == null ? "UNCLASSIFIED" : label;
  }

  /** ASI risks tagged on a tool's classification or any of its calls. */
  public List<AsiRisk> asiRisksOf(String toolName) {
    TreeSet<AsiRisk> risks = new TreeSet<>();
    ToolClassification c = classifications.get(toolName);
    if (c != null) {
      risks.addAll(c.risks());
    }
    toolCalls().stream()
        .filter(e -> e.targetId().equals(toolName))
        .forEach(e -> risks.addAll(e.risks()));
    return List.copyOf(risks);
  }

  /**
   * Whether a tool call event concerns a side-effecting tool. Uses the registered classification
   * when there is one; otherwise a call is treated as side-effecting when it is tagged ASI02 (the
   * pipeline tags untagged side-effecting tools that way) or its risk label is HIGH or CRITICAL.
   */
  public boolean sideEffecting(AuditEvent toolCall) {
    ToolClassification c = classifications.get(toolCall.targetId());
    if (c != null) {
      return c.sideEffecting();
    }
    if (toolCall.risks().contains(AsiRisk.ASI02)) {
      return true;
    }
    String label = toolCall.attributes().get(RISK_ATTRIBUTE);
    if (label == null) {
      return false;
    }
    try {
      return RiskLabel.valueOf(label).atLeast(RiskLabel.HIGH);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** {@code tenant/agent} grouping key, with {@code -} for an absent part. */
  public static String scopeOf(AuditEvent event) {
    String tenant = event.context().tenantId();
    String agent = event.context().agentId();
    return (tenant == null ? "-" : tenant) + "/" + (agent == null ? "-" : agent);
  }

  /** Events grouped by {@link #scopeOf}, in first-seen order. */
  public Map<String, List<AuditEvent>> byScope() {
    return events.stream()
        .collect(
            Collectors.groupingBy(
                EvidenceBundle::scopeOf, LinkedHashMap::new, Collectors.toList()));
  }

  /** Counts by outcome name, in enum order. */
  public static Map<String, Long> countByOutcome(Collection<AuditEvent> events) {
    Map<String, Long> counts = new TreeMap<>(Comparator.comparing(AuditOutcome::valueOf));
    for (AuditEvent e : events) {
      counts.merge(e.outcome().name(), 1L, Long::sum);
    }
    return counts;
  }

  static long seqOf(AuditEvent event) {
    try {
      return Long.parseLong(event.attributes().getOrDefault(HashChainedAuditSink.SEQ, "0"));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public static final class Builder {
    private final List<AuditEvent> events = new ArrayList<>();
    private final Map<String, ToolClassification> classifications = new LinkedHashMap<>();
    private @Nullable Instant from;
    private @Nullable Instant to;
    private @Nullable Instant generatedAt;
    private @Nullable Verifier verifier;

    private Builder() {}

    public Builder events(Collection<AuditEvent> values) {
      events.addAll(Objects.requireNonNull(values, "events"));
      return this;
    }

    public Builder event(AuditEvent value) {
      events.add(Objects.requireNonNull(value, "event"));
      return this;
    }

    public Builder classification(ToolClassification value) {
      classifications.put(value.toolName(), value);
      return this;
    }

    public Builder classifications(Collection<ToolClassification> values) {
      values.forEach(this::classification);
      return this;
    }

    /** Inclusive start of the reporting period. */
    public Builder from(@Nullable Instant value) {
      this.from = value;
      return this;
    }

    /** Exclusive end of the reporting period. */
    public Builder to(@Nullable Instant value) {
      this.to = value;
      return this;
    }

    /** When the bundle was produced; defaults to now. Set it in tests for stable output. */
    public Builder generatedAt(Instant value) {
      this.generatedAt = value;
      return this;
    }

    /** Verifies {@code chain.sig} on every event; without one only the hash links are checked. */
    public Builder verifier(@Nullable Verifier value) {
      this.verifier = value;
      return this;
    }

    public EvidenceBundle build() {
      return new EvidenceBundle(this);
    }
  }
}
