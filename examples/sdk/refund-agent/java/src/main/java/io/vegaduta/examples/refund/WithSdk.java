package io.vegaduta.examples.refund;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.annotation.Governed;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.autonomy.AutonomyLevel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.api.tool.ToolStatus;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.GovernedTool;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import io.vegaduta.agent.core.autonomy.AutonomyRegistry;
import io.vegaduta.agent.core.classification.GovernedAnnotations;
import io.vegaduta.agent.core.evidence.ChainVerification;
import io.vegaduta.agent.core.evidence.EvidenceBundle;
import io.vegaduta.agent.core.evidence.HashChainedAuditSink;
import io.vegaduta.agent.core.evidence.HmacSha256Signer;
import io.vegaduta.agent.core.evidence.OwaspAsiCoverageReport;
import io.vegaduta.agent.core.hook.JsonLite;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The same support agent wrapped in the VegaDūta Agent SDK.
 *
 * <p>Same shop backend, same scripted model turns as {@code WithoutSdk}. The
 * only additions are the {@link Governed} classification on each
 * {@link SupportTools} method and the {@link Governance} pipeline the calls
 * pass through: approval, effect journal, budget, guardrails and audit.
 * Requires {@code mvn -f sdk/java/pom.xml -DskipTests install} once.
 */
public final class WithSdk {

  private static final String REVIEWER = "maya@acme-shop (support lead)";
  private static final long AUTO_APPROVE_LIMIT_CENTS = 10_000; // $100: a lead's limit

  private WithSdk() {}

  /** Adapts one annotated {@code Map -> String} method into the SDK's {@link Tool} surface. */
  private record MethodTool(
      String name, String description, Object target, Method method) implements Tool {
    @Override
    public ToolDescriptor descriptor() {
      return ToolDescriptor.of(name, description);
    }

    @Override
    public String call(String argumentsJson, RunContext context) {
      try {
        return (String) method.invoke(target, JsonLite.readFlatObject(argumentsJson));
      } catch (ReflectiveOperationException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static void main(String[] args) {
    Shop shop = new Shop();
    SupportTools target = new SupportTools(shop);
    InMemoryAuditSink audit = new InMemoryAuditSink();
    // Tamper-evident trail: every event is hash-chained to the previous one and signed, so a
    // reviewer can prove nothing was edited or dropped. The key is demo-only.
    HmacSha256Signer signer =
        new HmacSha256Signer("refund-agent-demo-key".getBytes(StandardCharsets.UTF_8));
    HashChainedAuditSink chained = new HashChainedAuditSink(audit, signer);

    // Tiered autonomy: the support bot is new, so the lead demotes it from the default L4 to
    // L3 (act within bounds; high-risk effects need approval). The demotion is itself audited.
    AutonomyRegistry autonomy = new AutonomyRegistry(AutonomyLevel.L4_ACT_AUTONOMOUS, chained);
    autonomy.demote(
        "support-bot",
        AutonomyLevel.L3_ACT_BOUNDED,
        REVIEWER,
        "new agent: no unreviewed high-risk effects until it has a track record");

    Governance.Builder builder =
        Governance.builder()
            .policy(
                GovernancePolicy.builder()
                    .mode(GovernanceMode.STRICT)
                    .maxToolCallsPerRun(8)
                    .build())
            .autonomy(autonomy)
            .auditSink(chained);
    List<ToolClassification> classifications = new ArrayList<>();

    // Scan the @Governed methods — like Python's governance.scan(SupportTools).
    Map<String, String> descriptions =
        Map.of(
            "lookup_order", "Look up an order by id",
            "read_email", "Read an inbound customer email",
            "issue_refund", "Refund money to an order's payment method",
            "apply_store_credit", "Apply goodwill store credit");
    Map<String, Tool> raw = new LinkedHashMap<>();
    for (Method m : SupportTools.class.getDeclaredMethods()) {
      Governed g = GovernedAnnotations.find(m);
      if (g == null) {
        continue;
      }
      String name =
          m.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
      ToolClassification classification = GovernedAnnotations.toClassification(g, name);
      classifications.add(classification);
      builder.classification(classification);
      raw.put(name, new MethodTool(name, descriptions.getOrDefault(name, name), target, m));
    }

    Governance governance = builder.build();
    Map<String, GovernedTool> tools = new LinkedHashMap<>();
    raw.forEach((name, tool) -> tools.put(name, governance.govern(tool)));

    RunContext ctx =
        RunContext.builder()
            .tenantId("acme-shop")
            .principal("customer-7721@example.com")
            .agentId("support-bot")
            .runId("ticket-4821")
            .build();

    List<UUID> approvalIds = new ArrayList<>();
    Scenario.banner("SUPPORT AGENT — WITH VEGADŪTA SDK (governed dispatch)");
    for (Scenario.Step step : Scenario.steps()) {
      if (!step.kind().equals("call")) {
        System.out.println(step.text());
        continue;
      }
      String argsJson = JsonLite.writeObject(step.args());
      System.out.printf("  -> %s %s%n", step.tool(), argsJson);
      ToolResult result =
          tools.get(step.tool()).invoke(ToolCall.of(step.tool(), argsJson), ctx);
      System.out.printf("  <- [%s] %s%n", result.status(), result.output());
      if (result.status() == ToolStatus.PENDING_APPROVAL) {
        approvalIds.add(UUID.fromString(result.approvalId()));
        reviewPending(governance);
      } else if (result.status() == ToolStatus.BUDGET_EXCEEDED) {
        System.out.println("  !! run stopped: out of tool-call budget");
        break;
      }
    }

    Scenario.banner("MONEY MOVED");
    System.out.print(shop.moneyMoved());

    Scenario.banner("AUDIT TRAIL (one key=value line per governed event)");
    for (AuditEvent event : audit.events()) {
      System.out.println("  " + line(event));
    }

    Scenario.banner("APPROVAL DECISIONS");
    for (UUID id : approvalIds) {
      governance
          .approvals()
          .find(id)
          .ifPresent(
              r ->
                  System.out.printf(
                      "  %-22s %-9s decided by %s%n", r.actionType(), r.status(), r.decidedBy()));
    }

    // The evidence a review asks for: is the trail intact, and which OWASP agentic risks did the
    // controls actually exercise in this run?
    Scenario.banner("EVIDENCE");
    EvidenceBundle bundle =
        EvidenceBundle.builder()
            .events(audit.events())
            .classifications(classifications)
            .verifier(signer.verifier())
            .build();
    ChainVerification chain = bundle.chainVerification();
    System.out.printf(
        "  audit chain: %s (%d events, signed=%s): %s%n",
        chain.ok() ? "INTACT" : "BROKEN", chain.eventsChecked(), chain.signed(), chain.message());
    System.out.println();
    System.out.println(new OwaspAsiCoverageReport(bundle).toMarkdown());
  }

  /** The human step: whoever reviews the queue decides, not the model. */
  private static void reviewPending(Governance governance) {
    for (ApprovalRequest req : governance.approvals().pending(null, 10)) {
      long cents =
          Long.parseLong(JsonLite.readFlatObject(req.payloadJson()).getOrDefault("cents", "0"));
      System.out.printf("  review queue: %s%n", req.summary());
      if (cents <= AUTO_APPROVE_LIMIT_CENTS) {
        ApprovalRequest decided = governance.approvals().approve(req.id(), REVIEWER);
        System.out.printf("  reviewer: APPROVED -> %s%n", decided.resultJson());
      } else {
        governance
            .approvals()
            .reject(req.id(), REVIEWER, "over the $100 limit — route to a manager, not a chatbot");
        System.out.println("  reviewer: REJECTED (over the $100 limit)");
      }
    }
  }

  private static String line(AuditEvent event) {
    return event.toFlatMap().entrySet().stream()
        .map(e -> e.getKey() + "=" + quote(e.getValue()))
        .collect(Collectors.joining(" "));
  }

  private static String quote(String value) {
    return value.indexOf(' ') >= 0 ? '"' + value + '"' : value;
  }
}
