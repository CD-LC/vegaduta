package com.agenticai.core.graphmemory;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Deterministic fake LLMs for {@link MemoryWriteArbitrationService}: no real model is involved,
 * only the arbitration prompt contract (existing facts as {@code "- <subject> <relation words>
 * <object>"} lines, candidates as {@code "- (subject, RELATION, object)"} lines, a JSON array
 * reply). What these decide is therefore the decision an IDEAL (or deliberately naive) model would
 * make, not what a real model does - see docs/plans/MEMORY-ARBITRATION-FLIP-REPORT.md.
 */
final class ScriptedChats {

    private static final Pattern CANDIDATE = Pattern.compile("^- \\((.*?), (.*?), (.*)\\)$");

    private ScriptedChats() {
    }

    /** A chat client that always answers {@code content}. */
    static ChatClient fixed(String content) {
        return responding(prompt -> content);
    }

    /** A chat client whose reply is computed from the arbitration user prompt. */
    static ChatClient responding(Function<String, String> reply) {
        ChatClient chat = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        AtomicReference<String> lastUser = new AtomicReference<>("");
        when(chat.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenAnswer(inv -> {
            lastUser.set(inv.getArgument(0));
            return spec;
        });
        when(spec.call()).thenReturn(call);
        when(call.content()).thenAnswer(inv -> reply.apply(lastUser.get()));
        return chat;
    }

    /**
     * The decision an ideal arbitrator would make from the prompt alone. For a relation in {@code
     * multiValued}: equivalent known object -> DISCARD, otherwise ADD. For every other relation:
     * equivalent known object -> DISCARD, a different known object -> UPDATE, none -> ADD. With
     * {@code naive} every relation is treated as single-valued (the typical LLM over-eagerness that
     * overwrites multi-valued facts such as "speaks Hindi" / "speaks English").
     */
    static ChatClient rules(Set<String> multiValued, boolean naive) {
        return responding(prompt -> ruleReply(prompt, multiValued, naive));
    }

    static String ruleReply(String prompt, Set<String> multiValued, boolean naive) {
        String[] parts = prompt.split("Candidate facts to decide on:\n", 2);
        String known = parts[0];
        List<String> knownLines = known.lines().filter(l -> l.startsWith("- ")).map(l -> l.substring(2)).toList();
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (String line : parts.length > 1 ? parts[1].lines().toList() : List.<String>of()) {
            Matcher m = CANDIDATE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String subject = m.group(1);
            String relation = m.group(2);
            String object = m.group(3);
            String prefix = (subject + " " + relation.toLowerCase(Locale.ROOT).replace('_', ' ') + " ").toLowerCase(Locale.ROOT);
            boolean sameObject = false;
            boolean differentObject = false;
            for (String k : knownLines) {
                if (k.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    String knownObject = k.substring(prefix.length());
                    if (norm(knownObject).equals(norm(object))) {
                        sameObject = true;
                    } else {
                        differentObject = true;
                    }
                }
            }
            boolean multi = !naive && multiValued.contains(relation.toUpperCase(Locale.ROOT));
            String decision = sameObject ? "DISCARD" : (differentObject && !multi ? "UPDATE" : "ADD");
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"subject\":").append(quote(subject)).append(",\"relation\":").append(quote(relation))
                .append(",\"object\":").append(quote(object)).append(",\"decision\":\"").append(decision).append("\"}");
        }
        return json.append(']').toString();
    }

    private static String norm(String s) {
        return s.trim().replaceAll(" +", " ").toLowerCase(Locale.ROOT);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
