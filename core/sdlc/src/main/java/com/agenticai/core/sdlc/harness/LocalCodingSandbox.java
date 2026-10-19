package com.agenticai.core.sdlc.harness;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEVELOPMENT ONLY. Runs the workspace in a temp directory on the machine hosting core, with a
 * cleared environment and the directory as cwd - and nothing else: there is no OS-level isolation,
 * so a test command can read anything this process can. It is therefore never constructed unless
 * {@code app.coding-harness.allow-local-runner=true}, and the factory refuses it under the
 * {@code security}/{@code prod}/{@code production} Spring profiles.
 *
 * <p>File confinement is still enforced: archive entries are re-checked against the resolved target
 * path before anything is written, and the restricted handle applies the same command allowlist as
 * the E2B one.
 *
 * <p>The workspace and scratch HOME are never created in the shared, world-writable system temp
 * directory: they live under a private, owner-only parent ({@code app.coding-harness.local-workspace-dir},
 * default {@code ~/.vegaduta/coding-sandbox}) so another local user can neither pre-create, read nor
 * swap them (java:S5443).
 */
public final class LocalCodingSandbox extends AbstractCodingSandbox {

    private static final Logger log = LoggerFactory.getLogger(LocalCodingSandbox.class);
    private static final int MAX_OUTPUT_BYTES = 1_000_000;
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

    private final Path root;
    /** Scratch HOME and command scripts; outside the workspace so they never reach a checkpoint. */
    private final Path home;
    private final CodingCommandPolicy policy;
    private final String path;
    private final String bash;
    private boolean closed;

    public LocalCodingSandbox(CodingCommandPolicy policy) {
        this(policy, "bash");
    }

    /** {@code bash} is the executable to start (a dev box may need an absolute path, e.g. Git Bash on Windows). */
    public LocalCodingSandbox(CodingCommandPolicy policy, String bash) {
        this(policy, bash, null);
    }

    /**
     * @param workspaceParent private directory the workspace and scratch HOME are created under; {@code null}
     *     = {@link #defaultWorkspaceParent()}. It is created if missing and forced to owner-only permissions.
     */
    public LocalCodingSandbox(CodingCommandPolicy policy, String bash, Path workspaceParent) {
        this.bash = bash == null || bash.isBlank() ? "bash" : bash;
        try {
            Path parent = privateParent(workspaceParent);
            this.root = Files.createTempDirectory(parent, "vd-coding-", ownerOnly()).toRealPath();
            this.home = Files.createTempDirectory(parent, "vd-home-", ownerOnly()).toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Could not create a local workspace.", e);
        }
        this.policy = policy;
        String hostPath = System.getenv("PATH");
        this.path = hostPath == null ? "/usr/local/bin:/usr/bin:/bin" : hostPath;
    }

    /**
     * Parent used when the caller passes none: {@code CODING_HARNESS_LOCAL_WORKSPACE_DIR} (the environment
     * variable {@code app.coding-harness.local-workspace-dir} binds) when set, else {@code ~/.vegaduta/coding-sandbox}
     * inside the user's home - never the shared system temp root. Read here, like {@code PATH} is, so the setting
     * is honoured even when the factory does not pass it through.
     */
    static Path defaultWorkspaceParent() {
        String configured = System.getenv("CODING_HARNESS_LOCAL_WORKSPACE_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        return Path.of(System.getProperty("user.home"), ".vegaduta", "coding-sandbox");
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static FileAttribute<?>[] ownerOnly() {
        return posix()
            ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)}
            : new FileAttribute<?>[0];
    }

    /**
     * Resolves (creating if needed) the owner-only parent directory. On POSIX the parent's permissions are
     * forced to {@code rwx------} and it must be owned by the current user, so a pre-planted directory or
     * symlink of another local user is refused; elsewhere (Windows) the per-user home is relied on.
     */
    private static Path privateParent(Path configured) throws IOException {
        Path parent = (configured == null ? defaultWorkspaceParent() : configured).toAbsolutePath().normalize();
        Files.createDirectories(parent, ownerOnly());
        Path real = parent.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new IOException("Not a directory: " + parent);
        }
        if (posix()) {
            UserPrincipal owner = Files.getOwner(real);
            UserPrincipal self = currentUser();
            if (self != null && !owner.equals(self)) {
                throw new IOException("Refusing sandbox parent " + real + ": owned by " + owner.getName()
                    + ", not " + self.getName());
            }
            if (!OWNER_ONLY.equals(Files.getPosixFilePermissions(real))) {
                Files.setPosixFilePermissions(real, OWNER_ONLY);
            }
        }
        return real;
    }

    /** The process user, or {@code null} when it has no passwd entry (a dev container with an arbitrary uid). */
    private static UserPrincipal currentUser() throws IOException {
        try {
            return FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        } catch (UserPrincipalNotFoundException e) {
            return null;
        }
    }

    public Path root() {
        return root;
    }

    @Override
    public ShellRunner internal() {
        return (command, timeoutSeconds) -> exec(command, timeoutSeconds);
    }

    @Override
    public ShellRunner restricted() {
        return (command, timeoutSeconds) -> exec(policy.validate(command), timeoutSeconds);
    }

    @Override
    public void seedFromArchive(byte[] sanitizedZip) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(sanitizedZip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IllegalArgumentException("Archive entry escapes the workspace: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                if (!target.getParent().toRealPath().startsWith(root)) {
                    throw new IllegalArgumentException("Archive entry escapes the workspace: " + entry.getName());
                }
                Files.write(target, zip.readAllBytes());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not unpack the repository: " + e.getMessage(), e);
        }
    }

    @Override
    public String describe() {
        return "local workspace " + root;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Path dir : new Path[] {root, home}) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            } catch (IOException | RuntimeException e) {
                log.warn("Could not remove {}: {}", dir, e.getMessage());
            }
        }
    }

    private ShellRunner.ShellResult exec(String command, int timeoutSeconds) {
        if (closed) {
            throw new IllegalStateException("The sandbox is closed.");
        }
        Path script;
        try {
            // The command goes in a script file, not on the command line: no quoting rule of the host OS applies.
            script = Files.createTempFile(home, "cmd-", ".sh");
            Files.writeString(script, command + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new ShellRunner.ShellResult(127, "[harness] could not prepare the command: " + e.getMessage());
        }
        ProcessBuilder pb = new ProcessBuilder(bash, "--noprofile", "--norc", forwardSlashes(script));
        pb.directory(root.toFile());
        pb.redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().put("PATH", path);
        pb.environment().put("HOME", forwardSlashes(home));
        pb.environment().put("LANG", "C.UTF-8");
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            // A Windows dev box cannot start bash/git with a fully empty environment. Only these two, no secrets.
            for (String name : new String[] {"SystemRoot", "TEMP"}) {
                String value = System.getenv(name);
                if (value != null) {
                    pb.environment().put(name, value);
                }
            }
        }
        try {
            Process process = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread drain = TenantAwareExecutors.startVirtualThread("local-sandbox-drain", () -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        synchronized (out) {
                            if (out.size() < MAX_OUTPUT_BYTES) {
                                out.write(buf, 0, Math.min(n, MAX_OUTPUT_BYTES - out.size()));
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            });
            if (!process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                drain.join(2000);
                return new ShellRunner.ShellResult(124, text(out) + "\n[harness] command timed out");
            }
            drain.join(2000);
            return new ShellRunner.ShellResult(process.exitValue(), text(out));
        } catch (IOException e) {
            return new ShellRunner.ShellResult(127, "[harness] could not start bash: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ShellRunner.ShellResult(130, "[harness] interrupted");
        } finally {
            try {
                Files.deleteIfExists(script);
            } catch (IOException ignored) {
                // removed with the home directory on close
            }
        }
    }

    private static String forwardSlashes(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static String text(ByteArrayOutputStream out) {
        synchronized (out) {
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
