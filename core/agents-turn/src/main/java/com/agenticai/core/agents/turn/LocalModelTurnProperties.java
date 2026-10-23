package com.agenticai.core.agents.turn;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.chat.local-model.*}: how a turn adapts to a model served on the person's own machine, where reading the
 * prompt is the slow part and the server keeps one prompt cache. See {@code /chat/local-model.properties} for what each
 * value does; the same values are in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "app.chat.local-model")
public class LocalModelTurnProperties {

    private static final String DEFAULTS_RESOURCE = "/chat/local-model.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** Offer a local model the agent's tool set unchanged on every turn, so the server's prompt cache holds. */
    private boolean stableTools = Boolean.parseBoolean(DEFAULTS.getProperty("stable-tools"));

    /** Bring-your-own providers that are servers on the person's machine. */
    private Set<String> providers = names(DEFAULTS.getProperty("providers"));

    public boolean isStableTools() {
        return stableTools;
    }

    public void setStableTools(boolean stableTools) {
        this.stableTools = stableTools;
    }

    public Set<String> getProviders() {
        return providers;
    }

    public void setProviders(Set<String> providers) {
        this.providers = providers == null ? Set.of() : names(String.join(",", providers));
    }

    /** True when {@code providerName} (a bring-your-own provider) is a server on the person's machine. */
    public boolean isLocalProvider(String providerName) {
        return providerName != null && providers.contains(providerName.strip().toUpperCase(Locale.ROOT));
    }

    private static Set<String> names(String commaList) {
        Set<String> out = new LinkedHashSet<>();
        if (commaList != null) {
            for (String name : commaList.split(",")) {
                if (!name.isBlank()) {
                    out.add(name.strip().toUpperCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(out);
    }

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = LocalModelTurnProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents-turn module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }
}
