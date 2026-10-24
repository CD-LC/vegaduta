package com.agenticai.core.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.agent-turn.repair.*}: what the hosted agent-turn endpoint ({@code POST
 * /api/completions/agent-turn}) does when the model's tool call arrives with arguments that cannot
 * be used (invalid or cut-off JSON). The endpoint never runs tools, so an unusable call cannot be
 * given an error result by a tool; instead the server hands the model that error as the call's
 * result and asks again, up to {@link #getMaxAttempts()} times, and only then tells the client the
 * call could not be used. Before, such a call reached the client with EMPTY arguments and was run
 * that way.
 *
 * <p>The messages are model-facing (or user-facing) text, so they are configuration rather than
 * Java literals. Defaults are read from {@code /agent-turn/repair.properties}; the same values are
 * in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "app.agent-turn.repair")
public class AgentTurnRepairProperties {

    private static final String DEFAULTS_RESOURCE = "/agent-turn/repair.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /**
     * How many extra model calls one turn may spend asking again after a tool call with unusable
     * arguments. Each one is billed like the first (the reservation covers a single call, usage is
     * summed at settlement). {@code 0} never asks again: the unusable call is dropped and the client
     * is told.
     */
    private int maxAttempts = Integer.parseInt(DEFAULTS.getProperty("max-attempts"));

    /** The result handed to the model for the call whose arguments were unusable. */
    private String invalidArgumentsMessage = DEFAULTS.getProperty("invalid-arguments-message");

    /** The result handed to the model for every other call of the same reply (none was run). */
    private String skippedMessage = DEFAULTS.getProperty("skipped-message");

    /** {@code {reason}} when the arguments are cut off and the reply hit the output length limit. */
    private String invalidReasonCutOff = DEFAULTS.getProperty("invalid-reason-cut-off");

    /** {@code {reason}} when the arguments are not valid JSON for another reason. */
    private String invalidReasonMalformed = DEFAULTS.getProperty("invalid-reason-malformed");

    /** The text returned to the client when the calls are still unusable after the last attempt. */
    private String unusableCallsNotice = DEFAULTS.getProperty("unusable-calls-notice");

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = AgentTurnRepairProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the chat module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(0, maxAttempts);
    }

    public String getInvalidArgumentsMessage() {
        return invalidArgumentsMessage;
    }

    public void setInvalidArgumentsMessage(String invalidArgumentsMessage) {
        this.invalidArgumentsMessage = invalidArgumentsMessage;
    }

    public String getSkippedMessage() {
        return skippedMessage;
    }

    public void setSkippedMessage(String skippedMessage) {
        this.skippedMessage = skippedMessage;
    }

    public String getInvalidReasonCutOff() {
        return invalidReasonCutOff;
    }

    public void setInvalidReasonCutOff(String invalidReasonCutOff) {
        this.invalidReasonCutOff = invalidReasonCutOff;
    }

    public String getInvalidReasonMalformed() {
        return invalidReasonMalformed;
    }

    public void setInvalidReasonMalformed(String invalidReasonMalformed) {
        this.invalidReasonMalformed = invalidReasonMalformed;
    }

    public String getUnusableCallsNotice() {
        return unusableCallsNotice;
    }

    public void setUnusableCallsNotice(String unusableCallsNotice) {
        this.unusableCallsNotice = unusableCallsNotice;
    }

    /** Fills {@code {tool}} and {@code {reason}} in {@code template}. */
    String render(String template, String tool, String reason) {
        return template.replace("{tool}", String.valueOf(tool)).replace("{reason}", String.valueOf(reason));
    }
}
