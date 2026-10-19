package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.tool.ToolStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * An ordered list of recorded tool calls plus free-form metadata, persisted as JSON Lines.
 *
 * <p>File layout: the first line is a header {@code {"format":"vegaduta-trace","version":1,
 * "metadata":{...}}}; every following line is one {@link TraceEntry}. Files are UTF-8. The format
 * is written and read by hand, so a trace file never depends on a JSON library being present.
 *
 * <p>{@link #canonicalForm()} is a stable text that omits the volatile fields (timestamps, run and
 * agent ids) and canonicalises the arguments, so it can be committed as a golden file and compared
 * with plain string equality.
 */
@Experimental
public final class Trace {

  /** The {@code format} value written in the header line. */
  public static final String FORMAT = "vegaduta-trace";

  /** The {@code version} value written in the header line. */
  public static final int VERSION = 1;

  private final List<TraceEntry> entries;
  private final Map<String, String> metadata;

  private Trace(List<TraceEntry> entries, Map<String, String> metadata) {
    this.entries = List.copyOf(entries);
    this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
  }

  /** A trace with no metadata; entries are renumbered 1..n in list order. */
  public static Trace of(List<TraceEntry> entries) {
    return of(entries, Map.of());
  }

  /** A trace with the given metadata; entries are renumbered 1..n in list order. */
  public static Trace of(List<TraceEntry> entries, Map<String, String> metadata) {
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(metadata, "metadata");
    List<TraceEntry> renumbered = new ArrayList<>(entries.size());
    int seq = 1;
    for (TraceEntry e : entries) {
      renumbered.add(e.seq() == seq ? e : e.withSeq(seq));
      seq++;
    }
    return new Trace(renumbered, metadata);
  }

  public static Trace empty() {
    return new Trace(List.of(), Map.of());
  }

  public List<TraceEntry> entries() {
    return entries;
  }

  public Map<String, String> metadata() {
    return metadata;
  }

  public int size() {
    return entries.size();
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** The entries for one tool, in order. */
  public List<TraceEntry> entriesFor(String toolName) {
    return entries.stream().filter(e -> e.toolName().equals(toolName)).toList();
  }

  /** Every distinct tool name, in first-seen order. */
  public Set<String> toolNames() {
    Set<String> names = new LinkedHashSet<>();
    entries.forEach(e -> names.add(e.toolName()));
    return Collections.unmodifiableSet(names);
  }

  /** A copy with one metadata entry added or replaced. */
  public Trace withMetadata(String key, String value) {
    Map<String, String> copy = new LinkedHashMap<>(metadata);
    copy.put(key, value);
    return new Trace(entries, copy);
  }

  // ---------------------------------------------------------------- JSON Lines persistence

  /** Writes the trace as JSON Lines (UTF-8), creating parent directories as needed. */
  public void save(Path path) {
    try {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(path, toJsonl(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write trace to " + path, e);
    }
  }

  /** Reads a trace written by {@link #save(Path)}. */
  public static Trace load(Path path) {
    try {
      return fromJsonl(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read trace from " + path, e);
    }
  }

  /** The JSON Lines text, one header line then one line per entry, each ending with {@code \n}. */
  public String toJsonl() {
    StringBuilder sb = new StringBuilder();
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("format", FORMAT);
    header.put("version", BigDecimal.valueOf(VERSION));
    header.put("metadata", new TreeMap<>(metadata));
    sb.append(TraceJson.write(header)).append('\n');
    for (TraceEntry e : entries) {
      sb.append(TraceJson.write(toMap(e, true))).append('\n');
    }
    return sb.toString();
  }

  /** Parses the JSON Lines text produced by {@link #toJsonl()}. */
  public static Trace fromJsonl(String jsonl) {
    List<TraceEntry> entries = new ArrayList<>();
    Map<String, String> metadata = new LinkedHashMap<>();
    boolean headerSeen = false;
    int lineNo = 0;
    for (String raw : jsonl.split("\n")) {
      lineNo++;
      String line = raw.strip();
      if (line.isEmpty()) {
        continue;
      }
      Map<String, Object> obj;
      try {
        obj = TraceJson.parseObject(line);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("trace line " + lineNo + ": " + e.getMessage(), e);
      }
      if (!headerSeen && FORMAT.equals(obj.get("format"))) {
        headerSeen = true;
        Object version = obj.get("version");
        if (version instanceof BigDecimal v && v.intValue() > VERSION) {
          throw new IllegalArgumentException(
              "trace version " + v.toPlainString() + " is newer than supported " + VERSION);
        }
        if (obj.get("metadata") instanceof Map<?, ?> meta) {
          meta.forEach((k, v) -> metadata.put(String.valueOf(k), String.valueOf(v)));
        }
        continue;
      }
      entries.add(fromMap(obj, lineNo));
    }
    return of(entries, metadata);
  }

  /**
   * A stable text form: one line per entry with the volatile fields omitted and the arguments
   * canonicalised. Equal traces (as {@link TraceDiff} sees them) have equal canonical forms.
   */
  public String canonicalForm() {
    StringBuilder sb = new StringBuilder();
    for (TraceEntry e : entries) {
      sb.append(TraceJson.write(toMap(e, false))).append('\n');
    }
    return sb.toString();
  }

  private static Map<String, Object> toMap(TraceEntry e, boolean withVolatile) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("seq", BigDecimal.valueOf(e.seq()));
    map.put("tool", e.toolName());
    map.put("args", withVolatile ? e.argumentsJson() : e.canonicalArguments());
    map.put("status", e.status().name());
    map.put("output", e.output());
    map.put("reason", e.reason());
    if (withVolatile) {
      map.put("at", e.at().toString());
      map.put("runId", e.runId());
      map.put("agentId", e.agentId());
    }
    return map;
  }

  private static TraceEntry fromMap(Map<String, Object> obj, int lineNo) {
    try {
      Object seq = obj.get("seq");
      String status = requireString(obj, "status");
      return new TraceEntry(
          seq instanceof BigDecimal d ? d.intValue() : 0,
          requireString(obj, "tool"),
          requireString(obj, "args"),
          ToolStatus.valueOf(status),
          requireString(obj, "output"),
          optionalString(obj, "reason"),
          obj.get("at") instanceof String at ? Instant.parse(at) : Instant.EPOCH,
          obj.get("runId") instanceof String runId ? runId : "",
          optionalString(obj, "agentId"));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "trace line " + lineNo + ": not a trace entry (" + e.getMessage() + ")", e);
    }
  }

  private static String requireString(Map<String, Object> obj, String key) {
    Object v = obj.get(key);
    if (v instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException("missing string field '" + key + "'");
  }

  private static @Nullable String optionalString(Map<String, Object> obj, String key) {
    Object v = obj.get(key);
    return v instanceof String s ? s : null;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Trace other
        && entries.equals(other.entries)
        && metadata.equals(other.metadata);
  }

  @Override
  public int hashCode() {
    return Objects.hash(entries, metadata);
  }

  @Override
  public String toString() {
    return "Trace[" + entries.size() + " entries, metadata=" + metadata + "]";
  }
}
