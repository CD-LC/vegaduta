package com.agenticai.core.sdlc.harness;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

/**
 * The extra conditions {@link LocalCodingSandbox} needs beyond {@code app.coding-harness.runner=local}
 * and {@code allow-local-runner=true}. The local runner executes tenant-influenced shell commands on
 * the machine hosting core with no isolation at all, so it is only acceptable for one developer on
 * their own machine:
 *
 * <ol>
 *   <li>a SECOND, separately named acknowledgement, {@code
 *       app.coding-harness.acknowledge-local-runner-risk=true} - so a copied {@code
 *       allow-local-runner=true} line alone never arms it;
 *   <li>core must be bound to a loopback address ({@code server.address=127.0.0.1}, {@code ::1} or
 *       {@code localhost}). An unset {@code server.address} means "all interfaces", which is NOT
 *       loopback: anything on the network could then reach the endpoint that starts coding tasks.
 * </ol>
 *
 * (The deployment-profile refusal stays in {@link CodingSandboxFactory}.)
 */
final class LocalRunnerGate {

    static final String ACK_PROPERTY = "app.coding-harness.acknowledge-local-runner-risk";

    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

    private LocalRunnerGate() {}

    /** Empty when the local runner may open; otherwise a user-safe reason. */
    static Optional<String> refusal(Environment env) {
        if (!Boolean.parseBoolean(env.getProperty(ACK_PROPERTY, "false"))) {
            return Optional.of(
                    "The local runner needs an explicit acknowledgement: set "
                            + ACK_PROPERTY
                            + "=true (it runs commands on the core host with no isolation).");
        }
        String address = env.getProperty("server.address");
        if (!isLoopback(address)) {
            return Optional.of(
                    "The local runner is refused because core is not bound to a loopback address "
                            + "(server.address is "
                            + (address == null || address.isBlank() ? "unset, i.e. all interfaces" : "not loopback")
                            + "). Set server.address=127.0.0.1.");
        }
        return Optional.empty();
    }

    /**
     * Loopback only: {@code localhost} or an IP literal in 127.0.0.0/8 / {@code ::1}. A hostname
     * other than localhost is not resolved (no DNS from a security check) and counts as non-loopback.
     */
    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String a = address.strip();
        if (a.startsWith("[") && a.endsWith("]")) {
            a = a.substring(1, a.length() - 1);
        }
        if (a.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (!IPV4.matcher(a).matches() && !IPV6.matcher(a).matches()) {
            return false;
        }
        try {
            return InetAddress.getByName(a.toLowerCase(Locale.ROOT)).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** The WARN banner logged whenever the local runner is armed or used. */
    static String banner() {
        return "\n================================================================================\n"
                + "  LOCAL CODING RUNNER ACTIVE (app.coding-harness.runner=local)\n"
                + "  Tenant-influenced shell commands run DIRECTLY on this machine, as this user,\n"
                + "  with NO sandbox: they can read and change anything this user can.\n"
                + "  Development on a loopback-only core ONLY. Use runner=e2b anywhere else.\n"
                + "================================================================================";
    }
}
