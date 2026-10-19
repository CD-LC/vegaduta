package io.vegaduta.agent.core.approval;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalDecisionAuthorizer;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.hook.FailMode;
import io.vegaduta.agent.api.hook.HookDecision;
import io.vegaduta.agent.api.hook.HookEvent;
import io.vegaduta.agent.api.hook.HookPoint;
import io.vegaduta.agent.core.hook.HookEngine;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApprovalNotifierTest {

  private static final URI URL = URI.create("https://hooks.example.test/T000/B000/xyz");

  private final NoopTransport transport = new NoopTransport();
  private final InMemoryApprovalStore approvals = new InMemoryApprovalStore();

  private ApprovalNotifier notifier(MessageFormat format) {
    return ApprovalNotifier.builder()
        .format(format)
        .transport(transport)
        .url(URL)
        .approvalStore(approvals)
        .build();
  }

  private DefaultApprovalGate gateWith(ApprovalNotifier notifier) {
    HookEngine hooks = new HookEngine().register(notifier.registration("chat"));
    return new DefaultApprovalGate(
        approvals,
        ApprovalDecisionAuthorizer.permitAll(),
        new ApprovalActionExecutorRegistry(),
        AuditSink.discard(),
        hooks,
        Duration.ofMinutes(10),
        Clock.systemUTC());
  }

  private static RunContext ctx() {
    return RunContext.builder().tenantId("acme").principal("alice").agentId("bot").build();
  }

  @Test
  void slackFormatPostsBlockKitForRequestAndDecision() {
    DefaultApprovalGate gate = gateWith(notifier(new SlackMessageFormat("/vegaduta")));

    ApprovalRequest request =
        gate.request(
            "tool:wire_transfer", "Wire 9,000 EUR to <ACME & Co>", "{\"amt\":9000}", ctx());
    gate.reject(request.id(), "bob", "over limit");

    List<NoopTransport.Sent> sent = transport.sent();
    assertThat(sent).hasSize(2);
    assertThat(sent.get(0).url()).isEqualTo(URL);
    assertThat(sent.get(0).contentType()).isEqualTo("application/json");
    String requested = sent.get(0).body();
    assertThat(requested)
        .startsWith("{\"text\":\"Approval requested: ")
        .contains("\"type\":\"header\"")
        .contains("*Action:* wire_transfer")
        .contains("*Summary:* Wire 9,000 EUR to &lt;ACME &amp; Co&gt;")
        .contains("*Tenant:* acme")
        .contains("/vegaduta approve " + request.id())
        .contains("/vegaduta reject " + request.id() + " <reason>");
    String decided = sent.get(1).body();
    assertThat(decided)
        .contains("\"text\":\"Approval rejected")
        .contains("*Decided by:* bob")
        .contains("*Reason:* over limit")
        .contains(request.id().toString());
  }

  @Test
  void teamsFormatPostsAdaptiveCard() {
    DefaultApprovalGate gate = gateWith(notifier(new TeamsMessageFormat()));

    ApprovalRequest request = gate.request("ticket:close", "Close INC-1", "{}", ctx());
    gate.approve(request.id(), "bob");

    List<NoopTransport.Sent> sent = transport.sent();
    assertThat(sent).hasSize(2);
    assertThat(sent.get(0).body())
        .startsWith(
            "{\"type\":\"message\",\"attachments\":[{\"contentType\":"
                + "\"application/vnd.microsoft.card.adaptive\"")
        .contains("\"type\":\"AdaptiveCard\",\"version\":\"1.4\"")
        .contains("{\"title\":\"Action\",\"value\":\"ticket:close\"}")
        .contains("{\"title\":\"Summary\",\"value\":\"Close INC-1\"}")
        .contains("approve " + request.id());
    assertThat(sent.get(1).body())
        .contains("\"text\":\"Approval approved\"")
        .contains("{\"title\":\"Decided by\",\"value\":\"bob\"}")
        .doesNotContain("Reply");
  }

  @Test
  void worksWithoutStoreAndIgnoresOtherPoints() {
    ApprovalNotifier notifier =
        ApprovalNotifier.builder()
            .format(new SlackMessageFormat())
            .transport(transport)
            .url(URL.toString())
            .build();
    HookEvent requested =
        new HookEvent(
            HookPoint.ON_APPROVAL_REQUESTED,
            ctx(),
            "send_email",
            "{}",
            Map.of(
                "approvalId",
                "11111111-2222-3333-4444-555555555555",
                "actionType",
                "tool:send_email"));
    HookEvent unrelated =
        new HookEvent(HookPoint.BEFORE_TOOL_CALL, ctx(), "send_email", "{}", Map.of());

    assertThat(notifier.apply(requested)).isEqualTo(HookDecision.CONTINUE);
    assertThat(notifier.apply(unrelated)).isEqualTo(HookDecision.CONTINUE);

    assertThat(transport.sent()).hasSize(1);
    assertThat(transport.sent().get(0).body())
        .contains("*Action:* send_email")
        .doesNotContain("Summary")
        .contains("approve 11111111-2222-3333-4444-555555555555");
  }

  @Test
  void transportFailureIsSwallowedUnlessPropagationIsRequested() {
    Transport failing =
        (url, contentType, body) -> {
          throw new IllegalStateException("HTTP 503");
        };
    HookEvent event =
        new HookEvent(
            HookPoint.ON_APPROVAL_DECIDED,
            ctx(),
            "send_email",
            "{}",
            Map.of("approvalId", "x", "decision", "approved", "decidedBy", "bob"));

    ApprovalNotifier quiet =
        ApprovalNotifier.builder()
            .format(new TeamsMessageFormat())
            .transport(failing)
            .url(URL)
            .build();
    assertThat(quiet.apply(event)).isEqualTo(HookDecision.CONTINUE);
    HookEngine closedButQuiet =
        new HookEngine().register(quiet.registration("chat", FailMode.CLOSED));
    assertThat(closedButQuiet.run(event)).isEqualTo(HookDecision.CONTINUE);

    ApprovalNotifier loud =
        ApprovalNotifier.builder()
            .format(new TeamsMessageFormat())
            .transport(failing)
            .url(URL)
            .propagateFailures(true)
            .build();
    HookEngine closed = new HookEngine().register(loud.registration("chat", FailMode.CLOSED));
    HookEngine open = new HookEngine().register(loud.registration("chat", FailMode.OPEN));
    assertThat(closed.run(event)).isEqualTo(HookDecision.block("hook_failed:chat"));
    assertThat(open.run(event)).isEqualTo(HookDecision.CONTINUE);
  }

  @Test
  void aFailClosedNotifierStopsTheRequestAndTheExecutionAtTheGate() {
    Transport failing =
        (url, contentType, body) -> {
          throw new IllegalStateException("HTTP 503");
        };
    ApprovalNotifier loud =
        ApprovalNotifier.builder()
            .format(new TeamsMessageFormat())
            .transport(failing)
            .url(URL)
            .approvalStore(approvals)
            .propagateFailures(true)
            .build();
    java.util.concurrent.atomic.AtomicInteger executed =
        new java.util.concurrent.atomic.AtomicInteger();
    ApprovalActionExecutorRegistry executors = new ApprovalActionExecutorRegistry();
    executors.register(
        new io.vegaduta.agent.api.approval.ApprovalActionExecutor() {
          @Override
          public String actionType() {
            return "tool:wire";
          }

          @Override
          public String execute(ApprovalRequest request) {
            executed.incrementAndGet();
            return "done";
          }
        });
    io.vegaduta.agent.core.audit.InMemoryAuditSink audit =
        new io.vegaduta.agent.core.audit.InMemoryAuditSink();
    DefaultApprovalGate gate =
        new DefaultApprovalGate(
            approvals,
            ApprovalDecisionAuthorizer.permitAll(),
            executors,
            audit,
            new HookEngine().register(loud.registration("chat", FailMode.CLOSED)),
            Duration.ofMinutes(10),
            Clock.systemUTC());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> gate.request("tool:wire", "wire 9000", "{}", ctx()))
        .isInstanceOf(ApprovalNotificationBlockedException.class);
    assertThat(gate.pending(null, 10)).as("nothing sits PENDING unseen").isEmpty();
    assertThat(approvals.findPending(null, 10)).isEmpty();
    assertThat(audit.events())
        .anySatisfy(
            e -> {
              assertThat(e.actionType()).isEqualTo("approval.decided");
              assertThat(e.outcome()).isEqualTo(io.vegaduta.agent.api.audit.AuditOutcome.FAILURE);
            });

    // With a quiet notifier at the request point but a loud one at decision time, the decision is
    // recorded and the action is not executed.
    DefaultApprovalGate plain =
        new DefaultApprovalGate(
            approvals,
            ApprovalDecisionAuthorizer.permitAll(),
            executors,
            audit,
            new HookEngine(),
            Duration.ofMinutes(10),
            Clock.systemUTC());
    ApprovalRequest request = plain.request("tool:wire", "wire 9000", "{}", ctx());
    ApprovalRequest decided = gate.approve(request.id(), "bob");
    assertThat(decided.status()).isEqualTo(io.vegaduta.agent.api.approval.ApprovalStatus.APPROVED);
    assertThat(executed).hasValue(0);
  }

  @Test
  void slackFallbackTextEscapesControlSequencesAndWebhookUrlsAreRedacted() {
    DefaultApprovalGate gate = gateWith(notifier(new SlackMessageFormat()));
    gate.request("tool:notify", "Refund <!channel> order & 42", "{}", ctx());

    String body = transport.sent().get(0).body();
    assertThat(body).doesNotContain("<!channel>");
    assertThat(body).contains("&lt;!channel&gt; order &amp; 42");

    assertThat(JdkHttpTransport.redact(URL)).isEqualTo("https://hooks.example.test/...");
    assertThat(JdkHttpTransport.redact(URL)).doesNotContain("xyz");
  }

  @Test
  void concurrentDecisionsOnOneRequestLetExactlyOneWin() throws Exception {
    java.util.concurrent.atomic.AtomicInteger executed =
        new java.util.concurrent.atomic.AtomicInteger();
    ApprovalActionExecutorRegistry executors = new ApprovalActionExecutorRegistry();
    executors.register(
        new io.vegaduta.agent.api.approval.ApprovalActionExecutor() {
          @Override
          public String actionType() {
            return "tool:wire";
          }

          @Override
          public String execute(ApprovalRequest request) {
            executed.incrementAndGet();
            return "done";
          }
        });
    DefaultApprovalGate gate =
        new DefaultApprovalGate(
            approvals,
            ApprovalDecisionAuthorizer.permitAll(),
            executors,
            AuditSink.discard(),
            new HookEngine(),
            Duration.ofMinutes(10),
            Clock.systemUTC());
    ApprovalRequest request = gate.request("tool:wire", "wire", "{}", ctx());

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(4);
    java.util.concurrent.atomic.AtomicInteger lost =
        new java.util.concurrent.atomic.AtomicInteger();
    try {
      java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
      for (String approver : java.util.List.of("bob", "carol", "dave", "erin")) {
        futures.add(
            pool.submit(
                () -> {
                  try {
                    gate.approve(request.id(), approver);
                  } catch (io.vegaduta.agent.api.approval.ApprovalAlreadyDecidedException e) {
                    lost.incrementAndGet();
                  }
                }));
      }
      for (java.util.concurrent.Future<?> f : futures) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(executed).hasValue(1);
    assertThat(lost).hasValue(3);
  }
}
