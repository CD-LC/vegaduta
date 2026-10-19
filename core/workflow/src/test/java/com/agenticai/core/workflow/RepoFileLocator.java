package com.agenticai.core.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Finds a file elsewhere in the repository (the web sources, another module's migrations) by walking up from
 * the test's working directory. A missing file FAILS the calling test rather than skipping it: a parity check
 * that silently disappears when the checkout layout changes is worse than none.
 */
final class RepoFileLocator {

    private RepoFileLocator() {
    }

    /** The file at {@code relativePath} under the nearest ancestor of the working directory that has it. */
    static Path find(String relativePath) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot find '" + relativePath + "' in any parent of " + dir
            + "; this test reads it from the repository checkout.");
    }

    static String read(String relativePath) {
        try {
            return Files.readString(find(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + relativePath, e);
        }
    }
}
