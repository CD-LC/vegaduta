package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.core.hook.JsonLite;
import io.vegaduta.agent.core.text.Text;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes an audit log tamper-evident. Every event is linked to its predecessor with SHA-256 and
 * handed to the delegate sink with extra attributes:
 *
 * <ul>
 *   <li>{@code chain.seq}: 1-based position in this writer's chain
 *   <li>{@code chain.prev}: the previous event's hash ({@link #GENESIS}, 64 zeros, for the first)
 *   <li>{@code chain.hash}: SHA-256 hex over {@code chain.prev} followed by the {@linkplain
 *       #canonical canonical form} of the event
 *   <li>{@code chain.sig} and {@code chain.alg}: when a {@link Signer} is configured, the Base64
 *       signature over the UTF-8 bytes of {@code chain.hash}
 *   <li>{@code chain.sigError}: when the signer threw; the event is chained unsigned so the chain
 *       stays contiguous and a verifier with a {@link Verifier} reports the gap
 * </ul>
 *
 * <p>The canonical form covers every top-level field of the event under its own name and every
 * attribute under {@code attr.<key>}, so an attribute can never shadow a field (an {@code
 * actionType} attribute does not hide the real action type from the hash).
 *
 * <p>Editing, removing or reordering any stored event changes or orphans a hash, which {@link
 * #verify(List, Optional)} reports with the sequence number of the first bad link (EU AI Act Art.
 * 12 asks for exactly this property). The event id and timestamp are preserved, so the chained
 * event is the same record with extra attributes.
 *
 * <p><b>Resuming.</b> The chain state lives in memory. A process that restarts must resume from the
 * last stored {@code chain.seq}/{@code chain.hash} with {@link #HashChainedAuditSink(AuditSink,
 * Signer, long, String)} or {@link #resume(long, String)}; otherwise the new process starts a
 * second chain at seq 1 and a verifier over the whole log reports a break at the restart. Each
 * writer must own its chain: two replicas writing into one sink must use distinct sinks or tag and
 * verify per writer.
 *
 * <p><b>Threading.</b> Sequencing, hashing and signing happen under one lock; the delegate is
 * called outside it, so a slow or stalled sink does not serialise every tool call in the process.
 * The delegate may therefore see events slightly out of chain order under contention; {@code
 * chain.seq} is the order of record and {@link EvidenceBundle} sorts by it before verifying.
 *
 * <p>Exceptions from the signer are contained (see {@code chain.sigError}); exceptions from the
 * delegate propagate, so wrap the delegate in {@code CompositeAuditSink.safe} when it must not
 * reach the pipeline (Governance does).
 */
@Experimental
public final class HashChainedAuditSink implements AuditSink {

  public static final String SEQ = "chain.seq";
  public static final String PREV = "chain.prev";
  public static final String HASH = "chain.hash";
  public static final String SIG = "chain.sig";
  public static final String ALG = "chain.alg";
  public static final String SIG_ERROR = "chain.sigError";

  /** Prefix under which attributes appear in the canonical form. */
  public static final String ATTR_PREFIX = "attr.";

  /** The {@code chain.prev} of the first event. */
  public static final String GENESIS = "0".repeat(64);

  private static final Logger log = LoggerFactory.getLogger(HashChainedAuditSink.class);
  private static final Set<String> CHAIN_KEYS = Set.of(SEQ, PREV, HASH, SIG, ALG, SIG_ERROR);

  private final AuditSink delegate;
  private final @Nullable Signer signer;
  private final Object lock = new Object();
  private long seq;
  private String head = GENESIS;

  public HashChainedAuditSink(AuditSink delegate) {
    this(delegate, null);
  }

  public HashChainedAuditSink(AuditSink delegate, @Nullable Signer signer) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.signer = signer;
  }

  /**
   * A sink that continues an existing chain: the next event gets {@code lastSeq + 1} and {@code
   * chain.prev = lastHash}. Pass {@code 0} and {@link #GENESIS} for a new chain.
   */
  public HashChainedAuditSink(
      AuditSink delegate, @Nullable Signer signer, long lastSeq, String lastHash) {
    this(delegate, signer);
    resume(lastSeq, lastHash);
  }

  /**
   * Repositions the chain on the last stored link. Only meaningful before the first {@link #emit}
   * of this instance (for example at start-up, from the newest row of the durable sink).
   *
   * @throws IllegalArgumentException when {@code lastSeq} is negative, {@code lastHash} is not a
   *     64-character hex string, or seq 0 is paired with anything but {@link #GENESIS}
   */
  public void resume(long lastSeq, String lastHash) {
    Objects.requireNonNull(lastHash, "lastHash");
    if (lastSeq < 0) {
      throw new IllegalArgumentException("lastSeq must not be negative");
    }
    if (lastHash.length() != 64 || !lastHash.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
      throw new IllegalArgumentException("lastHash must be 64 hex characters");
    }
    if (lastSeq == 0 && !GENESIS.equals(lastHash)) {
      throw new IllegalArgumentException("seq 0 must pair with the GENESIS hash");
    }
    synchronized (lock) {
      this.seq = lastSeq;
      this.head = lastHash;
    }
  }

  @Override
  public void emit(AuditEvent event) {
    AuditEvent chained;
    synchronized (lock) {
      long next = seq + 1;
      String prev = head;
      String hash = sha256Hex(prev + canonical(event));
      Map<String, String> attributes = new LinkedHashMap<>(event.attributes());
      CHAIN_KEYS.forEach(attributes::remove);
      attributes.put(SEQ, Long.toString(next));
      attributes.put(PREV, prev);
      attributes.put(HASH, hash);
      if (signer != null) {
        try {
          String alg = signer.algorithm();
          String sig = Base64.getEncoder().encodeToString(signer.sign(utf8(hash)));
          attributes.put(ALG, alg);
          attributes.put(SIG, sig);
        } catch (RuntimeException e) {
          // Keep the chain contiguous: the event is recorded unsigned and says so. A verifier
          // asked to check signatures reports the gap at this seq.
          log.warn("Audit chain signer failed at seq {}: {}", next, e.toString());
          attributes.put(SIG_ERROR, Text.shortText(e.getClass().getSimpleName()));
        }
      }
      chained = withAttributes(event, attributes);
      seq = next;
      head = hash;
    }
    delegate.emit(chained);
  }

  /** Number of events chained so far by this writer (including a resumed prefix). */
  public long size() {
    synchronized (lock) {
      return seq;
    }
  }

  /** The hash of the most recent event, or {@link #GENESIS} when nothing was written yet. */
  public String head() {
    synchronized (lock) {
      return head;
    }
  }

  /**
   * Checks a stored chain. {@code events} must be in emission order and may be a suffix of the full
   * chain (a capped in-memory sink drops old events): the first event's {@code chain.prev} is
   * trusted unless its sequence number is 1, in which case it must be {@link #GENESIS}.
   *
   * @param events the events as read back from storage
   * @param verifier when present, every event must carry a valid {@code chain.sig}
   */
  public static ChainVerification verify(List<AuditEvent> events, Optional<Verifier> verifier) {
    Objects.requireNonNull(events, "events");
    Objects.requireNonNull(verifier, "verifier");
    long expectedSeq = -1;
    String expectedPrev = GENESIS;
    int checked = 0;
    for (AuditEvent event : events) {
      Map<String, String> attrs = event.attributes();
      String seqText = attrs.get(SEQ);
      String prev = attrs.get(PREV);
      String hash = attrs.get(HASH);
      long seqForReport = expectedSeq < 0 ? parseSeq(seqText, 1) : expectedSeq;
      if (seqText == null || prev == null || hash == null) {
        return ChainVerification.broken(seqForReport, checked, "missing chain attributes");
      }
      long seq = parseSeq(seqText, -1);
      if (seq < 1) {
        return ChainVerification.broken(
            seqForReport, checked, "invalid chain.seq '" + Text.bounded(seqText, 32) + "'");
      }
      if (expectedSeq < 0) {
        expectedSeq = seq;
        expectedPrev = seq == 1 ? GENESIS : prev;
      }
      if (seq != expectedSeq) {
        return ChainVerification.broken(
            expectedSeq, checked, "expected seq " + expectedSeq + " but found " + seq);
      }
      if (!prev.equals(expectedPrev)) {
        return ChainVerification.broken(seq, checked, "chain.prev does not match previous hash");
      }
      String recomputed = sha256Hex(prev + canonical(event));
      if (!recomputed.equals(hash)) {
        return ChainVerification.broken(seq, checked, "chain.hash does not match event content");
      }
      if (verifier.isPresent()) {
        String sig = attrs.get(SIG);
        if (sig == null) {
          String why = attrs.containsKey(SIG_ERROR) ? "signer failed when writing" : "missing";
          return ChainVerification.broken(seq, checked, "missing chain.sig (" + why + ")");
        }
        byte[] raw;
        try {
          raw = Base64.getDecoder().decode(sig);
        } catch (IllegalArgumentException e) {
          return ChainVerification.broken(seq, checked, "chain.sig is not Base64");
        }
        if (!verifier.get().verify(utf8(hash), raw)) {
          return ChainVerification.broken(seq, checked, "signature invalid");
        }
      }
      checked++;
      expectedSeq = seq + 1;
      expectedPrev = hash;
    }
    return ChainVerification.intact(checked, verifier.isPresent());
  }

  /**
   * The canonical serialization that is hashed: a flat JSON object in sorted key order with every
   * top-level field under its own name and every non-chain attribute under {@code attr.<key>}.
   */
  public static String canonical(AuditEvent event) {
    Map<String, String> flat = new TreeMap<>();
    flat.put("id", event.id().toString());
    flat.put("createdAt", event.createdAt().toString());
    flat.put("actorId", event.actorId());
    flat.put("actionType", event.actionType());
    flat.put("targetType", event.targetType());
    flat.put("targetId", event.targetId());
    flat.put("outcome", event.outcome().name());
    if (event.detail() != null) {
      flat.put("detail", event.detail());
    }
    flat.put("runId", event.context().runId());
    putIfPresent(flat, "tenantId", event.context().tenantId());
    putIfPresent(flat, "agentId", event.context().agentId());
    putIfPresent(flat, "sessionId", event.context().sessionId());
    putIfPresent(flat, "correlationId", event.context().correlationId());
    if (!event.risks().isEmpty()) {
      flat.put(
          "risks",
          event.risks().stream()
              .map(r -> r.id())
              .sorted()
              .reduce((a, b) -> a + "," + b)
              .orElse(""));
    }
    for (Map.Entry<String, String> e : event.attributes().entrySet()) {
      if (!CHAIN_KEYS.contains(e.getKey())) {
        flat.put(ATTR_PREFIX + e.getKey(), e.getValue());
      }
    }
    return JsonLite.writeObject(flat);
  }

  private static void putIfPresent(Map<String, String> map, String key, @Nullable String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  static String sha256Hex(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(utf8(text));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static long parseSeq(@Nullable String text, long fallback) {
    if (text == null) {
      return fallback;
    }
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /** Same id, timestamp and content; only the attributes differ. */
  static AuditEvent withAttributes(AuditEvent event, Map<String, String> attributes) {
    return new AuditEvent(
        event.id(),
        event.createdAt(),
        event.actorId(),
        event.actionType(),
        event.targetType(),
        event.targetId(),
        event.outcome(),
        event.detail(),
        event.context(),
        event.risks(),
        attributes);
  }
}
