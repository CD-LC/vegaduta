package com.agenticai.core.knowledge.rag;

import com.agenticai.core.knowledge.Chunker;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parent-child ("small-to-big") chunking: cut a document into LARGE parent passages, then cut each
 * parent into SMALL child chunks. Only the children are embedded (small chunks give precise vector
 * and keyword hits); at retrieval time the matching child's parent text is what the model receives
 * (large chunks give the context needed to answer). This is the same two-level idea Dify calls its
 * "parent-child" retrieval mode and LlamaIndex calls small-to-big retrieval; the implementation
 * below is original.
 *
 * <p><b>Units.</b> "Tokens" in {@code app.knowledge.parent-child.*} are measured as whitespace
 * separated words - the same unit {@link Chunker} uses for every other chunk size on this platform
 * (the sidecar embeds at most {@link Chunker#MAX_CHUNK_SIZE} words per chunk, so a child is capped
 * there; a parent is never embedded and is capped separately at {@link #MAX_PARENT_WORDS}).
 *
 * <p><b>Boundaries.</b> Both levels pack whole sentences/paragraphs greedily: a paragraph break
 * (blank line) or a sentence terminator ({@code . ! ?} and the Devanagari danda, since this
 * platform ingests Indic text) ends an atomic unit; a unit longer than the level's budget is cut
 * by a hard word window. A markdown heading line also ends the unit before it, and at the PARENT
 * level a heading starts a new parent once the current one holds at least a quarter of its budget
 * (so a tiny section is not stranded alone, but "Refunds" and "Shipping" do not share a parent).
 *
 * <p><b>Offsets.</b> Every parent and child is an exact substring of the (line-ending normalised)
 * source text, so a child's {@code [startInParent, endInParent)} offsets index straight into its
 * parent's text and {@code parent.text().substring(start, end).equals(child.text())} always holds.
 * That is what lets retrieval return the parent as the context while the citation keeps pointing
 * at the child span. Children carry NO overlap: the parent already supplies the surrounding
 * context, and an overlap would break the exact-substring property.
 */
public final class ParentChildChunker {

    /** Parents are never embedded, so they are not bound by the sidecar's 200-word limit; this only bounds prompt blow-up. */
    public static final int MAX_PARENT_WORDS = 4000;

    // Same whitespace definition as Chunker (Unicode whitespace plus Python's extra ASCII separators).
    private static final Pattern WORD = Pattern.compile("[^\\s\\x1C-\\x1F]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PY_EXTRA_WHITESPACE = Pattern.compile("[\\x1C-\\x1F]");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?\\u0964\\u0965][\"'\\u201D\\u2019)\\]]*$");
    private static final Pattern HEADING_WORD = Pattern.compile("^#{1,6}$");
    private static final Pattern PARAGRAPH_GAP = Pattern.compile("\\n[ \\t\\u00A0]*\\n");

    /** Validated sizing, in words. */
    public record Settings(int childWords, int parentWords) {
        public Settings {
            if (childWords < Chunker.MIN_CHUNK_SIZE || childWords > Chunker.MAX_CHUNK_SIZE) {
                throw new IllegalArgumentException(
                    "child size must be between " + Chunker.MIN_CHUNK_SIZE + " and " + Chunker.MAX_CHUNK_SIZE
                        + " words (got " + childWords + ")"
                );
            }
            if (parentWords < childWords * 2 || parentWords > MAX_PARENT_WORDS) {
                throw new IllegalArgumentException(
                    "parent size must be at least twice the child size (" + (childWords * 2) + ") and at most "
                        + MAX_PARENT_WORDS + " words (got " + parentWords + ")"
                );
            }
        }

        /**
         * Clamps raw config into a valid pair instead of failing ingestion over a typo'd property:
         * child into [MIN, MAX], parent into [2 x child, MAX_PARENT_WORDS].
         */
        public static Settings clamped(int childWords, int parentWords) {
            int child = Math.max(Chunker.MIN_CHUNK_SIZE, Math.min(Chunker.MAX_CHUNK_SIZE, childWords));
            int parent = Math.max(child * 2, Math.min(MAX_PARENT_WORDS, parentWords));
            return new Settings(child, parent);
        }
    }

    /** A child chunk: {@code text} equals {@code parent.text().substring(startInParent, endInParent)}. */
    public record Child(String text, int startInParent, int endInParent) {}

    /** A parent passage and its children in reading order; {@code index} is its position in the document. */
    public record Parent(int index, String text, List<Child> children) {}

    private ParentChildChunker() {
    }

    public static List<Parent> chunk(String text, Settings settings) {
        List<Parent> parents = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return parents;
        }
        String normalized = PY_EXTRA_WHITESPACE.matcher(text.replace("\r\n", "\n").replace('\r', '\n')).replaceAll(" ");

        List<int[]> wordBounds = new ArrayList<>();
        Matcher m = WORD.matcher(normalized);
        while (m.find()) {
            wordBounds.add(new int[] {m.start(), m.end()});
        }
        int total = wordBounds.size();
        if (total == 0) {
            return parents;
        }
        int[] ws = new int[total];
        int[] we = new int[total];
        for (int i = 0; i < total; i++) {
            ws[i] = wordBounds.get(i)[0];
            we[i] = wordBounds.get(i)[1];
        }

        boolean[] unitEnd = new boolean[total];
        boolean[] sectionStart = new boolean[total];
        for (int i = 0; i < total; i++) {
            String word = normalized.substring(ws[i], we[i]);
            boolean last = i == total - 1;
            boolean paragraphBreak = !last && PARAGRAPH_GAP.matcher(normalized.substring(we[i], ws[i + 1])).find();
            unitEnd[i] = last || paragraphBreak || SENTENCE_END.matcher(word).find();
            sectionStart[i] = HEADING_WORD.matcher(word).matches() && startsLine(normalized, ws[i]);
        }
        // A heading line ends the unit before it, and the heading line itself is one unit
        // (so it travels with - never swallows - the text under it only via greedy packing).
        for (int i = 1; i < total; i++) {
            if (sectionStart[i]) {
                unitEnd[i - 1] = true;
            }
        }

        List<int[]> parentRanges = pack(unitEnd, sectionStart, 0, total, settings.parentWords(), settings.parentWords() / 4);
        int parentIndex = 0;
        for (int[] pr : parentRanges) {
            int parentStart = ws[pr[0]];
            int parentEnd = we[pr[1] - 1];
            String parentText = normalized.substring(parentStart, parentEnd);
            List<Child> children = new ArrayList<>();
            for (int[] cr : pack(unitEnd, sectionStart, pr[0], pr[1], settings.childWords(), Integer.MAX_VALUE)) {
                int cs = ws[cr[0]] - parentStart;
                int ce = we[cr[1] - 1] - parentStart;
                children.add(new Child(parentText.substring(cs, ce), cs, ce));
            }
            parents.add(new Parent(parentIndex++, parentText, List.copyOf(children)));
        }
        return parents;
    }

    /** True when the char at {@code pos} is the first non-blank char of its line. */
    private static boolean startsLine(String text, int pos) {
        for (int i = pos - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return true;
            }
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    /**
     * Greedy packing over word indices {@code [from, to)}. Atomic units end where {@code unitEnd}
     * is true; consecutive units are merged while the merged span stays within {@code maxWords}.
     * A unit longer than {@code maxWords} is cut into hard {@code maxWords} windows. When
     * {@code sectionBreakAfter} is reached, a unit that begins a section starts a new span instead
     * of merging. Returns half-open word-index ranges covering every word exactly once.
     */
    static List<int[]> pack(boolean[] unitEnd, boolean[] sectionStart, int from, int to, int maxWords, int sectionBreakAfter) {
        List<int[]> spans = new ArrayList<>();
        int curStart = -1;
        int curEnd = -1;
        int unitStart = from;
        for (int i = from; i < to; i++) {
            if (!unitEnd[i] && i != to - 1) {
                continue;
            }
            int a = unitStart;
            int b = i + 1;
            unitStart = b;
            int len = b - a;
            if (len > maxWords) {
                if (curStart >= 0) {
                    spans.add(new int[] {curStart, curEnd});
                    curStart = -1;
                }
                for (int x = a; x < b; x += maxWords) {
                    spans.add(new int[] {x, Math.min(x + maxWords, b)});
                }
                continue;
            }
            if (curStart < 0) {
                curStart = a;
                curEnd = b;
            } else if (b - curStart <= maxWords && !(sectionStart[a] && curEnd - curStart >= sectionBreakAfter)) {
                curEnd = b;
            } else {
                spans.add(new int[] {curStart, curEnd});
                curStart = a;
                curEnd = b;
            }
        }
        if (curStart >= 0) {
            spans.add(new int[] {curStart, curEnd});
        }
        return spans;
    }
}
