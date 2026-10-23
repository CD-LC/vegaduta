package com.agenticai.core.agents.auxiliary;

import com.agenticai.core.providers.ProviderName;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.auxiliary-model.*}: which model the background calls use (context compaction, the session summary,
 * the "Ask AI" assist, judges) when the tenant uses its own provider key.
 *
 * <p>These calls used to take the first model of the first active credential's catalog listing, which is
 * alphabetical: the model (and its price) depended on sort order and on which provider key happened to come
 * first, not on any decision. Now the choice is explicit, in this order: the tenant's own preference (AI Model
 * Control), then the platform choice below, then the model of the agent the call is made for, then the provider's
 * catalog default. The default of this class sets nothing, so the agent's own model is used.
 *
 * <p>A platform choice names a provider (its credential must be active, else the next layer is used) and
 * optionally a model; a provider without a model means that provider's catalog default. A model without a
 * provider is ignored (a model id means nothing without its provider) and logged. Defaults are read from {@code
 * /chat/auxiliary-model.properties}; the same values are in {@code application.yml}. Registered by {@link
 * AuxiliaryModelConfiguration}.
 */
@ConfigurationProperties(prefix = "app.auxiliary-model")
public class AuxiliaryModelProperties {

    private static final String DEFAULTS_RESOURCE = "/chat/auxiliary-model.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** A provider and a model; either may be blank. */
    public static class Choice {

        private ProviderName provider;
        private String model = "";

        public ProviderName getProvider() {
            return provider;
        }

        public void setProvider(ProviderName provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model == null ? "" : model.strip();
        }

        /** True when nothing is set. */
        public boolean isEmpty() {
            return provider == null && model.isEmpty();
        }
    }

    /** True: with no platform choice, a call made for an agent uses that agent's own provider and model. */
    private boolean useAgentModel = Boolean.parseBoolean(DEFAULTS.getProperty("use-agent-model"));

    private Choice compaction = new Choice();
    private Choice sessionSummary = new Choice();
    private Choice assist = new Choice();
    private Choice judge = new Choice();

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = AuxiliaryModelProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents-turn module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    /** The platform choice for {@code purpose} (empty when none is configured). */
    public Choice choiceFor(AuxiliaryPurpose purpose) {
        return switch (purpose) {
            case COMPACTION -> compaction;
            case SESSION_SUMMARY -> sessionSummary;
            case ASSIST -> assist;
            case JUDGE -> judge;
        };
    }

    public boolean isUseAgentModel() {
        return useAgentModel;
    }

    public void setUseAgentModel(boolean useAgentModel) {
        this.useAgentModel = useAgentModel;
    }

    public Choice getCompaction() {
        return compaction;
    }

    public void setCompaction(Choice compaction) {
        this.compaction = compaction == null ? new Choice() : compaction;
    }

    public Choice getSessionSummary() {
        return sessionSummary;
    }

    public void setSessionSummary(Choice sessionSummary) {
        this.sessionSummary = sessionSummary == null ? new Choice() : sessionSummary;
    }

    public Choice getAssist() {
        return assist;
    }

    public void setAssist(Choice assist) {
        this.assist = assist == null ? new Choice() : assist;
    }

    public Choice getJudge() {
        return judge;
    }

    public void setJudge(Choice judge) {
        this.judge = judge == null ? new Choice() : judge;
    }
}
