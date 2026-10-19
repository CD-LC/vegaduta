package com.agenticai.core.agents.tools.browseruse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds a Chromium binary already present on the host so browser-use works without a second browser
 * download.
 *
 * <p>The container image bakes Chromium into {@code PLAYWRIGHT_BROWSERS_PATH} for the Playwright
 * MCP server, but the Playwright Java driver pins its own, different browser revision and would
 * otherwise try to download one at first use (and fail in a locked-down container). Pointing the
 * driver at the binary that is already on disk avoids both problems.
 */
final class BrowserUseChromiumLocator {

    private static final Set<String> EXECUTABLE_NAMES =
            Set.of("chrome", "chrome.exe", "headless_shell", "chrome-headless-shell");
    private static final int MAX_DEPTH = 4;

    private BrowserUseChromiumLocator() {}

    /**
     * @param configuredPath explicit {@code app.browseruse.chromium-executable-path}, may be blank
     * @param browsersRoot Playwright browsers cache directory, may be blank
     * @return a usable executable, preferring the explicit path, then the newest full Chromium
     *     under the root
     */
    static Optional<Path> locate(String configuredPath, String browsersRoot) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path explicit = Path.of(configuredPath.trim());
            return Files.isRegularFile(explicit) ? Optional.of(explicit) : Optional.empty();
        }
        if (browsersRoot == null || browsersRoot.isBlank()) {
            return Optional.empty();
        }
        Path root = Path.of(browsersRoot.trim());
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> EXECUTABLE_NAMES.contains(p.getFileName().toString()))
                    .filter(p -> isChromiumDir(root, p))
                    .max(
                            Comparator.comparingInt((Path p) -> isFullChrome(p) ? 1 : 0)
                                    .thenComparing(p -> root.relativize(p).toString()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean isChromiumDir(Path root, Path file) {
        Path relative = root.relativize(file);
        return relative.getNameCount() > 1
                && relative.getName(0)
                        .toString()
                        .toLowerCase(java.util.Locale.ROOT)
                        .startsWith("chromium");
    }

    private static boolean isFullChrome(Path file) {
        String name = file.getFileName().toString();
        return name.equals("chrome") || name.equals("chrome.exe");
    }
}
