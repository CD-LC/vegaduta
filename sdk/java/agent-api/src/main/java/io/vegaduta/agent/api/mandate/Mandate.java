package io.vegaduta.agent.api.mandate;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.risk.RiskLabel;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One link of a delegation chain: the authority under which an agent acts, as handed to it by the
 * previous hop. A mandate can only be {@linkplain #derive attenuated}, never widened: a derived
 * mandate names a subset of the tools, a risk ceiling at or below the parent's, and an expiry at or
 * before the parent's. Every mandate carries a SHA-256 digest over its canonical fields plus the
 * parent's digest, so a chain of mandates can be re-verified by anyone holding the whole chain
 * ({@link Mandates#verifyChain}).
 *
 * <p>Mandates are in-process capabilities, not tokens: they carry no signature and prove nothing to
 * a remote party. They answer the question "under whose mandate did hop 4 act, and did it exceed
 * hop 2's authority" inside one trust domain, where JWTs cannot be attenuated offline.
 *
 * @param id unique id of this mandate
 * @param issuer who granted this mandate: the root authority for a root mandate, the delegating
 *     agent's {@code subject} for a derived one
 * @param subject the agent that acts under this mandate
 * @param principal the human or service account the whole chain ultimately acts for, or {@code
 *     null}; fixed across the chain
 * @param allowedTools exact tool names or globs where {@code *} matches any run of characters
 * @param maxRisk the highest classification risk a tool may carry to be dispatched under this
 *     mandate
 * @param depth number of derivations from the root ({@code 0} for the root)
 * @param maxDepth maximum depth any descendant may reach; fixed across the chain
 * @param expiresAt instant after which the mandate no longer permits anything
 * @param parentId id of the mandate this one was derived from, or {@code null} for the root
 * @param digest lower-case hex SHA-256 over the canonical fields and the parent's digest
 */
@Experimental
public record Mandate(
    String id,
    String issuer,
    String subject,
    @Nullable String principal,
    Set<String> allowedTools,
    RiskLabel maxRisk,
    int depth,
    int maxDepth,
    Instant expiresAt,
    @Nullable String parentId,
    String digest) {

  private static final char PAIR_SEPARATOR = ';';
  private static final char TOOL_SEPARATOR = ',';

  /** Upper bound on any free-text field (id, issuer, subject, principal, tool pattern). */
  public static final int MAX_TEXT = 512;

  /** Upper bound on the number of tool patterns. */
  public static final int MAX_TOOLS = 256;

  private static final Set<String> KNOWN_KEYS =
      Set.of(
          "id",
          "iss",
          "sub",
          "prn",
          "tools",
          "risk",
          "depth",
          "maxDepth",
          "exp",
          "parent",
          "digest");

  public Mandate {
    requireText(id, "id");
    requireText(issuer, "issuer");
    requireText(subject, "subject");
    Objects.requireNonNull(allowedTools, "allowedTools");
    Objects.requireNonNull(maxRisk, "maxRisk");
    Objects.requireNonNull(expiresAt, "expiresAt");
    requireText(digest, "digest");
    if (depth < 0) {
      throw new IllegalArgumentException("depth must not be negative");
    }
    if (maxDepth < 0) {
      throw new IllegalArgumentException("maxDepth must not be negative");
    }
    if (depth > maxDepth) {
      throw new IllegalArgumentException("depth " + depth + " exceeds maxDepth " + maxDepth);
    }
    if ((depth == 0) != (parentId == null)) {
      throw new IllegalArgumentException(
          "a root mandate has depth 0 and no parent, and vice versa");
    }
    if (allowedTools.size() > MAX_TOOLS) {
      throw new IllegalArgumentException("allowedTools has more than " + MAX_TOOLS + " entries");
    }
    for (String tool : allowedTools) {
      requireText(tool, "allowedTools entry");
    }
    if (principal != null) {
      requireText(principal, "principal");
    }
    allowedTools = Collections.unmodifiableSet(new TreeSet<>(allowedTools));
  }

  /** Starts a root mandate (depth 0, no parent) for {@code subject} issued by {@code issuer}. */
  public static RootBuilder root(String issuer, String subject) {
    return new RootBuilder(issuer, subject);
  }

  /**
   * Hands a narrower mandate to the next agent in the chain. The derived mandate is issued by this
   * mandate's subject, keeps the principal and the maximum depth, and only attenuates.
   *
   * @param newSubject the agent that will act under the derived mandate
   * @param narrowerTools tool names or globs; every entry must be covered by this mandate
   * @param lowerOrEqualRisk a risk ceiling at or below this mandate's
   * @param earlierOrEqualExpiry an expiry at or before this mandate's
   * @throws IllegalArgumentException on any widening or when the chain would exceed {@code
   *     maxDepth}
   */
  public Mandate derive(
      String newSubject,
      Set<String> narrowerTools,
      RiskLabel lowerOrEqualRisk,
      Instant earlierOrEqualExpiry) {
    requireText(newSubject, "newSubject");
    Objects.requireNonNull(narrowerTools, "narrowerTools");
    Objects.requireNonNull(lowerOrEqualRisk, "lowerOrEqualRisk");
    Objects.requireNonNull(earlierOrEqualExpiry, "earlierOrEqualExpiry");
    if (depth + 1 > maxDepth) {
      throw new IllegalArgumentException(
          "mandate " + id + " is at depth " + depth + " of " + maxDepth + "; cannot derive");
    }
    checkAttenuation(this, narrowerTools, lowerOrEqualRisk, earlierOrEqualExpiry);
    return Builder.build(
        UUID.randomUUID().toString(),
        subject,
        newSubject,
        principal,
        narrowerTools,
        lowerOrEqualRisk,
        depth + 1,
        maxDepth,
        earlierOrEqualExpiry,
        id,
        digest);
  }

  /** True when {@code toolName} matches one of {@link #allowedTools} (exact or glob). */
  public boolean permits(String toolName) {
    Objects.requireNonNull(toolName, "toolName");
    for (String pattern : allowedTools) {
      if (Glob.matches(pattern, toolName)) {
        return true;
      }
    }
    return false;
  }

  /** True when {@code now} is at or after {@link #expiresAt}. */
  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  /**
   * The digest this mandate should carry given its parent's digest ({@code null} for a root). Equal
   * to {@link #digest} when the mandate has not been tampered with.
   */
  public String expectedDigest(@Nullable String parentDigest) {
    return sha256(canonical(parentDigest));
  }

  /**
   * Compact, JSON-free encoding: {@code key=value} pairs separated by {@code ;}, every value
   * URL-encoded. Fits in {@link io.vegaduta.agent.api.RunContext#attributes()}.
   */
  public String encode() {
    StringBuilder sb = new StringBuilder();
    pair(sb, "id", id);
    pair(sb, "iss", issuer);
    pair(sb, "sub", subject);
    if (principal != null) {
      pair(sb, "prn", principal);
    }
    List<String> encodedTools = new ArrayList<>(allowedTools.size());
    for (String tool : allowedTools) {
      encodedTools.add(urlEncode(tool));
    }
    sb.append("tools=").append(String.join(String.valueOf(TOOL_SEPARATOR), encodedTools));
    sb.append(PAIR_SEPARATOR);
    pair(sb, "risk", maxRisk.name());
    pair(sb, "depth", Integer.toString(depth));
    pair(sb, "maxDepth", Integer.toString(maxDepth));
    pair(sb, "exp", expiresAt.toString());
    if (parentId != null) {
      pair(sb, "parent", parentId);
    }
    sb.append("digest=").append(urlEncode(digest));
    return sb.toString();
  }

  /**
   * Reverses {@link #encode()}. Does not verify the digest against a parent: that needs the chain,
   * see {@link Mandates#verifyChain}; a root's self-contained digest is checked by {@link
   * #isSelfConsistent()}. Strict: a duplicate key or a key outside the encoding is rejected, so
   * exactly one text decodes to a given mandate.
   *
   * @throws IllegalArgumentException when the text is not a mandate encoding
   */
  public static Mandate decode(String encoded) {
    Objects.requireNonNull(encoded, "encoded");
    Map<String, String> fields = new LinkedHashMap<>();
    for (String pair : encoded.split(String.valueOf(PAIR_SEPARATOR))) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        throw new IllegalArgumentException("malformed mandate pair");
      }
      String key = pair.substring(0, eq);
      if (!KNOWN_KEYS.contains(key)) {
        throw new IllegalArgumentException("unknown mandate key '" + abbreviate(key) + "'");
      }
      if (fields.putIfAbsent(key, pair.substring(eq + 1)) != null) {
        throw new IllegalArgumentException("duplicate mandate key '" + key + "'");
      }
    }
    try {
      String toolsField = required(fields, "tools");
      Set<String> tools = new TreeSet<>();
      if (!toolsField.isEmpty()) {
        for (String tool : toolsField.split(String.valueOf(TOOL_SEPARATOR))) {
          tools.add(urlDecode(tool));
        }
      }
      String principal = fields.containsKey("prn") ? urlDecode(fields.get("prn")) : null;
      String parent = fields.containsKey("parent") ? urlDecode(fields.get("parent")) : null;
      return new Mandate(
          urlDecode(required(fields, "id")),
          urlDecode(required(fields, "iss")),
          urlDecode(required(fields, "sub")),
          principal,
          tools,
          RiskLabel.valueOf(urlDecode(required(fields, "risk"))),
          Integer.parseInt(required(fields, "depth")),
          Integer.parseInt(required(fields, "maxDepth")),
          Instant.parse(urlDecode(required(fields, "exp"))),
          parent,
          urlDecode(required(fields, "digest")));
    } catch (NumberFormatException | DateTimeParseException e) {
      throw new IllegalArgumentException("malformed mandate: " + e.getMessage(), e);
    }
  }

  /**
   * Throws when {@code tools}, {@code risk} or {@code expiry} would widen {@code parent}. Shared by
   * {@link #derive} and {@link Mandates#verifyChain}.
   */
  static void checkAttenuation(Mandate parent, Set<String> tools, RiskLabel risk, Instant expiry) {
    if (risk.compareTo(parent.maxRisk) > 0) {
      throw new IllegalArgumentException(
          "risk " + risk + " exceeds parent mandate " + parent.id + " ceiling " + parent.maxRisk);
    }
    if (expiry.isAfter(parent.expiresAt)) {
      throw new IllegalArgumentException(
          "expiry "
              + expiry
              + " is after parent mandate "
              + parent.id
              + " expiry "
              + parent.expiresAt);
    }
    for (String tool : tools) {
      if (!parent.covers(tool)) {
        throw new IllegalArgumentException(
            "tool pattern '" + tool + "' is not covered by parent mandate " + parent.id);
      }
    }
  }

  /**
   * True for a root mandate whose digest matches its own fields (depth 0, no parent). A derived
   * mandate cannot be checked without its parent; see {@link Mandates#verifyChain}.
   */
  public boolean isSelfConsistent() {
    return depth == 0 && expectedDigest(null).equals(digest);
  }

  /**
   * True when every tool name {@code pattern} could match is also permitted here. An exact name is
   * covered when it is permitted; a glob is covered when some pattern of this mandate matches it
   * taken literally (a {@code *} in the child can then only expand inside a {@code *} of the
   * parent).
   */
  boolean covers(String pattern) {
    return permits(pattern);
  }

  /**
   * The digested form. Every free-text value is URL-encoded, so no value can contain the field
   * separator ({@code \n}) or the tool separator ({@code ,}) and two different mandates can never
   * canonicalise to the same text.
   */
  private String canonical(@Nullable String parentDigest) {
    StringBuilder sb = new StringBuilder();
    sb.append("id=").append(urlEncode(id)).append('\n');
    sb.append("issuer=").append(urlEncode(issuer)).append('\n');
    sb.append("subject=").append(urlEncode(subject)).append('\n');
    sb.append("principal=").append(principal == null ? "" : urlEncode(principal)).append('\n');
    List<String> encodedTools = new ArrayList<>(allowedTools.size());
    for (String tool : allowedTools) {
      encodedTools.add(urlEncode(tool));
    }
    sb.append("tools=").append(String.join(",", encodedTools)).append('\n');
    sb.append("maxRisk=").append(maxRisk.name()).append('\n');
    sb.append("depth=").append(depth).append('\n');
    sb.append("maxDepth=").append(maxDepth).append('\n');
    sb.append("expiresAt=").append(expiresAt.toString()).append('\n');
    sb.append("parentId=").append(parentId == null ? "" : urlEncode(parentId)).append('\n');
    sb.append("parentDigest=").append(parentDigest == null ? "" : parentDigest).append('\n');
    return sb.toString();
  }

  private static String sha256(String text) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
    }
  }

  private static void pair(StringBuilder sb, String key, String value) {
    sb.append(key).append('=').append(urlEncode(value)).append(PAIR_SEPARATOR);
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String urlDecode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static String required(Map<String, String> fields, String key) {
    String value = fields.get(key);
    if (value == null) {
      throw new IllegalArgumentException("mandate encoding lacks '" + key + "'");
    }
    return value;
  }

  private static void requireText(@Nullable String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > MAX_TEXT) {
      throw new IllegalArgumentException(name + " is longer than " + MAX_TEXT + " characters");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        throw new IllegalArgumentException(name + " must not contain control characters");
      }
    }
  }

  private static String abbreviate(String s) {
    return s.length() > 32 ? s.substring(0, 32) + "..." : s;
  }

  /** Builds a root mandate. */
  public static final class RootBuilder {
    private final String issuer;
    private final String subject;
    private String id = UUID.randomUUID().toString();
    private @Nullable String principal;
    private final Set<String> allowedTools = new TreeSet<>();
    private RiskLabel maxRisk = RiskLabel.MEDIUM;
    private int maxDepth = 4;
    private @Nullable Instant expiresAt;

    private RootBuilder(String issuer, String subject) {
      this.issuer = issuer;
      this.subject = subject;
    }

    public RootBuilder id(String value) {
      this.id = Objects.requireNonNull(value, "id");
      return this;
    }

    public RootBuilder principal(@Nullable String value) {
      this.principal = value;
      return this;
    }

    /** Adds patterns; duplicates collapse, a null entry fails like a blank one does. */
    public RootBuilder allowTools(String... patterns) {
      for (String pattern : patterns) {
        requireText(pattern, "allowedTools entry");
        allowedTools.add(pattern);
      }
      return this;
    }

    public RootBuilder allowTools(Set<String> patterns) {
      allowedTools.addAll(patterns);
      return this;
    }

    public RootBuilder maxRisk(RiskLabel value) {
      this.maxRisk = Objects.requireNonNull(value, "maxRisk");
      return this;
    }

    public RootBuilder maxDepth(int value) {
      this.maxDepth = value;
      return this;
    }

    public RootBuilder expiresAt(Instant value) {
      this.expiresAt = Objects.requireNonNull(value, "expiresAt");
      return this;
    }

    public Mandate build() {
      if (expiresAt == null) {
        throw new IllegalArgumentException("a root mandate needs an expiry");
      }
      return Builder.build(
          id,
          issuer,
          subject,
          principal,
          allowedTools,
          maxRisk,
          0,
          maxDepth,
          expiresAt,
          null,
          null);
    }
  }

  /** Internal: constructs a mandate and stamps its digest. */
  private static final class Builder {
    private Builder() {}

    static Mandate build(
        String id,
        String issuer,
        String subject,
        @Nullable String principal,
        Set<String> tools,
        RiskLabel maxRisk,
        int depth,
        int maxDepth,
        Instant expiresAt,
        @Nullable String parentId,
        @Nullable String parentDigest) {
      Mandate unsigned =
          new Mandate(
              id, issuer, subject, principal, tools, maxRisk, depth, maxDepth, expiresAt, parentId,
              "pending");
      return new Mandate(
          id,
          issuer,
          subject,
          principal,
          unsigned.allowedTools,
          maxRisk,
          depth,
          maxDepth,
          expiresAt,
          parentId,
          unsigned.expectedDigest(parentDigest));
    }
  }
}
