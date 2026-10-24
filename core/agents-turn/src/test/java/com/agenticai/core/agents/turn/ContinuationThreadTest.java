package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.chat.ToolLoopTranscript;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

class ContinuationThreadTest {

    private static final String SYSTEM = "You are a coding agent.";

    private static List<Message> toolRound() {
        return List.of(
                new SystemMessage(SYSTEM),
                new UserMessage("history question"),
                new AssistantMessage("history answer"),
                new UserMessage("build it"),
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "write_file", "{}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "write_file", "wrote pom.xml")))
                        .build());
    }

    @Test
    void aTurnThatRanNoToolRoundContinuesFromTheHistoryTheRequestAndWhatTheModelSaid() {
        ContinuationThread thread =
                new ContinuationThread(
                        SYSTEM,
                        List.of(new UserMessage("earlier"), new AssistantMessage("earlier answer")),
                        "build it",
                        List.of());
        thread.passFinished(new ToolLoopTranscript());

        ContinuationThread.Request request = thread.next("Let me create the file:", "Carry on.");

        assertThat(request.systemPrompt()).as("the system prompt is sent the usual way").isEqualTo(SYSTEM);
        assertThat(request.user()).isEqualTo("Carry on.");
        assertThat(request.messages()).extracting(Message::getText)
                .containsExactly("earlier", "earlier answer", "build it", "Let me create the file:");
    }

    @Test
    void afterAToolRoundTheRealTranscriptIsTheBaselineSystemMessageIncluded() {
        ContinuationThread thread = new ContinuationThread(SYSTEM, List.of(), "build it", List.of());
        ToolLoopTranscript transcript = new ToolLoopTranscript();
        transcript.record(toolRound());
        thread.passFinished(transcript);

        ContinuationThread.Request request = thread.next("Written. Now let me run it:", "Carry on.");

        assertThat(request.systemPrompt()).as("the transcript already begins with the system message").isNull();
        List<Message> messages = request.messages();
        assertThat(messages).hasSize(7);
        assertThat(((AssistantMessage) messages.get(4)).getToolCalls().get(0).id()).isEqualTo("c1");
        assertThat(((ToolResponseMessage) messages.get(5)).getResponses().get(0).responseData()).isEqualTo("wrote pom.xml");
        assertThat(messages.get(6)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(6).getText()).isEqualTo("Written. Now let me run it:");
    }

    @Test
    void theOriginalRequestKeepsItsAttachments() {
        ChatAttachmentPayload image =
                new ChatAttachmentPayload(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] {1, 2, 3}), "a.png");
        ContinuationThread thread = new ContinuationThread(SYSTEM, List.of(), "what is in this picture", List.of(image));

        ContinuationThread.Request request = thread.next("Let me look at it:", "Carry on.");

        UserMessage original = (UserMessage) request.messages().get(0);
        assertThat(original.getText()).isEqualTo("what is in this picture");
        assertThat(original.getMedia()).hasSize(1);
        assertThat(original.getMedia().get(0).getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_PNG);
    }

    @Test
    void aSecondContinuationBuildsOnTheFirstAndANewToolRoundReplacesTheBaseline() {
        ContinuationThread thread = new ContinuationThread(SYSTEM, List.of(), "build it", List.of());
        thread.passFinished(new ToolLoopTranscript());
        thread.next("Step one is done. Now let me do step two:", "Carry on.");
        thread.passFinished(new ToolLoopTranscript());

        ContinuationThread.Request second = thread.next("Still going. Now I will do step three:", "Carry on.");

        assertThat(second.messages()).extracting(Message::getText)
                .containsExactly(
                        "build it",
                        "Step one is done. Now let me do step two:",
                        "Carry on.",
                        "Still going. Now I will do step three:");
    }

    @Test
    void anEmptyReplyIsNotSentAsAnAssistantMessage() {
        ContinuationThread thread = new ContinuationThread(SYSTEM, List.of(), "build it", List.of());

        assertThat(thread.next("  ", "Carry on.").messages()).hasSize(1);
    }
}
