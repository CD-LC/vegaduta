package com.agenticai.core.agents.tools.browseruse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Wire shapes of {@code /api/browser/takeover}. Nothing here carries page text, a typed value or a
 * session key: a view is the state, a short display reason, the site's HOST (never path or query),
 * and countdowns.
 */
public final class BrowserTakeoverDtos {

    private BrowserTakeoverDtos() {}

    /** Which session: the agent and the chat session id the playground already holds. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionRef(UUID agentId, String sessionId, String kind, String reason) {}

    /** One takeover as the person's browser sees it. */
    public record View(
            String takeoverId,
            String state,
            String requestedBy,
            String kind,
            String reason,
            String host,
            long idleRemainingSeconds,
            long maxRemainingSeconds,
            long waitRemainingSeconds) {}

    /**
     * Result of taking control: the token for the stream and input calls, and the limits the client
     * should respect.
     */
    public record Begin(
            View view,
            String token,
            long tokenExpiresInSeconds,
            double fps,
            int maxDimension,
            int maxTypeChars,
            int inputPerSecond) {}

    /** A refreshed token (the client renews well before {@code tokenExpiresInSeconds}). */
    public record TokenRefresh(String token, long tokenExpiresInSeconds) {}

    /**
     * One input event. {@code type}: {@code click} (x, y within frameWidth x frameHeight), {@code
     * key} (key), {@code type} (text), {@code scroll} (dx, dy, and x, y for where the wheel is).
     * Unknown fields are ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InputEvent(
            String type,
            Integer x,
            Integer y,
            Integer frameWidth,
            Integer frameHeight,
            String key,
            String text,
            Integer dx,
            Integer dy) {}

    /** One live-view frame as streamed (SSE {@code frame} event data). */
    public record FrameEvent(
            long seq,
            String mime,
            String data,
            int width,
            int height,
            String state,
            long idleRemainingSeconds,
            long maxRemainingSeconds) {}
}
