package com.agenticai.core.agents.tools.browseruse;

import com.microsoft.playwright.PlaywrightException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The {@code browser_click} built-in - clicks an element in this conversation's browser session
 * (see {@link BrowserNavigateToolCallback}'s javadoc for the family this belongs to and {@link
 * BrowserUseSessionManager}'s for the session model).
 *
 * <p>Classified {@code EGRESS}/irreversible in {@link
 * com.agenticai.core.agents.tools.classification.BuiltInToolClassifications} - the same treatment
 * {@code channel_send_message}/{@code forward_attachment} get - because a click can submit a form,
 * confirm a purchase, or trigger any other externally-visible, un-undoable effect on the site being
 * driven, and the selector/description argument may itself be steered by page content the session
 * already read (an injected instruction on a page telling the model "click the Confirm Transfer
 * button"), which is exactly the taint-sink shape the classification exists to flag.
 */
public class BrowserClickToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(BrowserClickToolCallback.class);

    private final BrowserUseSessionManager sessionManager;
    private final UUID agentId;
    private final String sessionId;

    public BrowserClickToolCallback(
            BrowserUseSessionManager sessionManager, UUID agentId, String sessionId) {
        this.sessionManager = sessionManager;
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("browser_click")
                .description(
                        "Clicks an element on the current page of this conversation's browser session (started with "
                                + "browser_navigate). The 'selector' argument accepts either a real CSS selector ('#submit', "
                                + "'button.primary') or a plain description of the element's visible text ('Submit', 'the Log "
                                + "in button') - both work. Requires browser_navigate to have been called first.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "selector": {
                            "type": "string",
                            "description": "A CSS selector or a plain description of the element's visible text/label to click."
                        }
                    },
                    "required": ["selector"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        Optional<String> tenantId = BrowserUseToolSupport.resolveTenantId();
        if (tenantId.isEmpty()) {
            return BrowserUseToolSupport.noTenantContextError();
        }
        Map<String, Object> args = BrowserUseToolSupport.parseArgs(toolInput);
        String selector = BrowserUseToolSupport.asTrimmedString(args.get("selector"));
        if (selector == null) {
            return BrowserUseToolSupport.errorJson("The 'selector' argument is required.");
        }
        try {
            sessionManager.click(tenantId.get(), agentId, sessionId, selector);
            return BrowserUseToolSupport.toJson(
                    Map.of(
                            "clicked",
                            selector,
                            "note",
                            "Click sent. Use browser_read_page_text or browser_screenshot to see the result."));
        } catch (BrowserUseException e) {
            return BrowserUseToolSupport.errorJson(
                    "Could not click '" + selector + "': " + e.getMessage());
        } catch (PlaywrightException e) {
            return BrowserUseToolSupport.errorJson(
                    "Could not click '"
                            + selector
                            + "': no matching element was found, or it was not clickable.");
        } catch (Exception e) {
            log.warn("browser_click tool call failed unexpectedly", e);
            return BrowserUseToolSupport.errorJson(
                    "Could not click '" + selector + "': an unexpected error occurred.");
        }
    }
}
