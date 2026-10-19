package io.vegaduta.agent.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

class GovernanceAdvisorTest {

  private final InMemoryAuditSink audit = new InMemoryAuditSink();
  private final Payments payments = new Payments();
  private final Governance governance =
      Governance.builder()
          .policy(GovernancePolicy.builder().mode(GovernanceMode.WARN).build())
          .classifications(GovernedMethodScanner.scan(payments))
          .auditSink(audit)
          .build();
  private final GovernanceAdvisor advisor = new GovernanceAdvisor(new ToolGovernor(governance));

  /** A chain that records the request it received and replies with canned text. */
  private static final class FakeChain implements CallAdvisorChain {
    final AtomicReference<ChatClientRequest> received = new AtomicReference<>();
    final String reply;

    FakeChain(String reply) {
      this.reply = reply;
    }

    @Override
    public ChatClientResponse nextCall(ChatClientRequest request) {
      received.set(request);
      ChatResponse response =
          ChatResponse.builder()
              .generations(List.of(new Generation(new AssistantMessage(reply))))
              .build();
      return ChatClientResponse.builder().chatResponse(response).context(request.context()).build();
    }

    @Override
    public List<CallAdvisor> getCallAdvisors() {
      return List.of();
    }

    @Override
    public CallAdvisorChain copy(CallAdvisor advisor) {
      return this;
    }
  }

  private ChatClientRequest request(String userText, RunContext context) {
    List<ToolCallback> tools =
        Arrays.asList(
            MethodToolCallbackProvider.builder().toolObjects(payments).build().getToolCallbacks());
    ToolCallingChatOptions options =
        ToolCallingChatOptions.builder().toolCallbacks(tools).model("test-model").build();
    Prompt prompt =
        Prompt.builder().messages(List.of(new UserMessage(userText))).chatOptions(options).build();
    return new ChatClientRequest(prompt, Map.of(GovernanceAdvisor.CONTEXT_KEY, context));
  }

  @Test
  void wrapsToolsAndPassesRunContextToThem() {
    RunContext ctx = RunContext.builder().principal("alice").sessionId("s1").build();
    FakeChain chain = new FakeChain("ok");

    ChatClientResponse response = advisor.adviseCall(request("hello", ctx), chain);

    assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("ok");
    ToolCallingChatOptions seen =
        (ToolCallingChatOptions) chain.received.get().prompt().getOptions();
    assertThat(seen.getToolCallbacks())
        .isNotEmpty()
        .allMatch(c -> c instanceof GovernedToolCallback);
    assertThat(seen.getToolContext()).containsEntry(RunContextResolver.TOOL_CONTEXT_KEY, ctx);
    assertThat(seen.getModel()).isEqualTo("test-model");
    AuditEvent modelCall =
        audit.events().stream()
            .filter(e -> e.actionType().equals("model.call"))
            .findFirst()
            .orElseThrow();
    assertThat(modelCall.outcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(modelCall.targetId()).isEqualTo("test-model");
    assertThat(modelCall.context().principal()).isEqualTo("alice");
  }

  @Test
  void blockedInputNeverReachesTheModel() {
    FakeChain chain = new FakeChain("should not be called");

    ChatClientResponse response =
        advisor.adviseCall(
            request("here is my key -----BEGIN RSA PRIVATE KEY----- xyz", RunContext.anonymous()),
            chain);

    assertThat(chain.received.get()).isNull();
    assertThat(response.chatResponse().getResult().getOutput().getText())
        .contains("could not be processed");
    assertThat(audit.events()).extracting(AuditEvent::outcome).contains(AuditOutcome.DENIED);
  }

  @Test
  void redactsSecretsInInputAndOutput() {
    FakeChain chain =
        new FakeChain("your token is ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCD ok");

    ChatClientResponse response =
        advisor.adviseCall(
            request("use AKIAIOSFODNN7EXAMPLE please", RunContext.anonymous()), chain);

    UserMessage seen = chain.received.get().prompt().getUserMessage();
    assertThat(seen.getText())
        .doesNotContain("AKIAIOSFODNN7EXAMPLE")
        .contains("[redacted:secret.aws_access_key]");
    assertThat(response.chatResponse().getResult().getOutput().getText())
        .doesNotContain("ghp_")
        .contains("[redacted:secret.github_token]");
    assertThat(audit.events()).extracting(AuditEvent::outcome).contains(AuditOutcome.WARNING);
  }

  @Test
  void offModePassesThrough() {
    Governance off =
        Governance.builder()
            .policy(GovernancePolicy.builder().mode(GovernanceMode.OFF).build())
            .auditSink(audit)
            .build();
    GovernanceAdvisor passthrough = new GovernanceAdvisor(new ToolGovernor(off));
    FakeChain chain = new FakeChain("raw");
    ChatClientRequest request = request("hi", RunContext.anonymous());

    passthrough.adviseCall(request, chain);

    assertThat(chain.received.get()).isSameAs(request);
  }
}
