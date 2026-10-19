package com.agenticai.core.sdlc.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspacePathsTest {

    @Test
    void normalizesPlainRelativePaths() {
        assertEquals("src/main/App.java", WorkspacePaths.normalize("src/main/App.java"));
        assertEquals("a/b.txt", WorkspacePaths.normalize("./a//b.txt"));
        assertEquals("pkg/__init__.py", WorkspacePaths.normalize("pkg/__init__.py"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "../etc/passwd", "a/../../b", "..", "a/..", "/etc/passwd", "~/x", "C:/Windows/x", "c:\\x", "a\\b", ".git/config",
        "src/.git/hooks/pre-commit", ".GIT/config", ".gitmodules", "a/.gitattributes", "-rf", "a/-b", "a b",
        "a;b", "a$(id)", "a`id`", "a\nb", "a\u0000b", "", "   ", ".", "./",
    })
    void rejectsEscapesAndProtectedNames(String path) {
        assertThrows(IllegalArgumentException.class, () -> WorkspacePaths.normalize(path), path);
    }

    @Test
    void rejectsNullAndOverlongAndTooDeep() {
        assertThrows(IllegalArgumentException.class, () -> WorkspacePaths.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePaths.normalize("a".repeat(301)));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePaths.normalize("a/".repeat(30) + "f"));
    }
}
