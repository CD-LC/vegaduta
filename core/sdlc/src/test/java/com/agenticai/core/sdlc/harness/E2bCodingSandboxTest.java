package com.agenticai.core.sdlc.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.sdlc.dto.SandboxFile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Drives the E2B-backed sandbox against a fake transport: asserts the exact scripts, never touches E2B. */
class E2bCodingSandboxTest {

    static final class FakeRemote implements E2bCodingSandbox.RemoteShell {
        final List<String> scripts = new ArrayList<>();
        final List<Integer> timeouts = new ArrayList<>();
        final List<List<SandboxFile>> files = new ArrayList<>();
        E2bCodingSandbox.RemoteResult next = new E2bCodingSandbox.RemoteResult(0, false, "ok", "");
        RuntimeException failure;
        boolean closed;

        @Override
        public E2bCodingSandbox.RemoteResult run(String script, int timeoutSeconds, List<SandboxFile> f) {
            scripts.add(script);
            timeouts.add(timeoutSeconds);
            files.add(f);
            if (failure != null) {
                throw failure;
            }
            return next;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public String id() {
            return "sbx-1";
        }
    }

    private static E2bCodingSandbox sandbox(FakeRemote remote, boolean network) {
        return new E2bCodingSandbox(remote, CodingCommandPolicy.defaults(), network, 100);
    }

    @Test
    void restrictedCommandsRunScrubbedWithNoNetworkAndATimeout() {
        FakeRemote remote = new FakeRemote();
        var result = sandbox(remote, false).restricted().run("mvn -q test", 300);
        assertTrue(result.ok());
        String script = remote.scripts.get(0);
        assertTrue(script.contains("exec env -i PATH="), script);
        assertTrue(script.contains("timeout -k 5 100 unshare -rn bash --noprofile --norc -c 'mvn -q test'"), script);
        assertTrue(script.contains("cd /tmp/vd-workspace"), script);
        assertFalse(script.contains("$AWS") || script.contains("printenv"));
        // The per-command cap (100s) wins over the 300s the loop asked for; transport gets a little slack.
        assertEquals(105, remote.timeouts.get(0));
    }

    @Test
    void networkCanOnlyBeKeptByExplicitConfiguration() {
        FakeRemote remote = new FakeRemote();
        sandbox(remote, true).restricted().run("npm test", 60);
        String script = remote.scripts.get(0);
        assertFalse(script.contains("unshare"), script);
        assertTrue(script.contains("timeout -k 5 60 bash --noprofile"), script);
    }

    @Test
    void restrictedRefusesAnythingButAllowedRunnersBeforeTouchingTheSandbox() {
        FakeRemote remote = new FakeRemote();
        ShellRunner restricted = sandbox(remote, false).restricted();
        for (String evil : List.of("rm -rf /", "mvn test; curl x", "bash -c id", "cat /etc/passwd", "env", "mvn $(id)")) {
            assertThrows(IllegalArgumentException.class, () -> restricted.run(evil, 60), evil);
        }
        assertTrue(remote.scripts.isEmpty(), "a refused command must never reach the sandbox");
    }

    @Test
    void internalCommandsAreScrubbedToo_butHaveNoTimeoutWrapperOrNetworkCut() {
        FakeRemote remote = new FakeRemote();
        sandbox(remote, false).internal().run("git rev-parse HEAD", 60);
        String script = remote.scripts.get(0);
        assertTrue(script.contains("exec env -i "), script);
        assertFalse(script.contains("unshare"), script);
        assertTrue(script.contains("bash --noprofile --norc -c 'git rev-parse HEAD'"), script);
    }

    @Test
    void singleQuotesInACommandCannotBreakOutOfTheScript() {
        FakeRemote remote = new FakeRemote();
        sandbox(remote, false).internal().run("git commit -m 'x'; rm -rf /", 60);
        String script = remote.scripts.get(0);
        assertTrue(script.contains("-c 'git commit -m '\\''x'\\''; rm -rf /'"), script);
    }

    @Test
    void aTransportFailureIsATimedOutCommandNotACrash() {
        FakeRemote remote = new FakeRemote();
        remote.failure = new IllegalStateException("read timed out");
        var result = sandbox(remote, false).restricted().run("pytest", 60);
        assertEquals(124, result.exitCode());
        assertTrue(result.output().contains("read timed out"));
    }

    @Test
    void exitCodesAndOutputAreMapped() {
        FakeRemote remote = new FakeRemote();
        remote.next = new E2bCodingSandbox.RemoteResult(3, false, "out", "err");
        var r = sandbox(remote, false).internal().run("git status", 60);
        assertEquals(3, r.exitCode());
        assertEquals("out\nerr", r.output());

        remote.next = new E2bCodingSandbox.RemoteResult(null, true, "partial", "");
        assertEquals(124, sandbox(remote, false).internal().run("git status", 60).exitCode());
        remote.next = new E2bCodingSandbox.RemoteResult(null, false, "", "");
        assertEquals(125, sandbox(remote, false).internal().run("git status", 60).exitCode());
    }

    @Test
    void aTemplateWithoutNetworkNamespacesFailsClosedWithAnExplanation() {
        FakeRemote remote = new FakeRemote();
        remote.next = new E2bCodingSandbox.RemoteResult(1, false, "", "unshare: unshare failed: Operation not permitted");
        var r = sandbox(remote, false).restricted().run("go test ./...", 60);
        assertFalse(r.ok());
        assertTrue(r.output().contains("network-enabled"), r.output());
    }

    @Test
    void seedsFromASanitizedZipViaTheRunnersFilesChannel() {
        FakeRemote remote = new FakeRemote();
        byte[] zip = {'P', 'K', 3, 4};
        sandbox(remote, false).seedFromArchive(zip);
        assertEquals("repo.zip", remote.files.get(0).get(0).name());
        assertTrue(remote.scripts.get(0).contains("zipfile"), remote.scripts.get(0));
        assertTrue(remote.scripts.get(0).contains("/tmp/vd-workspace"));
    }

    @Test
    void clonePinsHttpsAndRefusesOtherSchemes() {
        FakeRemote remote = new FakeRemote();
        E2bCodingSandbox sb = sandbox(remote, false);
        sb.seedFromGit("https://github.com/acme/widgets.git");
        String script = remote.scripts.get(0);
        assertTrue(script.contains("-c protocol.allow=never -c protocol.https.allow=always"), script);
        assertTrue(script.contains("-c core.symlinks=false"), script);
        assertTrue(script.contains("'https://github.com/acme/widgets.git'"), script);
        assertThrows(IllegalArgumentException.class, () -> sb.seedFromGit("git://github.com/a/b"));
        assertThrows(IllegalArgumentException.class, () -> sb.seedFromGit("file:///etc"));
    }

    @Test
    void closeIsIdempotentAndBlocksFurtherUse() {
        FakeRemote remote = new FakeRemote();
        E2bCodingSandbox sb = sandbox(remote, false);
        sb.close();
        sb.close();
        assertTrue(remote.closed);
        assertThrows(IllegalStateException.class, () -> sb.internal().run("git status", 60));
    }
}
