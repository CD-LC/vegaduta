package com.agenticai.core.agents.tools.stdiosandbox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Everything the sandbox decision needs to know about the host, behind one seam so the decision
 * logic is unit-testable on any OS without installing bubblewrap or Docker.
 */
interface HostProbe {

    /** Lower-case {@code os.name} family: {@code mac}, {@code linux}, {@code windows}, other. */
    String os();

    /** Absolute path of an executable found on PATH. */
    Optional<String> which(String name);

    /** Whether an absolute path is an executable regular file. */
    boolean executable(String absolutePath);

    /**
     * Runs {@code argv} (no shell) with all output discarded; true only for exit code 0 within the
     * timeout.
     */
    boolean runsOk(List<String> argv, int timeoutSeconds);

    Path home();

    /** The directories on PATH, in order. */
    List<Path> pathDirs();

    /** {@code uid:gid} of this process on POSIX, else null. */
    String uidGid();

    /** The real host. */
    final class Real implements HostProbe {

        @Override
        public String os() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (name.contains("mac") || name.contains("darwin")) {
                return "mac";
            }
            if (name.contains("win")) {
                return "windows";
            }
            return name.contains("linux") ? "linux" : name;
        }

        @Override
        public Optional<String> which(String name) {
            for (Path dir : pathDirs()) {
                try {
                    Path candidate = dir.resolve(name);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return Optional.of(candidate.toAbsolutePath().normalize().toString());
                    }
                } catch (InvalidPathException ignored) {
                    // a malformed PATH entry is skipped
                }
            }
            return Optional.empty();
        }

        @Override
        public boolean executable(String absolutePath) {
            try {
                Path p = Path.of(absolutePath);
                return Files.isRegularFile(p) && Files.isExecutable(p);
            } catch (InvalidPathException e) {
                return false;
            }
        }

        @Override
        public boolean runsOk(List<String> argv, int timeoutSeconds) {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = null;
            try {
                p = pb.start();
                if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return false;
                }
                return p.exitValue() == 0;
            } catch (java.io.IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (p != null) {
                    p.destroyForcibly();
                }
                return false;
            }
        }

        @Override
        public Path home() {
            String h = System.getProperty("user.home");
            return h == null || h.isBlank() ? null : Path.of(h);
        }

        @Override
        public List<Path> pathDirs() {
            String path = System.getenv("PATH");
            if (path == null || path.isBlank()) {
                return List.of();
            }
            java.util.ArrayList<Path> out = new java.util.ArrayList<>();
            for (String d : path.split(File.pathSeparator)) {
                if (!d.isBlank()) {
                    try {
                        out.add(Path.of(d));
                    } catch (InvalidPathException ignored) {
                        // skipped
                    }
                }
            }
            return out;
        }

        @Override
        public String uidGid() {
            try {
                Object uid = Files.getAttribute(Path.of("/proc/self"), "unix:uid");
                Object gid = Files.getAttribute(Path.of("/proc/self"), "unix:gid");
                return uid + ":" + gid;
            } catch (Exception e) {
                // not Linux /proc; try the home directory owner on macOS
            }
            try {
                Path h = home();
                if (h == null) {
                    return null;
                }
                return Files.getAttribute(h, "unix:uid") + ":" + Files.getAttribute(h, "unix:gid");
            } catch (Exception e) {
                return null;
            }
        }
    }
}
