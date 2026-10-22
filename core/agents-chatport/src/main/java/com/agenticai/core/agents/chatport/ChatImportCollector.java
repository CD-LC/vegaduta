package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatImportParser.MAX_WARNINGS;

import com.agenticai.core.agents.chatport.ChatImportParser.Limits;
import com.agenticai.core.agents.chatport.ChatImportParser.ParseResult;
import com.agenticai.core.agents.chatport.ChatImportParser.ParsedChat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The state of one {@link ChatImportParser#parse} call: receives each conversation tree from {@link
 * ChatImportReader}, picks its {@link ChatImportFormat}, de-duplicates by key, honours the
 * selection and the conversation and per-import character caps, and collects capped warnings.
 */
final class ChatImportCollector {

    final Limits limits;
    final boolean keepMessages;
    private final Set<String> selected;
    private final List<ParsedChat> chats = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private final List<String> warnings = new ArrayList<>();
    private int total;

    /** Conversations not usable; the reader also counts the ones it could not read. */
    int skipped;

    private int index;
    private int found;
    private long keptChars;
    private boolean capReached;

    ChatImportCollector(Limits limits, boolean keepMessages, Set<String> selected) {
        this.limits = limits;
        this.keepMessages = keepMessages;
        this.selected = selected;
    }

    boolean done() {
        return capReached || (selected != null && found >= selected.size());
    }

    void warn(String message) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(message);
        }
    }

    ParseResult result() {
        return new ParseResult(
                List.copyOf(chats), total, skipped, capReached, List.copyOf(warnings));
    }

    void accept(JsonNode conv) {
        if (conv == null || !conv.isObject()) {
            skipped++;
            return;
        }
        if (total >= limits.maxConversations()) {
            capReached = true;
            return;
        }
        index++;
        ChatImportFormat format = ChatImportFormat.forConversation(conv);
        if (format == null) {
            skipped++;
            return;
        }
        String source = format.source(conv);
        String sourceId = format.sourceId(conv);
        String key = sourceId != null ? sourceId : "idx-" + index;
        if (!seen.add(key)) {
            skipped++;
            return;
        }
        total++;
        if (selected != null && !selected.contains(key)) {
            return;
        }
        ParsedChat chat;
        try {
            chat = format.convert(conv, key, source, sourceId, this);
        } catch (RuntimeException e) {
            skipped++;
            return;
        }
        if (chat == null) {
            skipped++;
            return;
        }
        if (keepMessages && keptChars + chat.chars() > limits.maxCharsPerImport()) {
            skipped++;
            warn(
                    "Some conversations were left out because the import would be too large; import them in a smaller batch.");
            return;
        }
        if (keepMessages) {
            keptChars += chat.chars();
        }
        found++;
        chats.add(chat);
    }
}
