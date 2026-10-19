package com.agenticai.core.agents.tools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, thread-safe tail of a STDIO MCP child process's stderr - the Connection Doctor's
 * evidence store (MCP-DOCTOR, 2026-07-25). The MCP SDK already reads the child's stderr
 * line-by-line and hands each line to the transport's {@code stdErrorHandler}; this buffer keeps
 * only the LAST {@value #MAX_LINES} lines (each clipped to {@value #MAX_LINE_CHARS} chars) so the
 * actionable cause of a spawn/handshake failure - e.g. "Use `uvx --from dee-mcp dee-mcp-server`
 * instead" - can be surfaced to the UI without ever persisting unbounded subprocess output.
 */
final class StderrTailBuffer {

    static final int MAX_LINES = 15;
    static final int MAX_LINE_CHARS = 400;

    private final ArrayDeque<String> lines = new ArrayDeque<>(MAX_LINES);

    synchronized void add(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String clipped =
                line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + "..." : line;
        if (lines.size() == MAX_LINES) {
            lines.removeFirst();
        }
        lines.addLast(clipped);
    }

    synchronized boolean isEmpty() {
        return lines.isEmpty();
    }

    synchronized List<String> snapshot() {
        return new ArrayList<>(lines);
    }

    /** One display-ready block, or empty string when nothing was captured. */
    synchronized String joined() {
        return String.join("\n", lines);
    }
}
