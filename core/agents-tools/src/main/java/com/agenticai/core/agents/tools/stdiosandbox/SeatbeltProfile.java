package com.agenticai.core.agents.tools.stdiosandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates the macOS {@code sandbox-exec} (Seatbelt / SBPL) profile for one STDIO MCP child.
 *
 * <p>Deny-default. The model is "the filesystem is read-only to the child, except its own data
 * directory": reads are allowed broadly (a node/python runtime needs its toolchain, the dyld shared
 * cache, locale and certificate data, and enumerating those precisely is brittle across macOS
 * releases) and then the credential stores are carved back out, last rule winning:
 * {@code ~/.ssh}, {@code ~/.aws}, keychains, browser profiles and so on. Writes are allowed only
 * under the data directory (plus {@code /dev/null}). Network is off unless asked for, and when it
 * is asked for it is OUTBOUND TCP only (plus UDP/53 and the mDNSResponder socket for name
 * resolution): no unix-socket connects, so the Docker Desktop, ssh-agent, gpg-agent and password
 * manager sockets stay out of reach even though their directories are only read-denied, and
 * loopback is refused unless {@code allowLoopback} (a hostile package must not reach services
 * published on 127.0.0.1, such as the platform's own database). Mach IPC is a
 * short allowlist that excludes the keychain/security daemons, so denying the keychain FILES is not
 * undone by asking {@code securityd}.
 *
 * <p><b>Known limit.</b> The read policy is a deny-list over a broad {@code (allow file-read*)}:
 * anything not named in {@link #HOME_DENY_READ}, {@link #SYSTEM_DENY_READ} or the operator's
 * {@code sandbox-extra-deny-read} (for example {@code ~/Documents}, {@code ~/Desktop}, project
 * {@code .env} files) is readable by the child, and a malicious server can hand what it reads back
 * to the model in a tool result even with the network off. Add such paths to {@code
 * app.mcp.stdio.sandbox-extra-deny-read}, or use {@code sandbox=container}, which exposes only the
 * data directory.
 *
 * <p>Pure and deterministic (golden-file tested): same inputs, same text. The profile is passed
 * inline with {@code sandbox-exec -p}, so there is no profile file to leak or race. Inputs are
 * validated, not trusted: a path with a control character is refused, quotes and backslashes are
 * escaped, and no server name is ever rendered.
 */
public final class SeatbeltProfile {

    /** Home-relative credential and browser-profile locations denied for reading. */
    static final List<String> HOME_DENY_READ =
            List.of(
                    ".ssh",
                    ".aws",
                    ".azure",
                    ".gnupg",
                    ".kube",
                    ".docker",
                    ".config/gcloud",
                    ".config/gh",
                    ".config/google-chrome",
                    ".config/chromium",
                    ".mozilla",
                    ".netrc",
                    ".npmrc",
                    ".pypirc",
                    ".git-credentials",
                    ".vegaduta",
                    ".config/op",
                    ".config/1Password",
                    ".claude",
                    ".zsh_history",
                    ".zsh_sessions",
                    ".bash_history",
                    "Library/Keychains",
                    "Library/Cookies",
                    "Library/Safari",
                    "Library/Mail",
                    "Library/Messages",
                    "Library/Application Support/Google",
                    "Library/Application Support/Firefox",
                    "Library/Application Support/BraveSoftware",
                    "Library/Application Support/Microsoft Edge",
                    "Library/Application Support/Chromium",
                    "Library/Application Support/Arc",
                    "Library/Application Support/Vivaldi",
                    "Library/Application Support/com.operasoftware.Opera",
                    // Wholesale: app token stores (Slack, Claude, VS Code, 1Password, ...) live here.
                    "Library/Application Support",
                    "Library/Group Containers",
                    "Library/Containers");

    /** System-wide credential stores denied for reading. */
    static final List<String> SYSTEM_DENY_READ =
            List.of("/Library/Keychains", "/private/etc/ssh");

    /**
     * Mach services the runtimes need: directory/user lookup, logging, preferences, notification
     * center. DNS/TLS services are added only when the network is. Deliberately no {@code
     * com.apple.SecurityServer}/{@code securityd} (keychain).
     */
    static final List<String> MACH_SERVICES =
            List.of(
                    "com.apple.system.opendirectoryd.libinfo",
                    "com.apple.logd",
                    "com.apple.diagnosticd",
                    "com.apple.cfprefsd.daemon",
                    "com.apple.cfprefsd.agent",
                    "com.apple.system.notification_center",
                    "com.apple.system.logger");

    static final List<String> NETWORK_MACH_SERVICES =
            List.of(
                    "com.apple.dnssd.service",
                    "com.apple.SystemConfiguration.DNSConfiguration",
                    "com.apple.SystemConfiguration.configd",
                    "com.apple.trustd",
                    "com.apple.trustd.agent",
                    "com.apple.networkd",
                    "com.apple.nsurlsessiond");

    private SeatbeltProfile() {}

    /**
     * @param dataDir the only writable directory (a real path - Seatbelt matches resolved paths)
     * @param home the host user's home, whose credential locations are masked; may be null
     * @param extraDenyRead operator-configured additional absolute paths to mask
     * @param network allow outbound TCP (and name resolution)
     */
    public static String render(Path dataDir, Path home, List<Path> extraDenyRead, boolean network) {
        return render(dataDir, home, extraDenyRead, network, false);
    }

    /**
     * @param allowLoopback with {@code network}, also allow connections to 127.0.0.1 / ::1 (a
     *     server that talks to a local daemon). Default off: loopback carries the platform's own
     *     Postgres and Valkey in native mode.
     */
    public static String render(
            Path dataDir, Path home, List<Path> extraDenyRead, boolean network, boolean allowLoopback) {
        String data = SandboxText.requirePath(dataDir, "data directory");

        Set<String> deny = new LinkedHashSet<>();
        if (home != null && home.isAbsolute() && home.getNameCount() > 0) {
            String h = SandboxText.requirePath(home, "home directory");
            for (String rel : HOME_DENY_READ) {
                deny.add(h + "/" + rel);
            }
        }
        deny.addAll(SYSTEM_DENY_READ);
        for (Path p : extraDenyRead == null ? List.<Path>of() : extraDenyRead) {
            deny.add(SandboxText.requirePath(p, "extra deny-read path"));
        }

        List<String> lines = new ArrayList<>();
        lines.add("(version 1)");
        lines.add(";; VegaDuta MCP STDIO sandbox: deny by default, read-only except the data dir");
        lines.add("(deny default)");
        lines.add("(allow process-exec)");
        lines.add("(allow process-fork)");
        lines.add("(allow signal (target same-sandbox))");
        lines.add("(allow process-info* (target self))");
        lines.add("(allow sysctl-read)");
        lines.add("(allow ipc-posix-shm-read-data)");
        lines.add("(allow file-read*)");
        for (String d : deny) {
            lines.add("(deny file-read* (subpath " + SandboxText.sbplString(d) + "))");
        }
        // The data dir usually lives under a denied parent (~/.vegaduta); later rules win.
        lines.add("(allow file-read* (subpath " + SandboxText.sbplString(data) + "))");
        lines.add("(allow file-write* (literal \"/dev/null\") (literal \"/dev/dtracehelper\"))");
        lines.add("(allow file-write* (subpath " + SandboxText.sbplString(data) + "))");

        StringBuilder mach = new StringBuilder("(allow mach-lookup");
        for (String s : MACH_SERVICES) {
            mach.append(" (global-name ").append(SandboxText.sbplString(s)).append(')');
        }
        if (network) {
            for (String s : NETWORK_MACH_SERVICES) {
                mach.append(" (global-name ").append(SandboxText.sbplString(s)).append(')');
            }
        }
        lines.add(mach.append(')').toString());

        if (network) {
            lines.add("(allow system-socket)");
            lines.add("(allow network-outbound (remote tcp))");
            lines.add("(allow network-outbound (remote udp \"*:53\"))");
            lines.add("(allow network-outbound (literal \"/private/var/run/mDNSResponder\"))");
            if (!allowLoopback) {
                lines.add("(deny network-outbound (remote ip \"localhost:*\"))");
            }
        }
        return String.join("\n", lines) + "\n";
    }
}
