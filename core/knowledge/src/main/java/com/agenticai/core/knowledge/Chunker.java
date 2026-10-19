package com.agenticai.core.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one chunker every ingestion path uses ({@link KnowledgeController}'s
 * ingest/upload endpoints, {@link AddToKnowledgeBaseToolCallback}, the async
 * job and connector paths - all of them funnel into {@link
 * KnowledgeIngestionService#ingest}, which calls {@link #chunk}).
 *
 * <p>Before this class, chunking happened inside the sidecar's {@code
 * chunk_text}: a blind sliding window of {@code chunk_size} words that cut
 * straight through headings, paragraphs and sentences. This splits on
 * STRUCTURE first and only falls back to the word window for a single
 * sentence that is itself longer than a chunk:
 *
 * <ol>
 *   <li><b>Sections</b> - a markdown ATX heading ({@code # ...} through
 *       {@code ###### ...}) always starts a new chunk, and the heading line
 *       travels with the content under it. Overlap is never carried across a
 *       section boundary (the tail of "Refunds" is noise at the head of
 *       "Shipping").</li>
 *   <li><b>Paragraphs</b> - blank-line separated blocks inside a section are
 *       packed greedily into a chunk while they fit.</li>
 *   <li><b>Sentences</b> - a paragraph longer than a chunk is split on
 *       sentence terminators ({@code . ! ?} and the Devanagari danda
 *       {@code ।}/{@code ॥}, since this platform ingests Indic text) and the
 *       sentences are packed the same way.</li>
 *   <li><b>Word window</b> - only a single sentence longer than a whole chunk
 *       is cut by the old fixed window ({@code chunkSize} words, stepping
 *       {@code chunkSize - chunkOverlap}).</li>
 * </ol>
 *
 * <p>Sizes stay measured in WORDS, same unit as before, and every emitted
 * chunk is guaranteed to be at most {@code chunkSize} words. That guarantee is
 * load-bearing: {@link SidecarClient#embedPreChunked} sends each chunk to the
 * sidecar's {@code /embed} with {@code chunk_size = MAX_CHUNK_SIZE}, which only
 * returns exactly one embedding per chunk because no chunk exceeds that. Word
 * splitting uses Unicode whitespace ({@code (?U)\s}) so the count matches
 * Python's {@code str.split()} on the sidecar side (plain {@code \s} would
 * treat a no-break space as part of a word and undercount).
 *
 * <p>Overlap: when a section spills into a second chunk, the last {@code
 * chunkOverlap} words of the previous chunk are carried into the next one
 * (shrunk if needed so the next chunk still fits), preserving the old
 * "context straddles the boundary" behaviour for long sections.
 *
 * <p>Defaults (150/30) are unchanged from the historical hardcoded values, so
 * a collection with no per-collection chunking configured (migration V361
 * columns null) chunks with the same budget as before - only the boundaries
 * move to structural ones.
 */
public final class Chunker {

    public static final int DEFAULT_CHUNK_SIZE = 150;
    public static final int DEFAULT_CHUNK_OVERLAP = 30;
    /** Same bounds the sidecar's EmbedRequest enforces (ge=10, le=200) - see this class's javadoc for why MAX matters. */
    public static final int MIN_CHUNK_SIZE = 10;
    public static final int MAX_CHUNK_SIZE = 200;

    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}(\\s.*)?$");
    private static final Pattern BLANK_LINE = Pattern.compile("\\n[ \\t\\u00A0]*\\n");
    // Split AFTER a terminator (optionally followed by closing quotes/brackets) and whitespace.
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?\\u0964\\u0965][\"'\\u201D\\u2019)\\]]?)(?U)\\s+");
    // Python's str.split() also treats the ASCII file/group/record/unit separators as whitespace.
    private static final Pattern PY_EXTRA_WHITESPACE = Pattern.compile("[\\x1C-\\x1F]");

    /** Validated chunking budget, in words. */
    public record Settings(int chunkSize, int chunkOverlap) {
        public static final Settings DEFAULTS = new Settings(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);

        public Settings {
            if (chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE) {
                throw new IllegalArgumentException(
                    "chunkSize must be between " + MIN_CHUNK_SIZE + " and " + MAX_CHUNK_SIZE + " words (got " + chunkSize + ")"
                );
            }
            if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
                throw new IllegalArgumentException(
                    "chunkOverlap must be at least 0 and smaller than chunkSize (got overlap " + chunkOverlap
                        + ", size " + chunkSize + ")"
                );
            }
        }
    }

    private Chunker() {
    }

    /**
     * Precedence: explicit per-request value, then the collection's own
     * configured value (V361, nullable), then the platform default. Size and
     * overlap resolve independently, then validate together.
     */
    public static Settings resolve(Integer requestedSize, Integer requestedOverlap, Integer collectionSize, Integer collectionOverlap) {
        int size = requestedSize != null ? requestedSize
            : collectionSize != null ? collectionSize
            : DEFAULT_CHUNK_SIZE;
        int overlap = requestedOverlap != null ? requestedOverlap
            : collectionOverlap != null ? collectionOverlap
            : DEFAULT_CHUNK_OVERLAP;
        // A small explicit size with the default overlap (e.g. size 20, overlap 30)
        // would otherwise be rejected for an overlap the caller never chose.
        if (requestedOverlap == null && collectionOverlap == null && overlap >= size) {
            overlap = size / 5;
        }
        return new Settings(size, overlap);
    }

    public static int wordCount(String text) {
        return words(text).length;
    }

    /**
     * {@code text} unchanged if it has at most {@code maxWords} words, else its
     * first {@code maxWords} words space-joined. Used when re-embedding stored
     * chunks, which can't be re-cut (their ids and text are what search
     * returns) but must still fit the single-embedding contract of
     * {@link SidecarClient#embedPreChunked}.
     */
    static String firstWords(String text, int maxWords) {
        String[] words = words(text);
        if (words.length <= maxWords) {
            return text;
        }
        return String.join(" ", Arrays.copyOfRange(words, 0, maxWords));
    }

    public static List<String> chunk(String text, Settings settings) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String normalized = PY_EXTRA_WHITESPACE.matcher(text.replace("\r\n", "\n").replace('\r', '\n')).replaceAll(" ");
        for (List<String> section : sections(normalized)) {
            chunkSection(section, settings, chunks);
        }
        return chunks;
    }

    /** Each section is a list of paragraph blocks; a heading line opens a new section and is its first block. */
    private static List<List<String>> sections(String text) {
        List<List<String>> sections = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String block : BLANK_LINE.split(text)) {
            StringBuilder pending = new StringBuilder();
            for (String line : block.split("\n", -1)) {
                if (HEADING.matcher(line).matches()) {
                    addBlock(current, pending);
                    if (!current.isEmpty()) {
                        sections.add(current);
                        current = new ArrayList<>();
                    }
                    current.add(line.strip());
                } else {
                    if (pending.length() > 0) {
                        pending.append('\n');
                    }
                    pending.append(line);
                }
            }
            addBlock(current, pending);
        }
        if (!current.isEmpty()) {
            sections.add(current);
        }
        return sections;
    }

    private static void addBlock(List<String> section, StringBuilder pending) {
        String block = pending.toString().strip();
        if (!block.isEmpty()) {
            section.add(block);
        }
        pending.setLength(0);
    }

    /** A packable piece of a section; {@code block} = a whole paragraph/heading (joined with a blank line), else a sentence/window fragment. */
    private record Unit(String text, int words, boolean block) {}

    private static void chunkSection(List<String> blocks, Settings settings, List<String> out) {
        int size = settings.chunkSize();
        List<Unit> units = new ArrayList<>();
        for (String block : blocks) {
            int blockWords = wordCount(block);
            if (blockWords <= size) {
                units.add(new Unit(block, blockWords, true));
                continue;
            }
            for (String sentence : SENTENCE_BOUNDARY.split(block)) {
                int sentenceWords = wordCount(sentence);
                if (sentenceWords == 0) {
                    continue;
                }
                if (sentenceWords <= size) {
                    units.add(new Unit(sentence.strip(), sentenceWords, false));
                } else {
                    for (String window : wordWindow(words(sentence), settings)) {
                        units.add(new Unit(window, wordCount(window), false));
                    }
                }
            }
        }

        StringBuilder current = new StringBuilder();
        int currentWords = 0;
        for (Unit unit : units) {
            if (currentWords > 0 && currentWords + unit.words() > size) {
                String previousChunk = current.toString();
                out.add(previousChunk);
                current.setLength(0);
                currentWords = 0;
                // Carry overlap inside a section only, shrunk so the next unit still fits.
                int carry = Math.min(settings.chunkOverlap(), size - unit.words());
                if (carry > 0) {
                    String[] prevWords = words(previousChunk);
                    carry = Math.min(carry, prevWords.length);
                    current.append(String.join(" ", Arrays.copyOfRange(prevWords, prevWords.length - carry, prevWords.length)));
                    currentWords = carry;
                }
            }
            if (currentWords > 0) {
                current.append(unit.block() ? "\n\n" : " ");
            }
            current.append(unit.text());
            currentWords += unit.words();
        }
        if (currentWords > 0) {
            out.add(current.toString());
        }
    }

    /** The legacy sliding window, used only for a single over-long sentence. */
    static List<String> wordWindow(String[] words, Settings settings) {
        List<String> windows = new ArrayList<>();
        int total = words.length;
        if (total == 0) {
            return windows;
        }
        int step = settings.chunkSize() - settings.chunkOverlap();
        int start = 0;
        while (start < total) {
            int end = Math.min(start + settings.chunkSize(), total);
            windows.add(String.join(" ", Arrays.copyOfRange(words, start, end)));
            if (end >= total) {
                break;
            }
            start += step;
        }
        return windows;
    }

    private static String[] words(String text) {
        if (text == null) {
            return new String[0];
        }
        // Filter rather than strip(): String.strip() doesn't treat a no-break
        // space as whitespace, but WHITESPACE ((?U)\s) does, which would
        // otherwise yield an empty leading "word".
        return Arrays.stream(WHITESPACE.split(text)).filter(w -> !w.isEmpty()).toArray(String[]::new);
    }
}
