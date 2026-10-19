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
 * The {@code browser_type} built-in - fills text into an element in this conversation's browser
 * session (see {@link BrowserNavigateToolCallback}'s javadoc for the family, {@link
 * BrowserUseSessionManager}'s for the session model). Classified identically to {@link
 * BrowserClickToolCallback} - see its javadoc for why ({@code EGRESS}/irreversible): filled text is
 * routinely followed by a submit, and the text itself can carry an injected instruction forward
 * into whatever the destination site does with it.
 */
public class BrowserTypeToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(BrowserTypeToolCallback.class);

    private final BrowserUseSessionManager sessionManager;
    private final UUID agentId;
    private final String sessionId;

    public BrowserTypeToolCallback(
            BrowserUseSessionManager sessionManager, UUID agentId, String sessionId) {
        this.sessionManager = sessionManager;
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("browser_type")
                .description(
                        "Types text into an input/textarea on the current page of this conversation's browser session "
                                + "(started with browser_navigate). The 'selector' argument accepts either a real CSS selector "
                                + "or a plain description of the field ('the email field', 'search box') - both work. Replaces "
                                + "any existing value in the field. Requires browser_navigate to have been called first.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "selector": {
                            "type": "string",
                            "description": "A CSS selector or a plain description of the input field to type into."
                        },
                        "text": {
                            "type": "string",
                            "description": "The text to type into the field."
                        }
                    },
                    "required": ["selector", "text"]
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
        Object rawText = args.get("text");
        String text = rawText == null ? "" : rawText.toString();
        try {
            sessionManager.type(tenantId.get(), agentId, sessionId, selector, text);
            return BrowserUseToolSupport.toJson(
                    Map.of(
                            "typedInto",
                            selector,
                            "note",
                            "Text entered. Use browser_read_page_text or browser_screenshot to see the result."));
        } catch (BrowserUseException e) {
            return BrowserUseToolSupport.errorJson(
                    "Could not type into '" + selector + "': " + e.getMessage());
        } catch (PlaywrightException e) {
            return BrowserUseToolSupport.errorJson(
                    "Could not type into '"
                            + selector
                            + "': no matching element was found, or it was not editable.");
        } catch (Exception e) {
            log.warn("browser_type tool call failed unexpectedly", e);
            return BrowserUseToolSupport.errorJson(
                    "Could not type into '" + selector + "': an unexpected error occurred.");
        }
    }
}
