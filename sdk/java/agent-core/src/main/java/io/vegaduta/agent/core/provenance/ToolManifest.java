package io.vegaduta.agent.core.provenance;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The canonical, hashed form of the set of tools an agent is offered.
 *
 * <p>Building a manifest from descriptors is deterministic: tools are sorted by name, the
 * description has its whitespace collapsed, the JSON Schema has whitespace outside string literals
 * removed and metadata is sorted by key. Each tool gets a SHA-256 over those canonical parts and
 * the manifest gets a SHA-256 over the per-tool digests, so two hosts that see the same tools
 * produce the same {@link #digest()} regardless of order or formatting.
 *
 * <p>{@link #toText()} and {@link #parse(String)} round-trip through a line-based lockfile that
 * needs no JSON library and diffs well in a code review:
 *
 * <pre>
 * # vegaduta-tools.lock v1
 * manifest	sha256:...
 * tool	&lt;name&gt;	desc=sha256:...	schema=sha256:...	meta=sha256:...	tool=sha256:...
 * </pre>
 *
 * <p>Fields are tab-separated; tool names must not contain tabs or line breaks. A parsed manifest
 * carries digests only: {@link #description(String)} is empty for it, so description scanning runs
 * on the manifest built from live descriptors.
 */
@Experimental
public final class ToolManifest {

  /** The lockfile header; the version suffix guards against a future format change. */
  public static final String HEADER = "# vegaduta-tools.lock v1";

  private static final String DIGEST_PREFIX = "sha256:";

  /**
   * One tool's hashes.
   *
   * @param name the tool name
   * @param descriptionDigest SHA-256 of the canonical description
   * @param schemaDigest SHA-256 of the canonical input schema
   * @param metadataDigest SHA-256 of the canonical metadata
   * @param digest SHA-256 over the name and the three digests above
   */
  public record Entry(
      String name,
      String descriptionDigest,
      String schemaDigest,
      String metadataDigest,
      String digest) {

    public Entry {
      requireToken(name, "name");
      Objects.requireNonNull(descriptionDigest, "descriptionDigest");
      Objects.requireNonNull(schemaDigest, "schemaDigest");
      Objects.requireNonNull(metadataDigest, "metadataDigest");
      Objects.requireNonNull(digest, "digest");
    }

    static Entry of(String name, String descDigest, String schemaDigest, String metaDigest) {
      String digest = sha256(name + '\n' + descDigest + '\n' + schemaDigest + '\n' + metaDigest);
      return new Entry(name, descDigest, schemaDigest, metaDigest, digest);
    }
  }

  private final Map<String, Entry> entries;
  private final Map<String, String> descriptions;
  private final String digest;

  private ToolManifest(Map<String, Entry> sortedEntries, Map<String, String> descriptions) {
    this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(sortedEntries));
    this.descriptions = Collections.unmodifiableMap(new LinkedHashMap<>(descriptions));
    this.digest = manifestDigest(this.entries.values());
  }

  /** Builds the manifest for a set of descriptors. Duplicate names are rejected. */
  public static ToolManifest of(Collection<ToolDescriptor> descriptors) {
    Objects.requireNonNull(descriptors, "descriptors");
    TreeMap<String, Entry> sorted = new TreeMap<>();
    TreeMap<String, String> descriptions = new TreeMap<>();
    for (ToolDescriptor d : descriptors) {
      String description = canonicalDescription(d.description());
      Entry entry =
          Entry.of(
              d.name(),
              sha256(description),
              sha256(canonicalSchema(d.inputSchemaJson())),
              sha256(canonicalMetadata(d.metadata())));
      if (sorted.putIfAbsent(d.name(), entry) != null) {
        throw new IllegalArgumentException("duplicate tool name: " + d.name());
      }
      descriptions.put(d.name(), description);
    }
    return new ToolManifest(sorted, descriptions);
  }

  /** An empty manifest: the agent is offered no tools. */
  public static ToolManifest empty() {
    return new ToolManifest(Map.of(), Map.of());
  }

  /** Entries sorted by tool name. */
  public List<Entry> entries() {
    return List.copyOf(entries.values());
  }

  public Optional<Entry> entry(String toolName) {
    return Optional.ofNullable(entries.get(toolName));
  }

  public boolean contains(String toolName) {
    return entries.containsKey(toolName);
  }

  public int size() {
    return entries.size();
  }

  /**
   * The canonical description of a tool, present only when this manifest was built from live
   * descriptors (a parsed lockfile carries digests only).
   */
  public Optional<String> description(String toolName) {
    return Optional.ofNullable(descriptions.get(toolName));
  }

  /** All canonical descriptions this manifest carries, by tool name. */
  public Map<String, String> descriptions() {
    return descriptions;
  }

  /** SHA-256 over the per-tool digests, as {@code sha256:<hex>}. */
  public String digest() {
    return digest;
  }

  /** The lockfile text; ends with a line separator. */
  public String toText() {
    StringBuilder sb = new StringBuilder();
    sb.append(HEADER).append('\n');
    sb.append("manifest\t").append(digest).append('\n');
    for (Entry e : entries.values()) {
      sb.append("tool\t")
          .append(e.name())
          .append("\tdesc=")
          .append(e.descriptionDigest())
          .append("\tschema=")
          .append(e.schemaDigest())
          .append("\tmeta=")
          .append(e.metadataDigest())
          .append("\ttool=")
          .append(e.digest())
          .append('\n');
    }
    return sb.toString();
  }

  /**
   * Parses lockfile text written by {@link #toText()}.
   *
   * @throws IllegalArgumentException when the header, a line or a digest is malformed, or when the
   *     recorded manifest digest does not match the entries (a hand-edited or truncated file)
   */
  public static ToolManifest parse(String text) {
    Objects.requireNonNull(text, "text");
    String[] lines = text.split("\\r?\\n");
    if (lines.length == 0 || !lines[0].trim().equals(HEADER)) {
      throw new IllegalArgumentException("not a vegaduta-tools.lock v1 file: missing header");
    }
    String recorded = null;
    TreeMap<String, Entry> sorted = new TreeMap<>();
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i];
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] f = line.split("\t");
      if (f[0].equals("manifest") && f.length == 2) {
        recorded = requireDigest(f[1], i + 1);
      } else if (f[0].equals("tool") && f.length == 6) {
        // Recompute the per-tool digest from its components: a hand-edited file whose component
        // digests no longer match its tool digest is rejected, not trusted.
        Entry e =
            Entry.of(
                f[1],
                requireDigest(stripKey(f[2], "desc=", i + 1), i + 1),
                requireDigest(stripKey(f[3], "schema=", i + 1), i + 1),
                requireDigest(stripKey(f[4], "meta=", i + 1), i + 1));
        String recordedTool = requireDigest(stripKey(f[5], "tool=", i + 1), i + 1);
        if (!e.digest().equals(recordedTool)) {
          throw new IllegalArgumentException(
              "line " + (i + 1) + ": tool digest does not match its components");
        }
        if (sorted.putIfAbsent(e.name(), e) != null) {
          throw new IllegalArgumentException("line " + (i + 1) + ": duplicate tool " + e.name());
        }
      } else {
        throw new IllegalArgumentException("line " + (i + 1) + ": unrecognised record");
      }
    }
    if (recorded == null) {
      throw new IllegalArgumentException("missing manifest digest line");
    }
    ToolManifest manifest = new ToolManifest(sorted, Map.of());
    if (!manifest.digest.equals(recorded)) {
      throw new IllegalArgumentException(
          "manifest digest mismatch: recorded "
              + recorded
              + " but entries hash to "
              + manifest.digest);
    }
    return manifest;
  }

  // ---- canonicalisation -------------------------------------------------------------------

  /** Trims and collapses every run of whitespace to one space. */
  static String canonicalDescription(String description) {
    return description.trim().replaceAll("\\s+", " ");
  }

  /**
   * Canonical JSON: object keys sorted recursively, arrays kept in order, no whitespace outside
   * string literals. The same schema serialised by two libraries with different key order hashes
   * the same; a changed default value or description inside the schema still changes the digest.
   * Text that is not valid JSON falls back to whitespace stripping.
   */
  static String canonicalSchema(String schemaJson) {
    try {
      SchemaCanonicalizer c = new SchemaCanonicalizer(schemaJson);
      StringBuilder out = new StringBuilder(schemaJson.length());
      c.value(out);
      c.skipWhitespace();
      if (c.pos == schemaJson.length()) {
        return out.toString();
      }
    } catch (IllegalArgumentException e) {
      // not JSON; fall through
    }
    return stripWhitespace(schemaJson);
  }

  private static String stripWhitespace(String schemaJson) {
    StringBuilder sb = new StringBuilder(schemaJson.length());
    boolean inString = false;
    boolean escaped = false;
    for (int i = 0; i < schemaJson.length(); i++) {
      char c = schemaJson.charAt(i);
      if (inString) {
        sb.append(c);
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
        sb.append(c);
      } else if (!Character.isWhitespace(c)) {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Metadata as length-prefixed {@code key} and {@code value} pairs sorted by key, so no key or
   * value can imitate a boundary: {@code {"origin":"a\nserver=x"}} and {@code {"origin":"a",
   * "server":"x"}} hash differently.
   */
  static String canonicalMetadata(Map<String, String> metadata) {
    TreeMap<String, String> sorted = new TreeMap<>(metadata);
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : sorted.entrySet()) {
      sb.append(e.getKey().length()).append(':').append(e.getKey());
      sb.append(e.getValue().length()).append(':').append(e.getValue());
      sb.append('\n');
    }
    return sb.toString();
  }

  /** A minimal JSON reader that re-emits the text with object keys sorted. */
  private static final class SchemaCanonicalizer {
    private static final int MAX_DEPTH = 128;
    private final String s;
    int pos;
    private int depth;

    SchemaCanonicalizer(String s) {
      this.s = s;
    }

    void value(StringBuilder out) {
      skipWhitespace();
      if (pos >= s.length()) {
        throw new IllegalArgumentException("unexpected end");
      }
      char c = s.charAt(pos);
      if (c == '{') {
        object(out);
      } else if (c == '[') {
        array(out);
      } else if (c == '"') {
        out.append(string());
      } else {
        int start = pos;
        while (pos < s.length() && ",]} \t\r\n".indexOf(s.charAt(pos)) < 0) {
          pos++;
        }
        if (start == pos) {
          throw new IllegalArgumentException("unexpected character");
        }
        out.append(s, start, pos);
      }
    }

    private void object(StringBuilder out) {
      enter();
      pos++;
      TreeMap<String, String> members = new TreeMap<>();
      skipWhitespace();
      if (peek() == '}') {
        pos++;
        out.append("{}");
        depth--;
        return;
      }
      while (true) {
        skipWhitespace();
        if (peek() != '"') {
          throw new IllegalArgumentException("expected key");
        }
        String key = string();
        skipWhitespace();
        expect(':');
        StringBuilder v = new StringBuilder();
        value(v);
        members.put(key, v.toString());
        skipWhitespace();
        char c = next();
        if (c == '}') {
          break;
        }
        if (c != ',') {
          throw new IllegalArgumentException("expected , or }");
        }
      }
      out.append('{');
      boolean first = true;
      for (Map.Entry<String, String> e : members.entrySet()) {
        if (!first) {
          out.append(',');
        }
        first = false;
        out.append(e.getKey()).append(':').append(e.getValue());
      }
      out.append('}');
      depth--;
    }

    private void array(StringBuilder out) {
      enter();
      pos++;
      out.append('[');
      skipWhitespace();
      if (peek() == ']') {
        pos++;
        out.append(']');
        depth--;
        return;
      }
      boolean first = true;
      while (true) {
        if (!first) {
          out.append(',');
        }
        first = false;
        value(out);
        skipWhitespace();
        char c = next();
        if (c == ']') {
          break;
        }
        if (c != ',') {
          throw new IllegalArgumentException("expected , or ]");
        }
      }
      out.append(']');
      depth--;
    }

    /** The string literal verbatim, quotes and escapes included. */
    private String string() {
      int start = pos;
      pos++;
      while (pos < s.length()) {
        char c = s.charAt(pos++);
        if (c == '\\') {
          pos++;
        } else if (c == '"') {
          return s.substring(start, pos);
        }
      }
      throw new IllegalArgumentException("unterminated string");
    }

    private void enter() {
      if (++depth > MAX_DEPTH) {
        throw new IllegalArgumentException("too deep");
      }
    }

    void skipWhitespace() {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
        pos++;
      }
    }

    private char peek() {
      if (pos >= s.length()) {
        throw new IllegalArgumentException("unexpected end");
      }
      return s.charAt(pos);
    }

    private char next() {
      char c = peek();
      pos++;
      return c;
    }

    private void expect(char c) {
      if (next() != c) {
        throw new IllegalArgumentException("expected " + c);
      }
    }
  }

  static String sha256(String text) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(DIGEST_PREFIX.length() + hash.length * 2);
      sb.append(DIGEST_PREFIX);
      for (byte b : hash) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
    }
  }

  private static String manifestDigest(Collection<Entry> sortedEntries) {
    StringBuilder sb = new StringBuilder();
    for (Entry e : sortedEntries) {
      sb.append(e.name()).append('\t').append(e.digest()).append('\n');
    }
    return sha256(sb.toString());
  }

  private static void requireToken(String value, String what) {
    Objects.requireNonNull(value, what);
    if (value.isBlank()
        || value.indexOf('\t') >= 0
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(what + " must be non-blank and contain no tab or newline");
    }
  }

  private static String requireDigest(String value, int line) {
    if (!value.startsWith(DIGEST_PREFIX) || value.length() != DIGEST_PREFIX.length() + 64) {
      throw new IllegalArgumentException("line " + line + ": malformed digest " + abbrev(value));
    }
    for (int i = DIGEST_PREFIX.length(); i < value.length(); i++) {
      if (Character.digit(value.charAt(i), 16) < 0) {
        throw new IllegalArgumentException("line " + line + ": malformed digest " + abbrev(value));
      }
    }
    return value;
  }

  private static String stripKey(String field, String key, int line) {
    if (!field.startsWith(key)) {
      throw new IllegalArgumentException(
          "line " + line + ": expected " + key + " but got " + abbrev(field));
    }
    return field.substring(key.length());
  }

  /** A short prefix of a field from an untrusted file, for an error message. */
  private static String abbrev(String value) {
    return value.length() > 48 ? value.substring(0, 48) + "..." : value;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof ToolManifest other && entries.equals(other.entries);
  }

  @Override
  public int hashCode() {
    return entries.hashCode();
  }

  @Override
  public String toString() {
    List<String> names = new ArrayList<>(entries.keySet());
    return "ToolManifest{" + digest + ", tools=" + names + "}";
  }
}
