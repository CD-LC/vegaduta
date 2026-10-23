package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The tool loop runs inside Spring AI, so the application never sees the assistant's tool-use messages
 * or the tool results. {@link ToolLoopTranscript} is how a turn gets the real conversation back after
 * each tool round, to continue a reply from the actual transcript instead of a text summary of it.
 */
class ToolLoopTranscriptTest {

    private static List<Message> roundHistory(String callId) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        history.add(new UserMessage("create the file"));
        history.add(AssistantMessage.builder()
            .content("")
            .properties(Map.of("thoughtSignatures", List.of(new byte[] {1, 2, 3})))
            .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "write_file", "{\"path\":\"a.txt\"}")))
            .build());
        history.add(ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse(callId, "write_file", "{\"ok\":true}")))
            .build());
        return history;
    }

    private static Prompt promptWith(Map<String, Object> toolContext) {
        ToolCallingChatOptions.Builder builder = ToolCallingChatOptions.builder();
        if (toolContext != null) {
            builder.toolContext(toolContext);
        }
        return new Prompt(List.of(new UserMessage("create the file")), builder.build());
    }

    private static ChatResponse response(String callId) {
        AssistantMessage message = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "write_file", "{\"path\":\"a.txt\"}")))
            .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ArgumentSanitizingToolCallingManager managerReturning(List<Message> history, boolean returnDirect) {
        ToolCallingManager delegate = new ToolCallingManager() {
            @Override
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
                return List.of();
            }

            @Override
            public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                return ToolExecutionResult.builder().conversationHistory(history).returnDirect(returnDirect).build();
            }
        };
        return new ArgumentSanitizingToolCallingManager(delegate, new ObjectMapper());
    }

    @Test
    void aTurnThatRanNoToolRoundHasNoTranscript() {
        ToolLoopTranscript transcript = new ToolLoopTranscript();

        assertThat(transcript.hasRounds()).isFalse();
        assertThat(transcript.latest()).isEmpty();
    }

    @Test
    void theConversationAfterEachToolRoundIsRecordedWithTheToolUseAndToolResultMessages() {
        ToolLoopTranscript transcript = new ToolLoopTranscript();
        Prompt prompt = promptWith(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript));

        managerReturning(roundHistory("call-1"), false).executeToolCalls(prompt, response("call-1"));

        assertThat(transcript.hasRounds()).isTrue();
        List<Message> recorded = transcript.latest();
        assertThat(recorded).hasSize(4);
        AssistantMessage toolUse = (AssistantMessage) recorded.get(2);
        assertThat(toolUse.getToolCalls()).extracting(AssistantMessage.ToolCall::id).containsExactly("call-1");
        assertThat(toolUse.getMetadata()).as("provider metadata such as Gemini thought signatures survives").containsKey("thoughtSignatures");
        ToolResponseMessage toolResult = (ToolResponseMessage) recorded.get(3);
        assertThat(toolResult.getResponses().get(0).responseData()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void aLaterRoundReplacesTheEarlierOneBecauseItsHistoryContainsIt() {
        ToolLoopTranscript transcript = new ToolLoopTranscript();
        Prompt prompt = promptWith(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript));
        managerReturning(roundHistory("call-1"), false).executeToolCalls(prompt, response("call-1"));
        List<Message> second = new ArrayList<>(roundHistory("call-1"));
        second.addAll(roundHistory("call-2").subList(2, 4));

        managerReturning(second, false).executeToolCalls(prompt, response("call-2"));

        assertThat(transcript.latest()).hasSize(6);
    }

    @Test
    void aRoundThatReturnsTheToolResultDirectlyIsRecordedToo() {
        ToolLoopTranscript transcript = new ToolLoopTranscript();
        Prompt prompt = promptWith(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript));

        managerReturning(roundHistory("call-1"), true).executeToolCalls(prompt, response("call-1"));

        assertThat(transcript.hasRounds()).isTrue();
    }

    @Test
    void withoutTheKeyNothingIsRecordedAndTheHistoryIsUntouched() {
        List<Message> history = roundHistory("call-1");

        ToolExecutionResult result = managerReturning(history, false).executeToolCalls(promptWith(null), response("call-1"));

        assertThat(result.conversationHistory()).isEqualTo(history);
    }

    @Test
    void aNonTranscriptValueUnderTheKeyIsIgnored() {
        List<Message> history = roundHistory("call-1");
        Prompt prompt = promptWith(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, "not a transcript"));

        ToolExecutionResult result = managerReturning(history, false).executeToolCalls(prompt, response("call-1"));

        assertThat(result.conversationHistory()).isEqualTo(history);
    }

    @Test
    void theRecordedConversationIsACopyThatLaterMutationOfTheLoopsListCannotChange() {
        ToolLoopTranscript transcript = new ToolLoopTranscript();
        List<Message> history = new ArrayList<>(roundHistory("call-1"));
        transcript.record(history);

        history.clear();

        assertThat(transcript.latest()).hasSize(4);
    }
}
