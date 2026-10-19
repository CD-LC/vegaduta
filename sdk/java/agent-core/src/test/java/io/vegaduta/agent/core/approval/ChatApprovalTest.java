package io.vegaduta.agent.core.approval;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.core.approval.ChatCommandParser.Action;
import io.vegaduta.agent.core.approval.ChatCommandParser.Command;
import io.vegaduta.agent.core.hook.HookEngine;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatApprovalTest {

  private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

  @Test
  void parsesPlainPrefixedAndDecoratedCommands() {
    assertThat(ChatCommandParser.parse("approve " + ID))
        .contains(new Command(Action.APPROVE, ID, null));
    assertThat(ChatCommandParser.parse("  REJECT   " + ID + "   too  expensive  "))
        .contains(new Command(Action.REJECT, ID, "too expensive"));
    assertThat(ChatCommandParser.parse("/vegaduta approve " + ID))
        .contains(new Command(Action.APPROVE, ID, null));
    assertThat(ChatCommandParser.parse("@VegaDuta: deny `" + ID + "` nope"))
        .contains(new Command(Action.REJECT, ID, "nope"));
    assertThat(ChatCommandParser.parse("approve <" + ID.toString().toUpperCase() + ">"))
        .contains(new Command(Action.APPROVE, ID, null));
  }

  @Test
  void rejectsEverythingElse() {
    assertThat(ChatCommandParser.parse(null)).isEmpty();
    assertThat(ChatCommandParser.parse("")).isEmpty();
    assertThat(ChatCommandParser.parse("approve")).isEmpty();
    assertThat(ChatCommandParser.parse("approve 42")).isEmpty();
    assertThat(ChatCommandParser.parse("approve " + ID.toString().substring(1))).isEmpty();
    assertThat(ChatCommandParser.parse("maybe " + ID)).isEmpty();
    assertThat(ChatCommandParser.parse("/vegaduta")).isEmpty();
    assertThat(ChatCommandParser.parse("/othertool approve " + ID)).isEmpty();
    assertThat(ChatCommandParser.usage()).contains("approve <approval-id>");
  }

  @Test
  void handlerAppliesDecisionsWithChatUserAsApprover() {
    InMemoryApprovalStore store = new InMemoryApprovalStore();
    ApprovalActionExecutorRegistry executors = new ApprovalActionExecutorRegistry();
    DefaultApprovalGate gate =
        new DefaultApprovalGate(
            store,
            new FourEyesAuthorizer(),
            executors,
            AuditSink.discard(),
            new HookEngine(),
            Duration.ofMinutes(5),
            Clock.systemUTC());
    ChatApprovalHandler handler = new ChatApprovalHandler(gate);
    RunContext alice = RunContext.builder().principal("alice").build();
    ApprovalRequest a = gate.request("tool:refund", "Refund #1", "{}", alice);
    ApprovalRequest b = gate.request("tool:refund", "Refund #2", "{}", alice);

    assertThat(handler.handle("approve " + a.id(), "alice"))
        .startsWith("Not allowed:")
        .contains("four-eyes");
    assertThat(handler.handle("approve " + a.id(), "U_BOB"))
        .isEqualTo("Approved Refund #1 (" + a.id() + "); it will run when picked up.");
    assertThat(store.find(a.id()).map(ApprovalRequest::decidedBy)).contains("U_BOB");
    assertThat(handler.handle("approve " + a.id(), "U_CAROL")).contains("already APPROVED");

    assertThat(handler.handle("/vegaduta reject " + b.id() + " customer withdrew", "U_BOB"))
        .isEqualTo("Rejected Refund #2 (" + b.id() + "): customer withdrew");
    assertThat(store.find(b.id()).map(ApprovalRequest::status)).contains(ApprovalStatus.REJECTED);

    assertThat(handler.handle("reject " + UUID.randomUUID(), "U_BOB"))
        .startsWith("No approval request with id");
    assertThat(handler.handle("what is this", "U_BOB")).isEqualTo(ChatCommandParser.usage());
    assertThat(handler.apply(new Command(Action.REJECT, b.id(), null), "U_BOB"))
        .contains("already REJECTED");
    assertThat(executors.find("tool:refund")).isEmpty();
  }
}
