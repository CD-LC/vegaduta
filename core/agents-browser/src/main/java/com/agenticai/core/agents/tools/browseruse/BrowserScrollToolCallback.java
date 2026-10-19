package com.agenticai.core.agents.tools.browseruse;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code browser_scroll} (browser agent v2): scrolls the page up/down (by {@code pixels}, default
 * 80% of the viewport) or to its top/bottom. Reveals lazy-loaded and below-the-fold elements; take
 * a new {@code browser_snapshot} afterwards. Classified like {@code browser_read_page_text} ({@code
 * UNTRUSTED_EXTERNAL}/{@code READ_PUBLIC}): it moves the viewport, it does not act on the site.
 */
public class BrowserScrollToolCallback extends BrowserV2ToolBase {

    public static final String NAME = "browser_scroll";

    private static final Set<String> DIRECTIONS = Set.of("up", "down", "top", "bottom");
    static final int MAX_PIXELS = 5_000;

    public BrowserScrollToolCallback(
            BrowserUseSessionManager sessionManager, UUID agentId, String sessionId) {
        super(sessionManager, agentId, sessionId);
    }

    @Override
    String toolName() {
        return NAME;
    }

    @Override
    String toolDescription() {
        return "Scrolls the browser page up or down (default 80% of the viewport), or to the top/bottom. Take a new "
                + "browser_snapshot afterwards to see the elements now in view.";
    }

    @Override
    String toolInputSchema() {
        return """
            {
                "type": "object",
                "properties": {
                    "direction": {
                        "type": "string",
                        "enum": ["down", "up", "top", "bottom"],
                        "description": "Where to scroll (default down)."
                    },
                    "pixels": {
                        "type": "integer",
                        "description": "Optional distance for up/down in pixels (max 5000)."
                    }
                }
            }
            """;
    }

    @Override
    String execute(String tenantId, Map<String, Object> args) {
        String direction = BrowserUseToolSupport.asTrimmedString(args.get("direction"));
        direction = direction == null ? "down" : direction.toLowerCase(Locale.ROOT);
        if (!DIRECTIONS.contains(direction)) {
            return BrowserUseToolSupport.errorJson(
                    "'direction' must be one of: down, up, top, bottom.");
        }
        int pixels = 0;
        Object rawPixels = args.get("pixels");
        if (rawPixels != null) {
            try {
                pixels =
                        Math.max(
                                0,
                                Math.min(
                                        MAX_PIXELS,
                                        Integer.parseInt(rawPixels.toString().strip())));
            } catch (NumberFormatException e) {
                pixels = 0;
            }
        }
        final String dir = direction;
        final int px = pixels;
        Map<String, Object> result =
                sessionManager.runV2(
                        tenantId,
                        agentId,
                        sessionId,
                        (session, page, settings) ->
                                BrowserV2Engine.scroll(session, page, settings, dir, px));
        return BrowserUseToolSupport.toJson(result);
    }
}
