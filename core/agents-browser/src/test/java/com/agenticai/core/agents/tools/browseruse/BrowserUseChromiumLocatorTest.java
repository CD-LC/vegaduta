package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserUseChromiumLocatorTest {

    @TempDir Path tmp;

    private Path touch(String relative) throws IOException {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "x");
    }

    @Test
    void explicitPathWinsWhenItExists() throws IOException {
        Path explicit = touch("custom/my-chrome");
        assertThat(BrowserUseChromiumLocator.locate(explicit.toString(), tmp.toString()))
                .contains(explicit);
    }

    @Test
    void explicitPathThatDoesNotExistDoesNotFallBackSilently() {
        assertThat(
                        BrowserUseChromiumLocator.locate(
                                tmp.resolve("missing").toString(), tmp.toString()))
                .isEmpty();
    }

    @Test
    void blankInputsFindNothing() {
        assertThat(BrowserUseChromiumLocator.locate("", "")).isEmpty();
        assertThat(BrowserUseChromiumLocator.locate(null, null)).isEmpty();
    }

    @Test
    void missingRootFindsNothing() {
        assertThat(BrowserUseChromiumLocator.locate("", tmp.resolve("nope").toString())).isEmpty();
    }

    @Test
    void findsChromeUnderChromiumRevisionDirectory() throws IOException {
        Path chrome = touch("chromium-1234/chrome-linux/chrome");
        assertThat(BrowserUseChromiumLocator.locate("", tmp.toString())).contains(chrome);
    }

    @Test
    void prefersNewestRevisionAndFullChromeOverHeadlessShell() throws IOException {
        touch("chromium-1100/chrome-linux/chrome");
        Path newest = touch("chromium-1237/chrome-linux64/chrome");
        touch("chromium_headless_shell-1300/chrome-headless-shell-linux64/chrome-headless-shell");
        assertThat(BrowserUseChromiumLocator.locate("", tmp.toString())).contains(newest);
    }

    @Test
    void ignoresNonChromiumBrowsers() throws IOException {
        touch("firefox-1500/firefox/chrome");
        assertThat(BrowserUseChromiumLocator.locate("", tmp.toString())).isEmpty();
    }
}
