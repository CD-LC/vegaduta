package com.agenticai.core.agents.turn.quality;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.routing.simple.*}: what {@link QueryComplexityClassifier} calls a simple message, the only
 * kind cost routing hands to an agent's economy model. These were constants: a 160-character limit, two
 * clauses, a clause boundary that only English-like text has and an English list of signal words, so a
 * hard question in Hindi, Russian or Chinese read as short and keyword-free and was sent to the economy
 * model. Defaults are read from {@code /chat/routing-simple.properties}; the same values are in {@code
 * application.yml}. Registered by {@link RoutingSimpleConfiguration}.
 */
@ConfigurationProperties(prefix = "app.routing.simple")
public class RoutingSimpleProperties {

    private static final String DEFAULTS_RESOURCE = "/chat/routing-simple.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** A message longer than this many characters is never simple. */
    private int maxChars = Integer.parseInt(DEFAULTS.getProperty("max-chars"));

    /** A message of more sentences than this is never simple. */
    private int maxClauses = Integer.parseInt(DEFAULTS.getProperty("max-clauses"));

    /** A regular expression: where one sentence ends and the next begins. */
    private String clauseBoundary = DEFAULTS.getProperty("clause-boundary");

    /** False: a message with letters outside the Latin script is never simple. */
    private boolean nonLatinSimple = Boolean.parseBoolean(DEFAULTS.getProperty("non-latin-simple"));

    /**
     * False: a message with accented Latin letters (Spanish, French, German, Vietnamese, ...) is never simple,
     * for the same reason: the signal words are English, so they cannot say whether it is a hard question.
     */
    private boolean accentedLatinSimple = Boolean.parseBoolean(DEFAULTS.getProperty("accented-latin-simple"));

    /** Comma-separated phrases that mean analysis, generation or multi-step work, per language tag. */
    private Map<String, String> signalWords = byLocale("signal-words");

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = RoutingSimpleProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents-turn module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    private static Map<String, String> byLocale(String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        String start = prefix + ".";
        for (String key : DEFAULTS.stringPropertyNames()) {
            if (key.startsWith(start)) {
                out.put(key.substring(start.length()), DEFAULTS.getProperty(key));
            }
        }
        return out;
    }

    public int getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(int maxChars) {
        this.maxChars = Math.max(0, maxChars);
    }

    public int getMaxClauses() {
        return maxClauses;
    }

    public void setMaxClauses(int maxClauses) {
        this.maxClauses = Math.max(1, maxClauses);
    }

    public String getClauseBoundary() {
        return clauseBoundary;
    }

    public void setClauseBoundary(String clauseBoundary) {
        this.clauseBoundary = clauseBoundary;
    }

    public boolean isNonLatinSimple() {
        return nonLatinSimple;
    }

    public void setNonLatinSimple(boolean nonLatinSimple) {
        this.nonLatinSimple = nonLatinSimple;
    }

    public boolean isAccentedLatinSimple() {
        return accentedLatinSimple;
    }

    public void setAccentedLatinSimple(boolean accentedLatinSimple) {
        this.accentedLatinSimple = accentedLatinSimple;
    }

    public Map<String, String> getSignalWords() {
        return signalWords;
    }

    public void setSignalWords(Map<String, String> signalWords) {
        this.signalWords = signalWords;
    }
}
