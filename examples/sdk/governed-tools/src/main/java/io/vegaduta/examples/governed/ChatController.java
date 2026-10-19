package io.vegaduta.examples.governed;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.springai.GovernanceAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One endpoint, one ChatClient. The governance advisor is already on the builder (the starter
 * added it); the only thing the application does is say who is asking.
 */
@RestController
public class ChatController {

  private final ChatClient chatClient;

  ChatController(ChatClient.Builder builder, OrderTools tools) {
    this.chatClient =
        builder
            .defaultSystem(
                "You are a support agent. Use the tools. If a tool says an action awaits"
                    + " approval, tell the user and stop.")
            .defaultTools(tools)
            .build();
  }

  @GetMapping("/chat")
  public String chat(@RequestParam String user, @RequestParam String q) {
    RunContext context = RunContext.builder().principal(user).agentId("support").build();
    return chatClient
        .prompt(q)
        .advisors(a -> a.param(GovernanceAdvisor.CONTEXT_KEY, context))
        .call()
        .content();
  }
}
