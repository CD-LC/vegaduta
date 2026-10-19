package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.secrets.SecretVaultService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the persistence/replay half of the browser tool family for one conversation: {@code
 * browser_totp} (behind {@code app.browser.totp.enabled}) and {@code run_browser_recipe} (behind
 * {@code app.browser.recipes.enabled}). Kept as a collaborator so {@code
 * AgentCrewBrowserToolResolver} needs only a setter and one small method, and so the two flags are
 * decided in one place: with both off (the default) {@link #create} returns nothing and the tool
 * list is exactly what it was before. {@code browser_request_user} (human takeover) is added when
 * {@code app.browser.takeover.enabled} and browser v2 are both on.
 *
 * <p>Returns the RAW callbacks; the resolver wraps each with {@code guardAndLog} like every other
 * first-party tool. Optional collaborators are {@link ObjectProvider}s so a slim test context
 * without the vault or the recipe beans still constructs.
 */
@Component
public class BrowserAutomationTools {

    private final ObjectProvider<BrowserRecipeService> recipes;
    private final ObjectProvider<SecretVaultService> vault;
    private final boolean totpEnabled;
    private final String secretNamePrefix;

    public BrowserAutomationTools(
            ObjectProvider<BrowserRecipeService> recipes,
            ObjectProvider<SecretVaultService> vault,
            @Value("${app.browser.totp.enabled:false}") boolean totpEnabled,
            @Value("${app.browser.secret-name-prefix:BROWSER_}") String secretNamePrefix) {
        this.recipes = recipes;
        this.vault = vault;
        this.totpEnabled = totpEnabled;
        this.secretNamePrefix = secretNamePrefix;
    }

    /**
     * Human takeover ({@code browser_request_user}); setter-injected and optional so the
     * constructor is unchanged. Null, or a service that is not active (app.browser.takeover.enabled
     * + browser v2), adds no tool.
     */
    private volatile BrowserTakeoverService takeover;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTakeover(BrowserTakeoverService takeover) {
        this.takeover = takeover;
    }

    public List<ToolCallback> create(
            BrowserUseSessionManager manager, UUID agentId, String sessionId) {
        List<ToolCallback> tools = new ArrayList<>();
        BrowserTakeoverService takeoverService = takeover;
        if (takeoverService != null && takeoverService.isActive() && manager.isV2Enabled()) {
            tools.add(
                    new BrowserRequestUserToolCallback(
                            manager, takeoverService, agentId, sessionId));
        }
        SecretVaultService secrets = vault.getIfAvailable();
        if (totpEnabled && secrets != null) {
            tools.add(new BrowserTotpToolCallback(secrets, secretNamePrefix));
        }
        BrowserRecipeService recipeService = recipes.getIfAvailable();
        if (recipeService != null && recipeService.isEnabled()) {
            tools.add(
                    new RunBrowserRecipeToolCallback(
                            manager, recipeService, secrets, secretNamePrefix, agentId, sessionId));
        }
        return tools;
    }
}
