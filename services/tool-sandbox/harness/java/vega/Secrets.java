package vega;

import java.util.Collections;
import java.util.Map;

/**
 * Read-only access to the vault secrets this tool was granted.
 *
 * <p>Usage from a tool:
 *
 * <pre>{@code
 * public class Tool {
 *     public static Object run(java.util.Map<String, Object> input) {
 *         String key = vega.Secrets.get("STRIPE_KEY");
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <p><b>Why this class exists at all.</b> A tool cannot call
 * {@code System.getenv}: the bytecode verifier denies {@code java.lang.System}
 * outright, and its own comment calls that class "the door to getenv". That
 * denial is load-bearing and is not going to be relaxed. So the trusted harness
 * reads the environment on the tool's behalf, before any tenant bytecode runs,
 * and publishes the result here. {@code vega.} is already an allowed package
 * prefix for tool code (see {@code policy.JAVA_ALLOWED_PREFIXES}), so referring
 * to this class needs no change to the policy.
 *
 * <p><b>What a tool can and cannot do with it.</b> It can read the values it was
 * granted - those are its own tenant's credentials, deliberately handed over.
 * It cannot enumerate anything it was not granted: the map is populated only
 * from the names the tool declared in its allow-list, resolved by core before
 * the process started, so there is no read-across to another tool's or another
 * member's secrets.
 *
 * <p><b>{@link #populate} is public and IS callable by tool code.</b> An earlier
 * version of this comment claimed it was "package-private to
 * {@code vega.internal} in effect", which was wrong on three counts: it is
 * declared {@code public static}, this class ships in the tenant-facing
 * {@code vega-api.jar} (so it is on the tool's compile classpath), and
 * {@code policy.JAVA_ALLOWED_PREFIXES} permits {@code vega.} for any member
 * name. Nobody should reason from that false premise, so here is the real
 * argument:
 *
 * <ul>
 *   <li>{@code populate} can succeed at most <b>once per JVM</b> (see
 *       {@code sealed} below), and the harness calls it before
 *       {@code Class.forName} loads any tool bytecode. A tool therefore cannot
 *       overwrite, clear, or re-point the map.</li>
 *   <li>A tool granted nothing can call it and plant its own values - which are
 *       visible only to itself, in its own single-use process. That is not a
 *       disclosure of anything.</li>
 *   <li>The mutable state is never exposed: the stored map is unmodifiable and
 *       {@link #names()} returns its key set, not the values.</li>
 * </ul>
 *
 * <p>If a second {@code populate} call, a reset, or a per-invocation refresh is
 * ever added, the first bullet stops holding and this class must move its
 * mutable half into {@code vega.internal} (which is deliberately kept off the
 * tenant compile classpath) with this class left as a read-only facade.
 */
public final class Secrets {

    private static Map<String, String> values = Collections.emptyMap();

    /**
     * Separate from {@code values.isEmpty()} on purpose. Keying "already
     * populated" off emptiness would leave the door open for a tool that was
     * granted nothing: the harness would populate an empty map, the seal would
     * not engage, and the tool could then call {@code populate} itself. Harmless
     * today - it could only plant values for its own process - but it makes the
     * "at most once" guarantee conditional on the grant list, which is a
     * fragile thing for the argument above to rest on.
     */
    private static boolean sealed;

    private Secrets() {
    }

    /**
     * Called exactly once by the harness before any tool bytecode is loaded.
     * Every later call is ignored rather than throwing, so a tool that reaches
     * this method cannot blank the map out from under itself mid-run or plant
     * values for code that runs after it.
     */
    public static synchronized void populate(Map<String, String> resolved) {
        if (sealed) {
            return;
        }
        sealed = true;
        values = resolved == null ? Collections.emptyMap() : Collections.unmodifiableMap(resolved);
    }

    /**
     * The value granted under {@code name}, or null when this tool was not
     * granted it (or the vault could not resolve it - expired, revoked, or
     * never stored).
     *
     * <p>Null rather than an empty string on purpose: several services treat an
     * empty credential as anonymous access, so an empty string would turn a
     * missing secret into a confusing authorization result instead of an
     * obvious {@code NullPointerException} at the point of use.
     */
    public static String get(String name) {
        return values.get(name);
    }

    /** True when this tool was granted {@code name} and it resolved. */
    public static boolean has(String name) {
        return values.containsKey(name);
    }

    /**
     * The names available to this tool - never the values. Useful for a tool
     * that wants to fail early with a clear message about what it is missing.
     */
    public static java.util.Set<String> names() {
        return values.keySet();
    }
}
