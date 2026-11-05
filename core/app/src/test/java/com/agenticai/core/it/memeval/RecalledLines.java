package com.agenticai.core.it.memeval;

import com.agenticai.core.it.memeval.ProbeAnswerer.RecalledLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the memory lines out of the prompt block a chat turn builds ({@code ChatMemoryRecall}): legacy facts are
 * {@code "- text"} lines under the private and the shared header, unified items are {@code "- [kind] text"} lines
 * between the recalled-notes markers. Anything else (headers, the notes preamble) is framing, not memory.
 */
public final class RecalledLines {

    private static final Pattern UNIFIED = Pattern.compile("^- \\[([a-z-]+)] (.*)$");

    private RecalledLines() {}

    public static List<RecalledLine> parse(String block) {
        List<RecalledLine> out = new ArrayList<>();
        if (block == null || block.isBlank()) {
            return out;
        }
        boolean shared = false;
        for (String raw : block.split("\\R")) {
            String line = raw.strip();
            if (!line.startsWith("- ")) {
                if (!line.isEmpty()) {
                    shared = line.toLowerCase(Locale.ROOT).contains("shared by other agents");
                }
                continue;
            }
            Matcher m = UNIFIED.matcher(line);
            if (m.matches()) {
                out.add(new RecalledLine(m.group(2), m.group(1)));
            } else {
                out.add(new RecalledLine(line.substring(2).strip(), shared ? "shared" : "fact"));
            }
        }
        return out;
    }
}
