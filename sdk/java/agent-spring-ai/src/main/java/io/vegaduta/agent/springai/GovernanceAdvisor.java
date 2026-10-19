package io.vegaduta.agent.springai;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.guardrail.ContentGuardrail;
import io.vegaduta.agent.api.guardrail.GuardrailResult;
import io.vegaduta.agent.api.guardrail.GuardrailStage;
import io.vegaduta.agent.api.hook.HookEvent;
import io.vegaduta.agent.api.hook.HookPoint;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernanceMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Governs a {@code ChatClient} call end to end:
 *
 * <ol>
 *   <li>resolves the {@link RunContext} (from the request context under {@link #CONTEXT_KEY}, the
 *       {@link RunContextHolder}, or a fresh anonymous run) and binds it for the call;
 *   <li>runs {@code INPUT} guardrails on the user message: blocked content never reaches the model,
 *       redacted content is replaced;
 *   <li>wraps every tool callback on the request in the governance pipeline and puts the run
 *       context into the tool context so the wrappers can find it;
 *   <li>runs {@code OUTPUT} guardrails on the reply (call path only; streamed replies are not
 *       inspected chunk by chunk);
 *   <li>audits the model call.
 * </ol>
 *
 * <p>Register it once with {@code ChatClient.builder(model).defaultAdvisors(advisor)}; the Boot
 * starter does this for every {@code ChatClient.Builder}.
 */
public final class GovernanceAdvisor implements CallAdvisor, StreamAdvisor {

  /** Key under which callers may put a {@link RunContext} into the {@code ChatClient} context. */
  public static final String CONTEXT_KEY = "vegaduta.runContext";

  /** Runs before chat memory so that blocked input is never stored and redacted output is. */
  public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 500;

  private final ToolGovernor governor;
  private final Governance governance;
  private final int order;

  public GovernanceAdvisor(ToolGovernor governor) {
    this(governor, DEFAULT_ORDER);
  }

  public GovernanceAdvisor(ToolGovernor governor, int order) {
    this.governor = governor;
    this.governance = governor.governance();
    this.order = order;
  }

  @Override
  public String getName() {
    return "vegaduta-governance";
  }

  @Override
  public int getOrder() {
    return order;
  }

  @Override
  @SuppressWarnings("try")
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    if (governance.policy().mode() == GovernanceMode.OFF) {
      return chain.nextCall(request);
    }
    RunContext context = resolveContext(request);
    try (RunContextHolder.Scope ignored = RunContextHolder.bind(context)) {
      Prepared prepared = before(request, context);
      if (prepared.blocked != null) {
        return prepared.blocked;
      }
      ChatClientResponse response = chain.nextCall(prepared.request);
      return after(response, context, modelOf(request.prompt()));
    }
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(
      ChatClientRequest request, StreamAdvisorChain chain) {
    if (governance.policy().mode() == GovernanceMode.OFF) {
      return chain.nextStream(request);
    }
    RunContext context = resolveContext(request);
    Prepared prepared = before(request, context);
    if (prepared.blocked != null) {
      return Flux.just(prepared.blocked);
    }
    return chain.nextStream(prepared.request);
  }

  private static RunContext resolveContext(ChatClientRequest request) {
    Object value = request.context().get(CONTEXT_KEY);
    if (value instanceof RunContext context) {
      return context;
    }
    return RunContextHolder.current().orElseGet(RunContext::anonymous);
  }

  private record Prepared(ChatClientRequest request, @Nullable ChatClientResponse blocked) {}

  private Prepared before(ChatClientRequest request, RunContext context) {
    Prompt prompt = request.prompt();
    List<Message> messages = new ArrayList<>(prompt.getInstructions());
    ContentGuardrail guardrails = governance.guardrails();

    // input guardrails on the last user message
    UserMessage user = prompt.getUserMessage();
    if (user != null && guardrails.appliesTo(GuardrailStage.INPUT)) {
      GuardrailResult checked = guardrails.evaluate(user.getText(), GuardrailStage.INPUT, context);
      if (checked.blocked()) {
        governance
            .hooks()
            .notify(
                new HookEvent(
                    HookPoint.ON_GUARDRAIL_VIOLATION, context, null, "", Map.of("stage", "INPUT")));
        auditModelCall(
            context,
            modelOf(prompt),
            AuditOutcome.DENIED,
            "input guardrail: " + checked.violations(),
            null);
        String text =
            "Your message could not be processed because it contains content that is not allowed here ("
                + checked.violations().stream().map(v -> v.code()).toList()
                + ").";
        return new Prepared(request, syntheticResponse(request, text));
      }
      if (!checked.clean()) {
        int index = messages.lastIndexOf(user);
        if (index >= 0) {
          messages.set(index, user.mutate().text(checked.content()).build());
        }
      }
    }

    // govern the tools on the request and hand them the run context
    ChatOptions options = prompt.getOptions();
    if (options instanceof ToolCallingChatOptions toolOptions) {
      List<ToolCallback> callbacks =
          toolOptions.getToolCallbacks() == null ? List.of() : toolOptions.getToolCallbacks();
      Map<String, Object> existing = toolOptions.getToolContext();
      Map<String, Object> toolContext =
          existing == null ? new HashMap<>() : new HashMap<>(existing);
      toolContext.put(RunContextResolver.TOOL_CONTEXT_KEY, context);
      List<ToolCallback> governed =
          callbacks.isEmpty() ? callbacks : governor.governAll(callbacks, context);
      options = toolOptions.mutate().toolCallbacks(governed).toolContext(toolContext).build();
    }

    governance
        .hooks()
        .notify(
            new HookEvent(
                HookPoint.BEFORE_MODEL_CALL,
                context,
                null,
                user == null ? "" : user.getText(),
                Map.of()));
    Prompt governedPrompt = Prompt.builder().messages(messages).chatOptions(options).build();
    return new Prepared(new ChatClientRequest(governedPrompt, request.context()), null);
  }

  private ChatClientResponse after(ChatClientResponse response, RunContext context, String model) {
    ChatResponse chatResponse = response.chatResponse();
    if (chatResponse == null || chatResponse.getResult() == null) {
      auditModelCall(context, model, AuditOutcome.SUCCESS, "empty response", chatResponse);
      return response;
    }
    Generation generation = chatResponse.getResult();
    AssistantMessage assistant = generation.getOutput();
    String text = assistant.getText();
    ContentGuardrail guardrails = governance.guardrails();
    AuditOutcome outcome = AuditOutcome.SUCCESS;
    String detail = null;
    if (text != null && guardrails.appliesTo(GuardrailStage.OUTPUT)) {
      GuardrailResult checked = guardrails.evaluate(text, GuardrailStage.OUTPUT, context);
      if (!checked.clean()) {
        String replacement =
            checked.blocked()
                ? "The reply was withheld by a content guardrail ("
                    + checked.violations().stream().map(v -> v.code()).toList()
                    + ")."
                : checked.content();
        outcome = checked.blocked() ? AuditOutcome.DENIED : AuditOutcome.WARNING;
        detail = "output guardrail: " + checked.violations();
        governance
            .hooks()
            .notify(
                new HookEvent(
                    HookPoint.ON_GUARDRAIL_VIOLATION,
                    context,
                    null,
                    "",
                    Map.of("stage", "OUTPUT")));
        AssistantMessage replaced =
            AssistantMessage.builder()
                .content(replacement)
                .properties(assistant.getMetadata())
                .toolCalls(assistant.getToolCalls())
                .media(assistant.getMedia())
                .build();
        List<Generation> generations = new ArrayList<>(chatResponse.getResults());
        generations.set(
            generations.indexOf(generation), new Generation(replaced, generation.getMetadata()));
        chatResponse = ChatResponse.builder().from(chatResponse).generations(generations).build();
        response =
            ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(response.context())
                .build();
      }
    }
    governance
        .hooks()
        .notify(
            new HookEvent(
                HookPoint.AFTER_MODEL_CALL, context, null, text == null ? "" : text, Map.of()));
    auditModelCall(context, model, outcome, detail, chatResponse);
    return response;
  }

  private ChatClientResponse syntheticResponse(ChatClientRequest request, String text) {
    ChatResponse chatResponse =
        ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage(text))))
            .build();
    return ChatClientResponse.builder()
        .chatResponse(chatResponse)
        .context(request.context())
        .build();
  }

  private static String modelOf(Prompt prompt) {
    ChatOptions options = prompt.getOptions();
    return options != null && options.getModel() != null ? options.getModel() : "unknown";
  }

  private void auditModelCall(
      RunContext context,
      String promptModel,
      AuditOutcome outcome,
      @Nullable String detail,
      @Nullable ChatResponse response) {
    String model = promptModel;
    if ("unknown".equals(model)
        && response != null
        && response.getMetadata() != null
        && response.getMetadata().getModel() != null
        && !response.getMetadata().getModel().isBlank()) {
      model = response.getMetadata().getModel();
    }
    AuditEvent.Builder builder =
        AuditEvent.builder("model.call", "model", model)
            .context(context)
            .outcome(outcome)
            .detail(detail)
            .risk(AsiRisk.ASI01);
    if (response != null
        && response.getMetadata() != null
        && response.getMetadata().getUsage() != null) {
      Integer total = response.getMetadata().getUsage().getTotalTokens();
      if (total != null) {
        builder.attribute("tokens", String.valueOf(total));
      }
    }
    governance.audit().emit(builder.build());
  }
}
