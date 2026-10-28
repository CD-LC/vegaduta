package com.agenticai.core.chat;

import com.agenticai.core.prompts.PromptResolver;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;

/**
 * The parked-turn close path: a turn parked on a human approval ends without another model call -
 * the person reads the platform's fixed pause reply (prompt key {@code message.approval.parked}),
 * queued calls keep the {@code approval_parked} marker in the history, and {@code returnDirect}
 * ends the loop so no model round writes wrap-up prose. Factored out of {@link
 * ArgumentSanitizingToolCallingManager} for size; the turn's own parked state lives on the approval
 * context, so rewriting the parked response's data to the reply text loses nothing the platform
 * reads back.
 */
final class ToolLoopParkedClose {

    private ToolLoopParkedClose() {}

    /**
     * The closing reply the person reads (stored in the history too): the prompt registry's text,
     * else the shipped one.
     */
    static String reply(PromptResolver prompts, Prompt prompt) {
        return ChatPrompts.text(
                prompts, ChatPrompts.APPROVAL_PARKED, ToolLoopRunHooks.promptRun(prompt));
    }

    /**
     * The tool result for a call queued behind an undecided approval: the {@code approval_parked}
     * marker (so a history scan still reads this turn as parked) with a machine-readable result -
     * the same shape {@code GuardedToolCallback} writes for calls it parks itself. No English of its
     * own beyond the fixed marker payload.
     */
    static String queuedResult() {
        return "{\"error\":\"approval_parked\",\"result\":\"queued behind the approval\"}";
    }

    /**
     * The direct-close result when the model asks for MORE tools while the turn is already parked:
     * every queued call is answered with the {@code approval_parked} marker result (the guard
     * refuses post-park calls anyway, so nothing executes), the first response carries the fixed
     * pause reply, and {@code returnDirect} ends the loop.
     */
    static ToolExecutionResult closeQueued(
            PromptResolver prompts,
            Prompt prompt,
            List<Message> history,
            List<AssistantMessage.ToolCall> calls) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            responses.add(
                    new ToolResponseMessage.ToolResponse(
                            calls.get(i).id(),
                            calls.get(i).name(),
                            i == 0 ? reply(prompts, prompt) : queuedResult()));
        }
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(true)
                .build();
    }

    /**
     * If the round just executed parked the turn (a {@link ToolResponseMessage} carrying the {@code
     * approval_parked} marker), swap the result for a direct close: the parked call's response leads
     * the list with the platform's fixed reply text (which is also what {@code buildGenerations}
     * hands the caller), the remaining responses keep their real data, and {@code returnDirect}
     * ends the loop so no model call runs after a park. Untouched results pass through.
     */
    static ToolExecutionResult close(PromptResolver prompts, Prompt prompt, ToolExecutionResult result) {
        if (result == null || result.returnDirect()) {
            return result;
        }
        List<Message> history = result.conversationHistory();
        if (history == null || history.isEmpty()) {
            return result;
        }
        Message last = history.get(history.size() - 1);
        if (!(last instanceof ToolResponseMessage toolResponses)
                || !ToolLoopMarkers.carriesParkedMarker(toolResponses)) {
            return result;
        }
        List<ToolResponseMessage.ToolResponse> ordered = new ArrayList<>(toolResponses.getResponses());
        for (int i = 0; i < ordered.size(); i++) {
            String data = ordered.get(i).responseData();
            if (ToolLoopMarkers.isMarkerDocument(data, ArgumentSanitizingToolCallingManager.APPROVAL_PARKED)) {
                // The parked call's own response becomes the closing reply (clean text for the
                // person and a legible result for the model's next turn); its id stays so the
                // call->response pairing still matches.
                ordered.set(
                        i,
                        new ToolResponseMessage.ToolResponse(
                                ordered.get(i).id(), ordered.get(i).name(), reply(prompts, prompt)));
                ordered.add(0, ordered.remove(i));
                break;
            }
        }
        List<Message> rewritten = new ArrayList<>(history);
        rewritten.set(rewritten.size() - 1, ToolResponseMessage.builder().responses(ordered).build());
        return ToolExecutionResult.builder()
                .conversationHistory(rewritten)
                .returnDirect(true)
                .build();
    }
}
