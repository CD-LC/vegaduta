package com.agenticai.core.agents.turn;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.chat.follow-up.*}: how a turn whose message is a short follow-up ("yes", "continue",
 * "try again") is handled. Such a message shares no words with any tool and reads as a trivial question,
 * so judged on its own it cut the agent's tools down to the few that match nothing and was sent to the
 * economy model in the middle of a task. The turn now looks at the conversation: the tools it already
 * used stay on offer ({@link #isStickyToolsEnabled()}) and the model that started the task keeps it
 * ({@link #isKeepPrimaryModel()}).
 *
 * <p>Defaults are read from {@code /chat/follow-up.properties} on the classpath; the same values are in
 * {@code application.yml}. Registered by {@link FollowUpConfiguration}.
 */
@ConfigurationProperties(prefix = "app.chat.follow-up")
public class FollowUpProperties {

    private static final String DEFAULTS_RESOURCE = "/chat/follow-up.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** Keep the tools the conversation already used on offer for a follow-up. */
    private boolean stickyToolsEnabled = Boolean.parseBoolean(DEFAULTS.getProperty("sticky-tools-enabled"));

    /**
     * A message of at most this many characters (Unicode code points, trimmed) is a follow-up. Counting
     * characters rather than words keeps the rule language-neutral. {@code 0} turns the rules off.
     */
    private int shortMessageChars = Integer.parseInt(DEFAULTS.getProperty("short-message-chars"));

    /** How far back in the tool-call log "tools this conversation already used" looks. */
    private Duration stickyToolsWindow = Duration.parse(DEFAULTS.getProperty("sticky-tools-window"));

    /** The most tools kept on offer this way, most recently used first. */
    private int maxStickyTools = Integer.parseInt(DEFAULTS.getProperty("max-sticky-tools"));

    /** A follow-up in a conversation that already used tools never goes to the economy model. */
    private boolean keepPrimaryModel = Boolean.parseBoolean(DEFAULTS.getProperty("keep-primary-model"));

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = FollowUpProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    public boolean isStickyToolsEnabled() {
        return stickyToolsEnabled;
    }

    public void setStickyToolsEnabled(boolean stickyToolsEnabled) {
        this.stickyToolsEnabled = stickyToolsEnabled;
    }

    public int getShortMessageChars() {
        return shortMessageChars;
    }

    public void setShortMessageChars(int shortMessageChars) {
        this.shortMessageChars = Math.max(0, shortMessageChars);
    }

    public Duration getStickyToolsWindow() {
        return stickyToolsWindow;
    }

    public void setStickyToolsWindow(Duration stickyToolsWindow) {
        this.stickyToolsWindow = stickyToolsWindow;
    }

    public int getMaxStickyTools() {
        return maxStickyTools;
    }

    public void setMaxStickyTools(int maxStickyTools) {
        this.maxStickyTools = Math.max(0, maxStickyTools);
    }

    public boolean isKeepPrimaryModel() {
        return keepPrimaryModel;
    }

    public void setKeepPrimaryModel(boolean keepPrimaryModel) {
        this.keepPrimaryModel = keepPrimaryModel;
    }

    /**
     * True when {@code message} is a short follow-up: not blank, and no longer than {@link
     * #getShortMessageChars()} characters once trimmed.
     */
    public boolean isShortFollowUp(String message) {
        if (message == null || shortMessageChars <= 0) {
            return false;
        }
        String trimmed = message.strip();
        return !trimmed.isEmpty() && trimmed.codePointCount(0, trimmed.length()) <= shortMessageChars;
    }
}
