package com.agenticai.core.agents.tools.stdiosandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Argument builders for the Linux mechanisms. Pure: they return an argv, never run anything, and
 * never build a shell string (the real command follows a literal {@code --}, as separate
 * arguments, so no value is ever re-parsed).
 */
final class LinuxLaunchers {

    private LinuxLaunchers() {}

    /**
     * bubblewrap: read-only view of the host root, the home directory masked by an empty tmpfs
     * (so {@code ~/.ssh}, {@code ~/.aws}, browser profiles are simply absent), {@code /run} masked
     * (docker/dbus sockets), fresh {@code /dev} {@code /proc} {@code /tmp}, all namespaces
     * unshared (network only when allowed), capabilities dropped, killed with its parent. The data
     * dir is the single writable bind.
     *
     * @param bwrap absolute path to the bwrap binary
     * @param home host home to mask, or null
     * @param readOnlyUnderHome toolchain directories under {@code home} to re-expose read-only
     *     (they would otherwise vanish with the mask)
     */
    static List<String> bubblewrap(
            String bwrap,
            SandboxRequest req,
            Path home,
            List<Path> readOnlyUnderHome,
            boolean resolverUnderRun) {
        return bubblewrap(bwrap, req, home, readOnlyUnderHome, resolverUnderRun, null);
    }

    /**
     * @param privateRoot the shared cache base holding every registration's data directory, hidden
     *     by an empty tmpfs unless it already sits under the masked home; the data dir is bound
     *     back in afterwards. May be null.
     */
    static List<String> bubblewrap(
            String bwrap,
            SandboxRequest req,
            Path home,
            List<Path> readOnlyUnderHome,
            boolean resolverUnderRun,
            Path privateRoot) {
        return bubblewrap(bwrap, req, home, readOnlyUnderHome, resolverUnderRun, privateRoot, List.of());
    }

    /**
     * @param denyRead existing absolute paths hidden from the child: a directory is replaced by an
     *     empty tmpfs, a file by {@code /dev/null}. This is how a checkout or data directory
     *     outside the masked home (/opt, /srv, /data) is kept unreadable.
     */
    static List<String> bubblewrap(
            String bwrap,
            SandboxRequest req,
            Path home,
            List<Path> readOnlyUnderHome,
            boolean resolverUnderRun,
            Path privateRoot,
            List<Path> denyRead) {
        String data = SandboxText.requirePath(req.dataDir(), "data directory");
        List<String> a = new ArrayList<>();
        a.add(bwrap);
        a.add("--die-with-parent");
        a.add("--new-session");
        a.add("--unshare-user");
        a.add("--unshare-pid");
        a.add("--unshare-ipc");
        a.add("--unshare-uts");
        a.add("--unshare-cgroup-try");
        if (req.networkAccess()) {
            a.add("--share-net");
        } else {
            a.add("--unshare-net");
        }
        a.add("--cap-drop");
        a.add("ALL");
        a.add("--ro-bind");
        a.add("/");
        a.add("/");
        a.add("--dev");
        a.add("/dev");
        a.add("--proc");
        a.add("/proc");
        a.add("--tmpfs");
        a.add("/tmp");
        a.add("--tmpfs");
        a.add("/run");
        if (req.networkAccess() && resolverUnderRun) {
            // /etc/resolv.conf is commonly a symlink into /run/systemd/resolve.
            a.add("--ro-bind-try");
            a.add("/run/systemd/resolve");
            a.add("/run/systemd/resolve");
        }
        if (home != null && home.isAbsolute() && home.getNameCount() > 0) {
            String h = SandboxText.requirePath(home, "home directory");
            a.add("--tmpfs");
            a.add(h);
            for (Path p : readOnlyUnderHome) {
                String s = SandboxText.requirePath(p, "read-only path");
                a.add("--ro-bind-try");
                a.add(s);
                a.add(s);
            }
        }
        if (privateRoot != null && privateRoot.isAbsolute()) {
            String root = SandboxText.requirePath(privateRoot, "private root");
            boolean underHome = home != null && home.isAbsolute() && privateRoot.normalize().startsWith(home.normalize());
            if (!underHome && data.startsWith(root + "/")) {
                a.add("--tmpfs");
                a.add(root);
            }
        }
        for (Path p : denyRead == null ? List.<Path>of() : denyRead) {
            String d = SandboxText.requirePath(p, "deny-read path");
            // A denied directory that contains the data dir is fine: the data dir is bound back below.
            if (java.nio.file.Files.isDirectory(p)) {
                a.add("--tmpfs");
                a.add(d);
            } else {
                a.add("--ro-bind");
                a.add("/dev/null");
                a.add(d);
            }
        }
        a.add("--bind");
        a.add(data);
        a.add(data);
        a.add("--chdir");
        a.add(data);
        a.add("--");
        a.add(req.command());
        a.addAll(req.args());
        return List.copyOf(a);
    }

    /**
     * Degraded fallback when bubblewrap is absent: PID/IPC/UTS (and, unless allowed, network)
     * namespaces inside a user namespace, killed with the parent. It does NOT confine the
     * filesystem - callers must surface that.
     */
    static List<String> unshare(String unshare, SandboxRequest req) {
        List<String> a = new ArrayList<>();
        a.add(unshare);
        a.add("--user");
        a.add("--map-root-user");
        a.add("--pid");
        a.add("--fork");
        a.add("--kill-child");
        a.add("--ipc");
        a.add("--uts");
        if (!req.networkAccess()) {
            a.add("--net");
        }
        a.add("--");
        a.add(req.command());
        a.addAll(req.args());
        return List.copyOf(a);
    }
}
