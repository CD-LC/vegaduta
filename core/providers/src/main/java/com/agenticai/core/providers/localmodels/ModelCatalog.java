package com.agenticai.core.providers.localmodels;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The local model catalog: one pipe-separated text file, {@code local-models/catalog.txt} on the
 * classpath, that both this backend and {@code scripts/vegaduta models} read ({@code
 * deploy/models/catalog.txt} is a link to the same file), so the console and the CLI can never
 * disagree about what is on offer. Row format and the meaning of each column are in the file's own
 * header. A row that cannot be parsed is skipped with a warning; an unreadable file yields an empty
 * catalog, which the recommender answers with "nothing to recommend" rather than an error.
 */
@Component
public class ModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalog.class);

    static final String RESOURCE = "/local-models/catalog.txt";
    private static final String LADDER_PREFIX = "#ladder|";
    /** tag, family, download, RAM, tools, note, parameters, context, VRAM, licence, thinking, best for. */
    private static final int COLUMNS = 12;

    /**
     * How a model makes tool calls, derived from the catalog's {@code tools} column.
     *
     * <ul>
     *   <li>{@link #NATIVE}: structured {@code tool_calls}; agents work as they are.
     *   <li>{@link #CONSTRAINED}: tool calls are written as text or not yet verified; core
     *       recovers them or routes the choice through schema-constrained decoding (ToolRouterMode).
     *   <li>{@link #NONE}: no tool use; chat and summaries only.
     * </ul>
     */
    public enum ToolCalling {
        NATIVE,
        CONSTRAINED,
        NONE;

        /** The wire value used by the API: "native", "constrained" or "none". */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static ToolCalling fromCatalog(String tools) {
            return switch (tools == null ? "" : tools.trim().toLowerCase(Locale.ROOT)) {
                case "structured" -> NATIVE;
                case "chat only" -> NONE;
                // "text" (recovered from text) and "unverified" (not measured yet): the router covers both.
                default -> CONSTRAINED;
            };
        }
    }

    /**
     * One catalog row. {@code ramGb} is the memory the model wants, {@code downloadGb} its weights.
     * The detail columns ({@code paramsB} total parameters in billions, {@code contextK} context
     * window in thousands of tokens, {@code vramGb} video or unified memory that holds the whole
     * model, {@code licence}, {@code thinking}, {@code bestFor}) are optional in the file: a row
     * without them reads as 0 / blank / false.
     */
    public record Entry(
            String tag,
            String family,
            double downloadGb,
            double ramGb,
            ToolCalling toolCalling,
            String note,
            double paramsB,
            long contextK,
            double vramGb,
            String licence,
            boolean thinking,
            String bestFor) {

        /** A row with only the six original columns. */
        public Entry(String tag, String family, double downloadGb, double ramGb, ToolCalling toolCalling, String note) {
            this(tag, family, downloadGb, ramGb, toolCalling, note, 0, 0, 0, "", false, "");
        }
    }

    /** One {@code #ladder|minGB|tag} line: a machine with at least {@code minRamGb} GB is given {@code tag}. */
    public record Rung(long minRamGb, String tag) {}

    /** Everything one read of the file produced. */
    private record Parsed(List<Entry> entries, List<Rung> ladder) {}

    private final List<Entry> entries;
    private final List<Rung> ladder;

    @Autowired
    public ModelCatalog() {
        this(load());
    }

    private ModelCatalog(Parsed parsed) {
        this(parsed.entries(), parsed.ladder());
    }

    ModelCatalog(List<Entry> entries, List<Rung> ladder) {
        this.entries = List.copyOf(entries);
        this.ladder = ladder.stream()
                .sorted(Comparator.comparingLong(Rung::minRamGb).reversed())
                .toList();
    }

    /** Parses catalog text; used by tests. */
    static ModelCatalog parse(String text) {
        return new ModelCatalog(parseLines(new BufferedReader(new java.io.StringReader(text)).lines().toList()));
    }

    public List<Entry> entries() {
        return entries;
    }

    /** The memory ladder, highest minimum first; empty when the file has none. */
    public List<Rung> ladder() {
        return ladder;
    }

    public Optional<Entry> find(String tag) {
        if (tag == null) {
            return Optional.empty();
        }
        String wanted = tag.trim();
        return entries.stream().filter(e -> e.tag().equalsIgnoreCase(wanted)).findFirst();
    }

    private static Parsed load() {
        try (InputStream in = ModelCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("Local model catalog {} is not on the classpath; no models will be recommended.", RESOURCE);
                return new Parsed(List.of(), List.of());
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return parseLines(reader.lines().toList());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Local model catalog {} could not be read: {}", RESOURCE, e.toString());
            return new Parsed(List.of(), List.of());
        }
    }

    private static Parsed parseLines(List<String> lines) {
        List<Entry> parsed = new ArrayList<>();
        List<Rung> rungs = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith(LADDER_PREFIX)) {
                String[] f = line.split("\\|", 3);
                try {
                    if (f.length == 3 && !f[2].isBlank()) {
                        rungs.add(new Rung(Long.parseLong(f[1].trim()), f[2].trim()));
                        continue;
                    }
                } catch (NumberFormatException e) {
                    // falls through to the warning
                }
                log.warn("Skipping malformed local model catalog ladder line: {}", line);
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\\|", COLUMNS);
            if (f.length < 5) {
                log.warn("Skipping malformed local model catalog row: {}", line);
                continue;
            }
            try {
                parsed.add(new Entry(
                        f[0].trim(),
                        f[1].trim(),
                        Double.parseDouble(f[2].trim()),
                        Double.parseDouble(f[3].trim()),
                        ToolCalling.fromCatalog(f[4]),
                        column(f, 5),
                        number(f, 6),
                        (long) number(f, 7),
                        number(f, 8),
                        column(f, 9),
                        "yes".equalsIgnoreCase(column(f, 10)),
                        column(f, 11)));
            } catch (NumberFormatException e) {
                log.warn("Skipping local model catalog row with a bad number: {}", line);
            }
        }
        return new Parsed(parsed, rungs);
    }

    private static String column(String[] f, int index) {
        return f.length > index ? f[index].trim() : "";
    }

    /** A blank optional numeric column is 0; a non-numeric one is a bad row. */
    private static double number(String[] f, int index) {
        String raw = column(f, index);
        return raw.isEmpty() ? 0 : Double.parseDouble(raw);
    }
}
