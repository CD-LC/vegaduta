package io.vegaduta.agent.core.provenance;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.core.audit.LoggingAuditSink;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ToolDispatchGate} that refuses to run tools that drifted from the manifest the agent was
 * approved against (OWASP ASI04).
 *
 * <p>On every call the gate asks the {@code current} supplier for the manifest the agent is being
 * offered right now. The comparison with the pin is cached by manifest digest and by the store's
 * {@link ManifestPinStore#version pin version}: as long as neither changes the per-call cost is one
 * digest comparison and one set lookup. When the manifest digest changes (a server reconnected, a
 * package updated) or an operator writes a reviewed pin, the pin is reloaded and the drift
 * recomputed. A store without versions is re-read every {@value #RECHECK_MILLIS} ms while the
 * cached comparison is denying calls, so a committed lockfile takes effect without a restart.
 *
 * <p>Fails closed: when the supplier or the store throws (duplicate tool names, an unreadable
 * lockfile) the call is denied in {@link ProvenanceMode#ENFORCE} and allowed with a warning in
 * {@link ProvenanceMode#WARN}, audited as {@code tool.drift} with reason {@link
 * #REASON_PROVENANCE_ERROR}; the error is logged once per occurrence streak, not per call.
 *
 * <ul>
 *   <li>No pin for the key yet and {@code pinOnFirstUse}: the current manifest becomes the pin and
 *       a {@code tool.manifest.pinned} event is emitted. Descriptions are still scanned, so a tool
 *       that arrives already poisoned is flagged.
 *   <li>No pin and not {@code pinOnFirstUse}: every call is treated as drift ({@link
 *       #REASON_UNPINNED}).
 *   <li>Pin present and the called tool was added, changed, or has a suspicious description: in
 *       {@link ProvenanceMode#ENFORCE} the call is denied ({@link #REASON_TOOL_DRIFT}) and a {@code
 *       tool.drift} event with outcome {@code DENIED} is emitted; in {@link ProvenanceMode#WARN}
 *       the call is allowed and the same event is emitted with outcome {@code WARNING}.
 *   <li>Everything else: {@code ALLOW}. This gate says nothing about risk or approval; chain it in
 *       front of the policy gate with {@link #then(ToolDispatchGate)}.
 * </ul>
 *
 * <p>Prefer supplying a {@link ToolManifest} the host caches over a descriptor collection: building
 * a manifest hashes every tool.
 */
@Experimental
public final class ProvenanceGate implements ToolDispatchGate {

  /** Action type of the per-call drift event. */
  public static final String ACTION_TOOL_DRIFT = "tool.drift";

  /** Action type of the once-per-manifest-version summary event. */
  public static final String ACTION_MANIFEST_DRIFT = "tool.manifest.drift";

  /** Action type emitted when a pin is written on first use or by {@link #repin()}. */
  public static final String ACTION_MANIFEST_PINNED = "tool.manifest.pinned";

  public static final String REASON_TOOL_DRIFT = "tool_drift";
  public static final String REASON_UNPINNED = "unpinned_manifest";
  public static final String REASON_PROVENANCE_ERROR = "provenance_error";

  /** How long a denying comparison is trusted before the pin is re-read from the store. */
  public static final long RECHECK_MILLIS = 30_000;

  private static final Logger log = LoggerFactory.getLogger(ProvenanceGate.class);

  private final ManifestPinStore store;
  private final String key;
  private final Supplier<ToolManifest> current;
  private final ProvenanceMode mode;
  private final boolean pinOnFirstUse;
  private final AuditSink audit;
  private final ToolDriftDetector detector;
  private final Object lock = new Object();
  private final AtomicBoolean errorLogged = new AtomicBoolean();
  private volatile @Nullable Snapshot snapshot;

  /**
   * The cached comparison for one current-manifest digest and pin version. {@code report} is null
   * when no pin exists and the gate was told not to pin on first use.
   */
  private record Snapshot(
      String currentDigest,
      @Nullable String pinVersion,
      long computedAtNanos,
      @Nullable DriftReport report) {

    boolean denies() {
      return report == null || report.hasDrift();
    }
  }

  private ProvenanceGate(Builder b) {
    this.store = b.store;
    this.key = b.key;
    this.current =
        Objects.requireNonNull(b.current, "a manifest or descriptor supplier is required");
    this.mode = b.mode;
    this.pinOnFirstUse = b.pinOnFirstUse;
    this.audit = b.audit != null ? b.audit : new LoggingAuditSink();
    this.detector = b.detector != null ? b.detector : new ToolDriftDetector();
  }

  public static Builder builder(ManifestPinStore store, String key) {
    return new Builder(store, key);
  }

  @Override
  public Verdict check(ToolCall call, RunContext context) {
    String tool = call.toolName();
    Snapshot snap;
    try {
      ToolManifest manifest = current.get();
      snap = snapshot(manifest, context);
      errorLogged.set(false);
    } catch (RuntimeException e) {
      if (errorLogged.compareAndSet(false, true)) {
        log.warn("provenance: cannot evaluate tool manifest for key {}: {}", key, e.toString());
      }
      return react(
          tool,
          context,
          REASON_PROVENANCE_ERROR,
          "provenance check failed: " + e.getClass().getSimpleName(),
          "error",
          DriftSeverity.HIGH);
    }
    DriftReport report = snap.report();
    if (report == null) {
      return react(
          tool,
          context,
          REASON_UNPINNED,
          "no pinned manifest for key " + key,
          "unpinned",
          DriftSeverity.HIGH);
    }
    if (!report.affects(tool)) {
      return Verdict.allow();
    }
    return react(
        tool,
        context,
        REASON_TOOL_DRIFT,
        "tool drifted from pin "
            + report.pinnedDigest()
            + ": "
            + String.join(",", report.categoriesFor(tool)),
        String.join(",", report.categoriesFor(tool)),
        report.severityFor(tool));
  }

  /** Runs this gate first and {@code next} only when this gate allows. */
  public ToolDispatchGate then(ToolDispatchGate next) {
    Objects.requireNonNull(next, "next");
    return (call, context) -> {
      Verdict v = check(call, context);
      return v.allowed() ? next.check(call, context) : v;
    };
  }

  /** The most recent comparison, if one has been made and a pin existed. */
  public Optional<DriftReport> lastReport() {
    Snapshot s = snapshot;
    return s == null ? Optional.empty() : Optional.ofNullable(s.report());
  }

  /** Drops the cached comparison; the next call reloads the pin. */
  public void refresh() {
    snapshot = null;
  }

  /**
   * Accepts the current manifest as the new pin (after a human reviewed the drift) and drops the
   * cache.
   *
   * @return the manifest that was pinned
   */
  public ToolManifest repin() {
    ToolManifest manifest = current.get();
    synchronized (lock) {
      store.save(key, manifest);
      snapshot = null;
    }
    emit(
        AuditEvent.builder(ACTION_MANIFEST_PINNED, "tool_manifest", key)
            .outcome(AuditOutcome.SUCCESS)
            .detail("repinned " + manifest.digest())
            .risk(AsiRisk.ASI04)
            .attribute("manifestDigest", manifest.digest())
            .attribute("toolCount", Integer.toString(manifest.size())));
    return manifest;
  }

  public String key() {
    return key;
  }

  public ProvenanceMode mode() {
    return mode;
  }

  // ---- internals ----------------------------------------------------------------------------

  private Snapshot snapshot(ToolManifest manifest, RunContext context) {
    Snapshot s = snapshot;
    if (s != null && fresh(s, manifest)) {
      return s;
    }
    synchronized (lock) {
      s = snapshot;
      if (s != null && fresh(s, manifest)) {
        return s;
      }
      s = compute(manifest, context);
      snapshot = s;
      return s;
    }
  }

  /**
   * A snapshot is reused while the manifest digest is unchanged and the store's pin version (when
   * it has one) is unchanged; a denying snapshot over a store without versions expires after {@link
   * #RECHECK_MILLIS} so a pin written later is noticed.
   */
  private boolean fresh(Snapshot s, ToolManifest manifest) {
    if (!s.currentDigest().equals(manifest.digest())) {
      return false;
    }
    if (!s.denies()) {
      return true;
    }
    Optional<String> version = store.version(key);
    if (version.isPresent()) {
      return version.get().equals(s.pinVersion());
    }
    return System.nanoTime() - s.computedAtNanos() < RECHECK_MILLIS * 1_000_000L;
  }

  private Snapshot compute(ToolManifest manifest, RunContext context) {
    Optional<ToolManifest> pinned = store.load(key);
    if (pinned.isEmpty()) {
      if (!pinOnFirstUse) {
        log.warn("provenance: no pinned tool manifest for key {} and pinOnFirstUse is off", key);
        return new Snapshot(manifest.digest(), null, System.nanoTime(), null);
      }
      store.save(key, manifest);
      emit(
          AuditEvent.builder(ACTION_MANIFEST_PINNED, "tool_manifest", key)
              .context(context)
              .outcome(AuditOutcome.SUCCESS)
              .detail("pinned on first use: " + manifest.digest())
              .risk(AsiRisk.ASI04)
              .attribute("manifestDigest", manifest.digest())
              .attribute("toolCount", Integer.toString(manifest.size())));
      pinned = Optional.of(manifest);
    }
    DriftReport report = detector.compare(pinned.get(), manifest);
    if (report.hasDrift()) {
      log.warn("provenance: tool manifest drift for key {}: {}", key, report.summary());
      emit(
          AuditEvent.builder(ACTION_MANIFEST_DRIFT, "tool_manifest", key)
              .context(context)
              .outcome(mode == ProvenanceMode.ENFORCE ? AuditOutcome.DENIED : AuditOutcome.WARNING)
              .detail(report.summary())
              .risk(AsiRisk.ASI04)
              .attribute("severity", report.severity().name())
              .attribute("pinnedDigest", report.pinnedDigest())
              .attribute("currentDigest", report.currentDigest())
              .attribute("affectedTools", String.join(",", report.affectedTools())));
    }
    return new Snapshot(
        manifest.digest(), store.version(key).orElse(null), System.nanoTime(), report);
  }

  private Verdict react(
      String tool,
      RunContext context,
      String reason,
      String detail,
      String categories,
      DriftSeverity severity) {
    boolean deny = mode == ProvenanceMode.ENFORCE;
    emit(
        AuditEvent.builder(ACTION_TOOL_DRIFT, "tool", tool)
            .context(context)
            .outcome(deny ? AuditOutcome.DENIED : AuditOutcome.WARNING)
            .detail(detail)
            .risk(AsiRisk.ASI04)
            .attribute("pinKey", key)
            .attribute("reason", reason)
            .attribute("categories", categories)
            .attribute("severity", severity.name())
            .attribute("mode", mode.name()));
    return deny ? Verdict.deny(reason) : Verdict.allow();
  }

  private void emit(AuditEvent.Builder event) {
    try {
      audit.emit(event.build());
    } catch (RuntimeException e) {
      log.warn("provenance: audit sink failed", e);
    }
  }

  /** Builds a {@link ProvenanceGate}. */
  public static final class Builder {
    private final ManifestPinStore store;
    private final String key;
    private @Nullable Supplier<ToolManifest> current;
    private ProvenanceMode mode = ProvenanceMode.ENFORCE;
    private boolean pinOnFirstUse = true;
    private @Nullable AuditSink audit;
    private @Nullable ToolDriftDetector detector;

    private Builder(ManifestPinStore store, String key) {
      this.store = Objects.requireNonNull(store, "store");
      this.key = Objects.requireNonNull(key, "key");
    }

    /** The manifest the agent is offered now; called on every check, so make it cheap. */
    public Builder manifest(Supplier<ToolManifest> value) {
      this.current = Objects.requireNonNull(value, "manifest");
      return this;
    }

    /** A fixed manifest; for agents whose tool list is built once per process. */
    public Builder manifest(ToolManifest value) {
      Objects.requireNonNull(value, "manifest");
      this.current = () -> value;
      return this;
    }

    /**
     * The descriptors the agent is offered now. The manifest is rebuilt (every tool hashed) on
     * every check; prefer {@link #manifest(Supplier)} with a host-side cache when the list is
     * large.
     */
    public Builder descriptors(Supplier<? extends Collection<ToolDescriptor>> value) {
      Objects.requireNonNull(value, "descriptors");
      this.current = () -> ToolManifest.of(value.get());
      return this;
    }

    /** Default {@link ProvenanceMode#ENFORCE}. */
    public Builder mode(ProvenanceMode value) {
      this.mode = Objects.requireNonNull(value, "mode");
      return this;
    }

    /** Default true: an unpinned key is pinned to the first manifest seen. */
    public Builder pinOnFirstUse(boolean value) {
      this.pinOnFirstUse = value;
      return this;
    }

    /** Default {@link LoggingAuditSink}. */
    public Builder auditSink(AuditSink value) {
      this.audit = Objects.requireNonNull(value, "auditSink");
      return this;
    }

    /** Default: digests plus the built-in description scan. */
    public Builder detector(ToolDriftDetector value) {
      this.detector = Objects.requireNonNull(value, "detector");
      return this;
    }

    public ProvenanceGate build() {
      return new ProvenanceGate(this);
    }
  }
}
