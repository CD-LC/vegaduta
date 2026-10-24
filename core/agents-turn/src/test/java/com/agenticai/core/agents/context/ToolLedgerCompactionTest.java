package com.agenticai.core.agents.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.context.ContextCompactionEngine.Params;
import com.agenticai.core.agents.context.ContextCompactionEngine.Result;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.ToolLedgerReplay;
import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.agents.turn.ToolLedgerProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The tool record rides inside the assistant turn's text, so the context-compaction engine treats it as
 * the text it is: the turns it summarises hand the summariser the record (file ids and outcomes survive
 * into the summary), and the protected recent turns keep theirs verbatim. Nothing about the record needs a
 * special case in the engine.
 */
class ToolLedgerCompactionTest {

    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");
    private static final Params PARAMS = new Params(10_000, 0.6, 0.85, 2, 2, 100, true);
    private final ToolLedgerProperties props = new ToolLedgerProperties();
    private final ContextCompactionEngine engine = new ContextCompactionEngine();

    private List<Message> history(int exchanges) {
        List<ChatTurn> stored = new ArrayList<>();
        for (int i = 1; i <= exchanges; i++) {
            stored.add(new ChatTurn(MessageRole.USER, "u" + i + " " + "q".repeat(3_000), T));
            ToolLedger ledger =
                    new ToolLedger(
                            List.of(new ToolLedger.Entry("create_file", "{\"n\":" + i + "}", "{\"fileId\":\"file-" + i + "\"}", ToolLedger.Status.OK, null)),
                            0);
            stored.add(new ChatTurn(MessageRole.ASSISTANT, "a" + i + " " + "r".repeat(3_000), T, ledger));
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("memory block"));
        for (ChatTurn turn : ToolLedgerReplay.modelView(stored, props)) {
            messages.add(
                    turn.role() == MessageRole.USER
                            ? new UserMessage(turn.content())
                            : new AssistantMessage(turn.content()));
        }
        return messages;
    }

    @Test
    void theSummariserIsHandedTheRecordOfTheTurnsItReplaces() {
        List<Message> messages = history(6);
        AtomicReference<List<Message>> seen = new AtomicReference<>();

        Result result = engine.apply(messages, 0, PARAMS, (prior, turns) -> {
            seen.set(turns);
            return Optional.of("- files created: file-1, file-2");
        });

        assertTrue(result.compactedMessages() > 0, "the control: this history is long enough to compact");
        String seenText = String.join("\n", seen.get().stream().map(Message::getText).toList());
        assertTrue(seenText.contains("create_file") && seenText.contains("file-1"), seenText.substring(0, 400));
    }

    @Test
    void theRecentTurnsThatAreKeptShowTheirRecordVerbatim() {
        List<Message> messages = history(6);
        Message newestAssistant = messages.get(messages.size() - 1);

        List<Message> out = engine.apply(messages, 0, PARAMS, (prior, turns) -> Optional.of("summary")).messages();

        Message keptNewest = out.get(out.size() - 1);
        assertEquals(newestAssistant.getText(), keptNewest.getText());
        assertTrue(keptNewest.getText().contains("file-6"));
    }

    @Test
    void theRecordSitsInTheFirstCharactersOfALongReplySoAnExcerptKeepsIt() {
        List<ChatTurn> stored =
                List.of(
                        new ChatTurn(
                                MessageRole.ASSISTANT,
                                "x".repeat(10_000),
                                T,
                                new ToolLedger(List.of(new ToolLedger.Entry("create_file", "{}", "{\"fileId\":\"file-1\"}", ToolLedger.Status.OK, null)), 0)));

        String text = ToolLedgerReplay.modelView(stored, props).get(0).content();

        assertTrue(text.substring(0, 2_000).contains("file-1"), "the summary excerpts read the first 2,000 characters of a turn");
    }
}
