package com.agenticai.core.sdlc.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real bash + real git against the dev-only local sandbox: the shell templates ({@link WorkspaceFiles},
 * {@link GitWorkspace}) are exercised for real here, which the E2B-faked tests cannot do. Skipped when
 * bash or git is not on the PATH.
 */
class LocalCodingSandboxTest {

    private LocalCodingSandbox sandbox;

    /** On a Windows dev box plain "bash" can resolve to WSL; prefer Git Bash when it is installed. */
    private static String bash() {
        for (String candidate : new String[] {"C:/Program Files/Git/usr/bin/bash.exe", "C:/Program Files (x86)/Git/usr/bin/bash.exe"}) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "bash";
    }

    @BeforeEach
    void setUp() {
        sandbox = new LocalCodingSandbox(CodingCommandPolicy.defaults(), bash());
        Assumptions.assumeTrue(sandbox.internal().run("git --version && readlink -m /tmp/x && base64 --version", 20).ok(),
            "needs bash, git, readlink and base64");
    }

    @AfterEach
    void tearDown() {
        sandbox.close();
    }

    private static byte[] zip(String name, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            z.putNextEntry(new ZipEntry(name));
            z.write(content.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void seedsAndRoundTripsFilesWithAwkwardContent() throws Exception {
        sandbox.seedFromArchive(zip("src/a.txt", "hello"));
        sandbox.prepareRepository();
        String awkward = "line1\n'quoted' \"double\" $HOME `id` $(id) \\ é中\n";
        WorkspaceFiles.write(sandbox.internal(), "src/deep/er/b.txt", awkward);
        assertEquals(awkward, Files.readString(sandbox.root().resolve("src/deep/er/b.txt")));
        assertEquals("hello", WorkspaceFiles.read(sandbox.internal(), "src/a.txt", 100));
        assertTrue(WorkspaceFiles.list(sandbox.internal(), 10).contains("src/a.txt"));
        WorkspaceFiles.delete(sandbox.internal(), "src/a.txt");
        assertFalse(Files.exists(sandbox.root().resolve("src/a.txt")));
    }

    @Test
    void pathTraversalNeverLeavesTheWorkspace() throws Exception {
        Path outside = sandbox.root().getParent().resolve("vd-escape-" + System.nanoTime() + ".txt");
        for (String evil : List.of("../" + outside.getFileName(), "a/../../" + outside.getFileName(),
            outside.toString().replace('\\', '/'), "/tmp/evil", ".git/hooks/pre-commit")) {
            assertThrows(IllegalArgumentException.class,
                () -> WorkspaceFiles.write(sandbox.internal(), evil, "boom"), evil);
        }
        assertFalse(Files.exists(outside));
    }

    @Test
    void aSymlinkInTheTreeCannotRedirectAWriteOutside() throws Exception {
        Path outsideDir = Files.createTempDirectory("vd-outside-");
        try {
            sandbox.seedFromArchive(zip("keep.txt", "x"));
            // Plant the link the way a build step could. Skip where symlinks cannot be created for real.
            var link = sandbox.internal().run("ln -s " + ShellQuote.single(outsideDir.toString().replace('\\', '/')) + " escape", 20);
            Assumptions.assumeTrue(link.ok() && sandbox.internal().run("[ -L escape ]", 10).ok(), "symlinks unavailable here");
            assertThrows(IllegalStateException.class,
                () -> WorkspaceFiles.write(sandbox.internal(), "escape/pwned.txt", "boom"));
            assertFalse(Files.exists(outsideDir.resolve("pwned.txt")));
            // ... and an existing link as the final component is refused as well.
            assertThrows(IllegalStateException.class, () -> WorkspaceFiles.write(sandbox.internal(), "escape", "boom"));
        } finally {
            try (var walk = Files.walk(outsideDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void anOversizedWriteIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> WorkspaceFiles.write(sandbox.internal(), "big.txt", "x".repeat(WorkspaceFiles.MAX_FILE_BYTES + 1)));
    }

    @Test
    void anArchiveEntryThatBypassedSanitisingStillCannotEscape() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> sandbox.seedFromArchive(zip("../escape.txt", "boom")));
        assertFalse(Files.exists(sandbox.root().getParent().resolve("escape.txt")));
    }

    @Test
    void theRestrictedHandleStillRefusesNonRunnerCommands() {
        assertThrows(IllegalArgumentException.class, () -> sandbox.restricted().run("cat /etc/passwd", 10));
    }

    @Test
    void theCommandEnvironmentIsScrubbed() {
        var r = sandbox.internal().run("env", 20);
        assertTrue(r.ok());
        assertFalse(r.output().contains("JAVA_HOME"), r.output());
        assertFalse(r.output().contains("USERNAME="), r.output());
        assertTrue(r.output().contains("GIT_TERMINAL_PROMPT=0"));
    }

    @Test
    void gitCheckpointDiffAndRollbackWorkForReal() throws Exception {
        sandbox.seedFromArchive(zip("Calc.txt", "v1\n"));
        sandbox.prepareRepository();
        GitWorkspace git = new GitWorkspace(sandbox.internal());
        git.ensureRepository();
        String base = git.checkpoint("harness baseline");
        WorkspaceFiles.write(sandbox.internal(), "Calc.txt", "v2\n");
        WorkspaceFiles.write(sandbox.internal(), "target/build.log", "ignored build output");
        String edited = git.checkpoint("harness fix 1");
        String diff = git.diffSince(base);
        assertTrue(diff.contains("-v1") && diff.contains("+v2"), diff);
        assertFalse(diff.contains("build.log"), "build output must stay out of the checkpoints");
        assertFalse(base.equals(edited));
        git.rollbackTo(base);
        assertEquals("v1\n", Files.readString(sandbox.root().resolve("Calc.txt")).replace("\r\n", "\n"));
    }

    @Test
    void closeRemovesTheWorkspace() {
        Path root = sandbox.root();
        sandbox.close();
        assertFalse(Files.exists(root));
    }
}
