package com.agenticai.core.agents.turn;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.chat.cache.*}: what the exact-match and the semantic response caches (both opt-in per agent) may
 * serve and store, and what they key on. They used to key on the single message and to be open to every turn:
 * "yes" or "continue" returned the answer cached for a different conversation, and a turn that had read live
 * data through a tool could be answered from, or stored in, a cache. Now a turn of an agent that offers tools
 * is not cacheable ({@link #isEligibleWithTools()}), and the key includes the conversation so far ({@link
 * #isKeyOnConversation()}).
 *
 * <p>Defaults are read from {@code /chat/cache.properties} on the classpath; the same values are in {@code
 * application.yml}. Registered by {@link ChatCacheConfiguration}.
 */
@ConfigurationProperties(prefix = "app.chat.cache")
public class ChatCacheProperties {

    private static final String DEFAULTS_RESOURCE = "/chat/cache.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** True: a turn of an agent that offers tools may be served from, and stored in, a cache (the old behaviour). */
    private boolean eligibleWithTools = Boolean.parseBoolean(DEFAULTS.getProperty("eligible-with-tools"));

    /** True: the key is the message and the conversation before it; false: the message alone. */
    private boolean keyOnConversation = Boolean.parseBoolean(DEFAULTS.getProperty("key-on-conversation"));

    /** How many of the newest earlier turns go into the key. */
    private int contextTurns = Integer.parseInt(DEFAULTS.getProperty("context-turns"));

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = ChatCacheProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents-turn module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    public boolean isEligibleWithTools() {
        return eligibleWithTools;
    }

    public void setEligibleWithTools(boolean eligibleWithTools) {
        this.eligibleWithTools = eligibleWithTools;
    }

    public boolean isKeyOnConversation() {
        return keyOnConversation;
    }

    public void setKeyOnConversation(boolean keyOnConversation) {
        this.keyOnConversation = keyOnConversation;
    }

    public int getContextTurns() {
        return contextTurns;
    }

    public void setContextTurns(int contextTurns) {
        this.contextTurns = Math.max(1, contextTurns);
    }
}
