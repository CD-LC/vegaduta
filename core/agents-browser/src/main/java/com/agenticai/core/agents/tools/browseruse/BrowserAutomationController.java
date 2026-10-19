package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.Capabilities;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the console which browser-automation features this deployment has switched on (browser
 * profiles, recipes, TOTP) and whether the headless browser itself is available (a deployment
 * without Chromium offers no {@code browser_*} tools, so the cards would promise something that
 * cannot work), so the Profiles and Recipes cards can show an honest "not enabled" state instead of
 * a wall of 404s. Reads three flags and one boolean; no tenant data.
 */
@RestController
@RequestMapping("/api/browser/automation")
public class BrowserAutomationController {

    private final boolean profilesEnabled;
    private final boolean recipesEnabled;
    private final boolean totpEnabled;
    private final ObjectProvider<BrowserUseSessionManager> browser;

    public BrowserAutomationController(
            @Value("${app.browser.profiles.enabled:false}") boolean profilesEnabled,
            @Value("${app.browser.recipes.enabled:false}") boolean recipesEnabled,
            @Value("${app.browser.totp.enabled:false}") boolean totpEnabled,
            ObjectProvider<BrowserUseSessionManager> browser) {
        this.profilesEnabled = profilesEnabled;
        this.recipesEnabled = recipesEnabled;
        this.totpEnabled = totpEnabled;
        this.browser = browser;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        BrowserUseSessionManager manager = browser.getIfAvailable();
        // An add-on that is configured but not answering is reported as unavailable, with the reason.
        String remoteProblem = manager == null ? null : manager.remoteProblem();
        boolean available = manager != null && manager.isEnabled() && remoteProblem == null;
        String reason =
                available
                        ? null
                        : manager == null
                                ? "The browser is not part of this deployment."
                                : remoteProblem != null ? remoteProblem : manager.unavailableReason();
        return new Capabilities(profilesEnabled, recipesEnabled, totpEnabled, available, reason);
    }
}
