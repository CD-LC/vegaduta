package com.agenticai.core.agents.turn;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ToolCallGrade;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.chat.continuation.*}: when a turn re-prompts the model because its reply stopped at a step
 * it announced and never took, and what it says to it. (The number of extra passes is {@code
 * app.chat.continuation.max-passes}, read by {@link ChatTimeouts}.)
 *
 * <p>The provider's tool loop ends when the model returns no tool call, so a model that writes "Let me
 * create the file:" and stops looks finished to the framework. Nothing but the wording says otherwise,
 * and wording is a poor signal: it fired on "Let me know if you need anything else" and cost another
 * model call on ordinary answers. So the wording check is a configurable fallback, locale-keyed, and
 * applied by default only to a model that is a weak tool caller ({@link Scope#SMALL_MODELS}); the real
 * signals - the turn is parked on an approval, the provider's finish reason, whether the pass called
 * tools - are consulted by {@link TurnContinuation} first.
 *
 * <p>The patterns are model-facing and language-specific, so they are configuration, not Java literals.
 * Defaults are read from {@code /chat/continuation.properties}; the same values are in {@code
 * application.yml}. The nudge is not configuration: it is the prompt {@code nudge.continue_unfinished},
 * and {@code nudge.<lang>} is only a deprecated alias for its platform default. Registered by {@link
 * ContinuationConfiguration}.
 */
@ConfigurationProperties(prefix = "app.chat.continuation")
public class ContinuationProperties {

    /** Which models the wording check applies to. */
    public enum Scope {
        /** Only a weak tool caller: a small or text-graded hosted row, or a listed local provider. */
        SMALL_MODELS,
        /** Every model. */
        ALL_MODELS
    }

    private static final String DEFAULTS_RESOURCE = "/chat/continuation.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** False never re-prompts on wording. */
    private boolean heuristicEnabled = Boolean.parseBoolean(DEFAULTS.getProperty("heuristic-enabled"));

    private Scope heuristicScope = Scope.valueOf(DEFAULTS.getProperty("heuristic-scope"));

    /** Bring-your-own providers treated as weak tool callers (local servers). */
    private Set<ProviderName> heuristicProviders = enums(ProviderName.class, DEFAULTS.getProperty("heuristic-providers"));

    /** A platform-hosted pool row measured at one of these grades is a weak tool caller. */
    private Set<ToolCallGrade> heuristicToolGrades =
            enums(ToolCallGrade.class, DEFAULTS.getProperty("heuristic-tool-grades"));

    /** How much of the end of the reply is read. */
    private int tailChars = Integer.parseInt(DEFAULTS.getProperty("tail-chars"));

    /** The language whose nudge is used when no language matched. */
    private String defaultLocale = DEFAULTS.getProperty("default-locale");

    private Map<String, String> intent = byLocale("intent");
    private Map<String, String> closing = byLocale("closing");
    private Map<String, String> done = byLocale("done");
    private Map<String, String> asks = byLocale("asks");
    private Map<String, String> unfinishedEnding = byLocale("unfinished-ending");
    /**
     * DEPRECATED: the nudge is the prompt {@code nudge.continue_unfinished}. A language set here to a text other
     * than the shipped one is applied as that prompt's platform default (and logged); the default is the
     * shipped file, so nothing is duplicated. See {@link PromptPropertyAliases}.
     */
    private Map<String, String> nudge = new LinkedHashMap<>(NudgeTexts.shippedByLocale(NudgeTexts.CONTINUE_UNFINISHED));

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = ContinuationProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents-turn module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    private static <E extends Enum<E>> Set<E> enums(Class<E> type, String commaList) {
        Set<E> out = EnumSet.noneOf(type);
        for (String name : commaList.split(",")) {
            if (!name.isBlank()) {
                out.add(Enum.valueOf(type, name.strip()));
            }
        }
        return out;
    }

    /** The shipped {@code <prefix>.<language>} entries, keyed by language tag. */
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

    public boolean isHeuristicEnabled() {
        return heuristicEnabled;
    }

    public void setHeuristicEnabled(boolean heuristicEnabled) {
        this.heuristicEnabled = heuristicEnabled;
    }

    public Scope getHeuristicScope() {
        return heuristicScope;
    }

    public void setHeuristicScope(Scope heuristicScope) {
        this.heuristicScope = heuristicScope;
    }

    public Set<ProviderName> getHeuristicProviders() {
        return heuristicProviders;
    }

    public void setHeuristicProviders(Set<ProviderName> heuristicProviders) {
        this.heuristicProviders = heuristicProviders == null ? Set.of() : heuristicProviders;
    }

    public Set<ToolCallGrade> getHeuristicToolGrades() {
        return heuristicToolGrades;
    }

    public void setHeuristicToolGrades(Set<ToolCallGrade> heuristicToolGrades) {
        this.heuristicToolGrades = heuristicToolGrades == null ? Set.of() : heuristicToolGrades;
    }

    public int getTailChars() {
        return tailChars;
    }

    public void setTailChars(int tailChars) {
        this.tailChars = Math.max(1, tailChars);
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public void setDefaultLocale(String defaultLocale) {
        this.defaultLocale = defaultLocale;
    }

    public Map<String, String> getIntent() {
        return intent;
    }

    public void setIntent(Map<String, String> intent) {
        this.intent = intent;
    }

    public Map<String, String> getClosing() {
        return closing;
    }

    public void setClosing(Map<String, String> closing) {
        this.closing = closing;
    }

    public Map<String, String> getDone() {
        return done;
    }

    public void setDone(Map<String, String> done) {
        this.done = done;
    }

    public Map<String, String> getAsks() {
        return asks;
    }

    public void setAsks(Map<String, String> asks) {
        this.asks = asks;
    }

    public Map<String, String> getUnfinishedEnding() {
        return unfinishedEnding;
    }

    public void setUnfinishedEnding(Map<String, String> unfinishedEnding) {
        this.unfinishedEnding = unfinishedEnding;
    }

    public Map<String, String> getNudge() {
        return nudge;
    }

    public void setNudge(Map<String, String> nudge) {
        this.nudge = nudge;
    }
}
