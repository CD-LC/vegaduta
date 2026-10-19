package com.agenticai.core.sdlc.harness;

import com.agenticai.core.sdlc.dto.SandboxFile;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CodingSandbox} on a persistent E2B sandbox. The workspace is a directory on the sandbox's
 * own disk ({@value #WORKSPACE}); every command is one {@code bash} script sent to the template's
 * {@code /run} endpoint (see core/sdlc/e2b-template/runner.py), so nothing ever executes on the
 * core host.
 *
 * <p>Every script starts from a scrubbed environment ({@code env -i} with a fixed PATH and a private
 * HOME): no variable of the template's runner process, and no host secret - none were ever passed
 * in - reaches the command. The restricted handle additionally wraps the command in
 * {@code unshare -rn} (a new, empty network namespace) unless {@code networkEnabled} was set, and in
 * {@code timeout -k}. If the template cannot create a network namespace the command fails closed with
 * the shell's own message rather than running with network.
 *
 * <p>NOT verified against a real sandbox in this repository's tests (E2B is not reachable from CI):
 * the unit tests drive a fake {@link RemoteShell} and assert the exact scripts. The template needs
 * {@code bash}, {@code git}, {@code python3}, {@code unshare} and the toolchains for the allowed
 * runners; the default code-execution template does not ship all of them.
 */
public final class E2bCodingSandbox extends AbstractCodingSandbox {

    private static final Logger log = LoggerFactory.getLogger(E2bCodingSandbox.class);

    public static final String WORKSPACE = "/tmp/vd-workspace";
    static final String HOME = "/tmp/vd-home";
    private static final String CLEAN_ENV = "env -i PATH=/usr/local/go/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        + " HOME=" + HOME + " LANG=C.UTF-8 GIT_TERMINAL_PROMPT=0 GOCACHE=" + HOME + "/.cache/go-build CI=true";
    /** Output kept per command; the runner returns everything the process printed. */
    private static final int MAX_OUTPUT_CHARS = 200_000;

    /** What one remote script run returns. {@code exitCode} is null when the runner could not report one. */
    public record RemoteResult(Integer exitCode, boolean timedOut, String stdout, String stderr) { }

    /** The transport: one script in, one result out, plus teardown. Production adapts an E2B session. */
    public interface RemoteShell {
        RemoteResult run(String script, int timeoutSeconds, List<SandboxFile> files);

        void close();

        String id();
    }

    private final RemoteShell remote;
    private final CodingCommandPolicy policy;
    private final boolean networkEnabled;
    private final int commandTimeoutCapSeconds;
    private boolean closed;

    public E2bCodingSandbox(RemoteShell remote, CodingCommandPolicy policy, boolean networkEnabled,
                            int commandTimeoutCapSeconds) {
        this.remote = remote;
        this.policy = policy;
        this.networkEnabled = networkEnabled;
        this.commandTimeoutCapSeconds = Math.max(5, Math.min(115, commandTimeoutCapSeconds));
    }

    @Override
    public ShellRunner internal() {
        return (command, timeoutSeconds) -> exec(command, timeoutSeconds, false);
    }

    @Override
    public ShellRunner restricted() {
        return (command, timeoutSeconds) -> exec(policy.validate(command), timeoutSeconds, true);
    }

    @Override
    public void seedFromArchive(byte[] sanitizedZip) {
        String script = "mkdir -p " + WORKSPACE + " && python3 -c "
            + ShellQuote.single("import zipfile; zipfile.ZipFile('repo.zip').extractall('" + WORKSPACE + "')");
        RemoteResult r = remote.run(script, 120, List.of(new SandboxFile("repo.zip", sanitizedZip)));
        ShellRunner.ShellResult result = toShellResult(r);
        if (!result.ok()) {
            throw new IllegalStateException("Could not unpack the repository: " + tail(result.output()));
        }
    }

    @Override
    public String describe() {
        return "e2b sandbox " + remote.id();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            remote.close();
        } catch (RuntimeException e) {
            log.warn("Could not close {}: {}", describe(), e.getMessage());
        }
    }

    /** The exact script sent for {@code command}; package-private so tests can assert it. */
    static String script(String command, boolean restricted, boolean networkEnabled, int timeoutSeconds) {
        StringBuilder sb = new StringBuilder();
        sb.append("mkdir -p ").append(WORKSPACE).append(' ').append(HOME).append(" || exit 97\n");
        sb.append("cd ").append(WORKSPACE).append(" || exit 97\n");
        sb.append("exec ").append(CLEAN_ENV).append(' ');
        if (restricted) {
            sb.append("timeout -k 5 ").append(timeoutSeconds).append(' ');
            if (!networkEnabled) {
                sb.append("unshare -rn ");
            }
        }
        sb.append("bash --noprofile --norc -c ").append(ShellQuote.single(command)).append('\n');
        return sb.toString();
    }

    private ShellRunner.ShellResult exec(String command, int timeoutSeconds, boolean restricted) {
        if (closed) {
            throw new IllegalStateException("The sandbox is closed.");
        }
        int timeout = Math.max(1, Math.min(commandTimeoutCapSeconds, timeoutSeconds));
        String script = script(command, restricted, networkEnabled, timeout);
        RemoteResult r;
        try {
            // A few seconds of slack so the in-sandbox `timeout` fires before the runner's own kill.
            r = remote.run(script, timeout + 5, null);
        } catch (RuntimeException e) {
            // Includes a read timeout talking to the runner: report it as a timed-out command, not a crash.
            return new ShellRunner.ShellResult(124, "[harness] the command did not finish in time or the sandbox "
                + "could not be reached: " + e.getMessage());
        }
        ShellRunner.ShellResult result = toShellResult(r);
        if (restricted && !networkEnabled && result.output().contains("unshare failed")) {
            return new ShellRunner.ShellResult(result.exitCode(), result.output()
                + "\n[harness] this sandbox template cannot create an isolated network namespace, so the command "
                + "was not run. Use a template that allows 'unshare -rn', or have an operator set "
                + "app.coding-harness.network-enabled=true.");
        }
        return result;
    }

    private static ShellRunner.ShellResult toShellResult(RemoteResult r) {
        String out = (r.stdout() == null ? "" : r.stdout())
            + (r.stderr() == null || r.stderr().isEmpty() ? "" : (r.stdout() == null || r.stdout().isEmpty() ? "" : "\n") + r.stderr());
        if (out.length() > MAX_OUTPUT_CHARS) {
            out = out.substring(out.length() - MAX_OUTPUT_CHARS);
        }
        int exit = r.exitCode() != null ? r.exitCode() : (r.timedOut() ? 124 : 125);
        return new ShellRunner.ShellResult(exit, out);
    }
}
