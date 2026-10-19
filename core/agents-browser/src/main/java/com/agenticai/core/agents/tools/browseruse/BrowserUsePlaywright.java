package com.agenticai.core.agents.tools.browseruse;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one Playwright driver and headless Chromium of {@link BrowserUseSessionManager}, launched
 * lazily on first real use (not at application start-up, so a misconfigured environment fails one
 * tool call with a relayable message instead of failing Spring context refresh). Split out of the
 * manager; like the manager's session map it is touched ONLY on the manager's single Playwright
 * thread, so it needs no locking.
 */
final class BrowserUsePlaywright {

    private static final Logger log = LoggerFactory.getLogger(BrowserUsePlaywright.class);

    /**
     * Chromium already on disk (see {@link BrowserUseChromiumLocator}); empty means let Playwright
     * find its own.
     */
    private final Optional<Path> chromiumExecutable;

    /**
     * A Playwright server (ws:// endpoint of {@code playwright run-server}, the personal install's
     * {@code browser} add-on). Blank means launch Chromium in this JVM's own container. Its version
     * must match the pinned client's major.minor (Playwright refuses a mismatch with a clear error).
     */
    private final String remoteUrl;

    private Playwright playwright;
    private Browser browser;

    BrowserUsePlaywright(Optional<Path> chromiumExecutable) {
        this(chromiumExecutable, "");
    }

    BrowserUsePlaywright(Optional<Path> chromiumExecutable, String remoteUrl) {
        this.chromiumExecutable = chromiumExecutable;
        this.remoteUrl = remoteUrl == null ? "" : remoteUrl.trim();
    }

    /** True when the browser lives in a separate container reached over {@code app.browseruse.remote-url}. */
    boolean usesRemote() {
        return !remoteUrl.isEmpty();
    }

    /**
     * Why the remote browser cannot be reached right now, or {@code null} (also when none is configured).
     * A plain TCP connect: cheap, no Playwright thread, no browser started.
     */
    String remoteProblem() {
        if (!usesRemote()) {
            return null;
        }
        try {
            URI uri = URI.create(remoteUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : "wss".equals(uri.getScheme()) ? 443 : 80;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), 800);
            }
            return null;
        } catch (RuntimeException | java.io.IOException e) {
            return "The browser add-on is not answering at " + remoteUrl
                    + " (start it with `scripts/vegaduta personal --browser`, or check `docker compose ps`).";
        }
    }

    /** Where the deploy image keeps its browsers ({@code PLAYWRIGHT_BROWSERS_PATH}). */
    static String browsersRootFromEnv() {
        String fromEnv = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        return fromEnv == null || fromEnv.isBlank() ? "/opt/playwright-browsers" : fromEnv;
    }

    boolean hasChromium() {
        return chromiumExecutable.isPresent();
    }

    /** The launched (or connected) browser, starting it on first use and again after a lost connection. */
    Browser browser() {
        if (browser != null) {
            if (browser.isConnected()) {
                return browser;
            }
            // The sidecar restarted or Chromium crashed: drop the dead handle and start over.
            close();
        }
        try {
            // When we already have a Chromium (or the browser is remote), never let the driver try to
            // download its own pinned revision (fails in locked-down containers).
            Playwright.CreateOptions createOptions = new Playwright.CreateOptions();
            if (chromiumExecutable.isPresent() || usesRemote()) {
                createOptions.setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
            }
            // Playwright finds its bundled driver with the thread's CONTEXT class loader. This runs on a
            // dedicated worker thread whose context loader cannot see the nested driver-bundle jar inside
            // the Spring Boot fat jar (getResource("driver/linux") is null -> "Failed to create driver"), so
            // hand it this class's own loader for the duration of the call.
            Thread worker = Thread.currentThread();
            ClassLoader previousLoader = worker.getContextClassLoader();
            worker.setContextClassLoader(BrowserUsePlaywright.class.getClassLoader());
            try {
                playwright = Playwright.create(createOptions);
            } finally {
                worker.setContextClassLoader(previousLoader);
            }
            if (usesRemote()) {
                browser =
                        playwright
                                .chromium()
                                .connect(remoteUrl, new BrowserType.ConnectOptions().setTimeout(15_000));
                return browser;
            }
            BrowserType.LaunchOptions launchOptions =
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setArgs(List.of("--disable-dev-shm-usage", "--no-sandbox"));
            chromiumExecutable.ifPresent(launchOptions::setExecutablePath);
            browser = playwright.chromium().launch(launchOptions);
            return browser;
        } catch (RuntimeException e) {
            // Most common cause: `playwright install chromium` never ran in
            // this image (or the browser add-on is not running). Close out any half-created
            // Playwright handle so the NEXT call retries cleanly instead of being stuck on a
            // broken reference.
            close();
            // Playwright wraps the real cause ("Failed to create driver" says nothing): keep the whole chain.
            log.warn("The browser could not be started ({})", usesRemote() ? remoteUrl : "in-process Chromium", e);
            String detail = BrowserUseActionRunner.safeMessage(e) + rootCause(e);
            throw new BrowserUseException(
                    usesRemote()
                            ? "the browser add-on is not reachable at " + remoteUrl + ": " + detail
                            : "the platform's headless browser is not available in this environment: " + detail,
                    e);
        }
    }

    private static String rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == e ? "" : " (" + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
    }

    /** Closes the browser and the driver; best effort, safe to call twice. */
    void close() {
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (RuntimeException ignored) {
            // best effort
        } finally {
            browser = null;
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (RuntimeException ignored) {
            // best effort
        } finally {
            playwright = null;
        }
    }
}
