package com.agenticai.core.agents.generation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.agents.max-tokens-ceiling} and {@code app.agents.inferred-max-tokens-floor}: the bounds on an
 * agent's generation length. The ceiling used to be the literal 8192 in six places (the create and update
 * request validation, the Simple-mode tuning, the hosted-pool token reservation and two web screens); it is
 * one setting now, read by all the Java ones.
 *
 * <p>Defaults are read from {@code /agents/generation.properties}; the same values are in {@code
 * application.yml}. Registered by {@link AgentGenerationConfiguration}.
 */
@ConfigurationProperties(prefix = "app.agents")
public class AgentGenerationProperties {

    private static final String DEFAULTS_RESOURCE = "/agents/generation.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** Most output tokens one reply of an agent may be given. */
    private int maxTokensCeiling = Integer.parseInt(DEFAULTS.getProperty("max-tokens-ceiling"));

    /** Least value the Simple-mode tuning proposes. */
    private int inferredMaxTokensFloor = Integer.parseInt(DEFAULTS.getProperty("inferred-max-tokens-floor"));

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = AgentGenerationProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    public int getMaxTokensCeiling() {
        return maxTokensCeiling;
    }

    public void setMaxTokensCeiling(int maxTokensCeiling) {
        this.maxTokensCeiling = Math.max(1, maxTokensCeiling);
    }

    public int getInferredMaxTokensFloor() {
        return inferredMaxTokensFloor;
    }

    public void setInferredMaxTokensFloor(int inferredMaxTokensFloor) {
        this.inferredMaxTokensFloor = Math.max(1, inferredMaxTokensFloor);
    }
}
