package com.agenticai.core.agents.tools.stdiosandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Refuse-don't-quote validation for the strings that land in sandbox profiles and mount flags. */
class SandboxTextTest {

    @Test
    void validEnvNameAcceptsPosixNamesOnly() {
        assertTrue(SandboxText.validEnvName("PATH"));
        assertTrue(SandboxText.validEnvName("_private1"));
        assertTrue(SandboxText.validEnvName("a"));

        assertFalse(SandboxText.validEnvName(null));
        assertFalse(SandboxText.validEnvName(""));
        assertFalse(SandboxText.validEnvName("1STARTS_WITH_DIGIT"));
        assertFalse(SandboxText.validEnvName("HAS SPACE"));
        assertFalse(SandboxText.validEnvName("A=B"));
        assertFalse(SandboxText.validEnvName("A,B"));
        assertFalse(SandboxText.validEnvName("with-dash"));
    }

    @Test
    void requirePathNeedsAnAbsolutePath() {
        assertThrows(StdioSandboxException.class, () -> SandboxText.requirePath(null, "root"));
        assertThrows(
                StdioSandboxException.class,
                () -> SandboxText.requirePath(Path.of("relative/dir"), "root"));
        assertEquals("/abs/dir", SandboxText.requirePath(Path.of("/abs/./dir"), "root"));
        // The normalized form is what comes back.
        assertEquals("/a/b", SandboxText.requirePath(Path.of("/a/x/../b"), "root"));
    }

    @Test
    void requirePlainRejectsControlCharacters() {
        SandboxText.requirePlain("perfectly ordinary text", "value");
        assertThrows(
                StdioSandboxException.class, () -> SandboxText.requirePlain("with\ttab", "value"));
        assertThrows(
                StdioSandboxException.class,
                () -> SandboxText.requirePlain("with\nnewline", "value"));
        assertThrows(
                StdioSandboxException.class,
                () -> SandboxText.requirePlain("with" + Character.toString(0) + "nul", "value"));
        assertThrows(
                StdioSandboxException.class,
                () -> SandboxText.requirePlain("with" + (char) 0x7f + "del", "value"));
    }

    @Test
    void requireMountPathRefusesTheMountParserSeparators() {
        assertEquals("/mnt/data", SandboxText.requireMountPath(Path.of("/mnt/data"), "mount"));
        for (Path bad :
                new Path[] {
                    Path.of("/mnt/a,b"), Path.of("/mnt/a\"b"), Path.of("/mnt/a\\b"),
                }) {
            assertThrows(
                    StdioSandboxException.class,
                    () -> SandboxText.requireMountPath(bad, "mount"),
                    bad.toString());
        }
        // A colon anywhere in the path is refused (it splits src:dst on Linux too).
        assertThrows(
                StdioSandboxException.class,
                () -> SandboxText.requireMountPath(Path.of("/mnt/a:b"), "mount"));
    }

    @Test
    void sbplStringQuotesAndEscapes() {
        assertEquals("\"plain\"", SandboxText.sbplString("plain"));
        assertEquals("\"a\\\"b\"", SandboxText.sbplString("a\"b"));
        assertEquals("\"a\\\\b\"", SandboxText.sbplString("a\\b"));
        assertThrows(StdioSandboxException.class, () -> SandboxText.sbplString("line\nbreak"));
    }
}
