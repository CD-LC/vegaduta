package com.agenticai.core.agents.turn;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.chat.finish.*}: what a chat turn does with the provider's finish reason. The tool loop
 * runs inside Spring AI, so the application used to see only the final text and treated every reply
 * that arrived as an answer - a reply cut off at the output limit was shown, stored and cached as
 * complete, and a reply the provider's safety system stopped surfaced as "could you try rephrasing".
 *
 * <ul>
 *   <li>cut off at the output limit: the model is asked to continue, up to {@link
 *       #getMaxContinuations()} times, and the pieces are joined; if it is still cut off the person
 *       is told so;
 *   <li>stopped by the safety system: the person is told so, in {@link #getRefusalNotice()};
 *   <li>an unusable tool call: the model is asked once more, then the person is told;
 *   <li>none of those (nor the empty-reply line, nor a reply closed after a failure) is cached or
 *       stored as something the agent said.
 * </ul>
 *
 * <p>The numbers and the switch are read from {@code /chat/finish.properties} on the classpath; the same values
 * are in {@code application.yml}. Every text here is a prompt-registry key: the two nudges (model-facing, {@code
 * nudge.reply_cut_off}, {@code nudge.retry_tool_call}) and the four notices the person reads ({@code
 * message.finish.truncated|refusal|malformed_tool_call|empty_reply}). The text properties are deprecated seed
 * values: one that differs from the shipped text is applied as the platform default of its key (see {@link
 * PromptPropertyAliases}), so a tenant or agent override still wins over it.
 */
@ConfigurationProperties(prefix = "app.chat.finish")
public class ChatFinishProperties {

    private static final String DEFAULTS_RESOURCE = "/chat/finish.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /**
     * Master switch for acting on the provider's finish reason (continue, refuse, retry). Off, the
     * reason is ignored as before. Not affected: an empty or interrupted reply is still never cached
     * or stored as an answer, and the empty-reply line is still configurable.
     */
    private boolean enabled = Boolean.parseBoolean(DEFAULTS.getProperty("enabled"));

    /**
     * Extra model passes allowed to finish a reply cut off by the output limit. Each pass is billed
     * like any other call. {@code 0} never continues: the person is told the reply was cut off.
     */
    private int maxContinuations = Integer.parseInt(DEFAULTS.getProperty("max-continuations"));

    /** Extra model passes allowed after a tool call the provider could not use. {@code 0} never retries. */
    private int maxToolCallRetries = Integer.parseInt(DEFAULTS.getProperty("max-tool-call-retries"));

    /**
     * DEPRECATED: the prompt {@code nudge.reply_cut_off}. A value other than the shipped text is applied as that
     * prompt's platform default (and logged); the default is the shipped file. See {@link PromptPropertyAliases}.
     */
    private String continueNudge = NudgeTexts.shipped(NudgeTexts.REPLY_CUT_OFF);

    /**
     * DEPRECATED: the prompt {@code nudge.retry_tool_call}; same alias rule as {@link #continueNudge}.
     */
    private String retryToolCallNudge = NudgeTexts.shipped(NudgeTexts.RETRY_TOOL_CALL);

    /**
     * DEPRECATED: the prompt {@code message.finish.truncated}. Shown after a reply that is still cut off when the
     * continuation passes are used up. Same alias rule as {@link #continueNudge}.
     */
    private String truncatedNotice = NudgeTexts.shipped(ReplyFinish.TRUNCATED_KEY);

    /**
     * DEPRECATED: the prompt {@code message.finish.refusal}. Shown when the provider's safety system stopped the
     * reply; {@code {reason}} is the provider's reason.
     */
    private String refusalNotice = NudgeTexts.shipped(ReplyFinish.REFUSAL_KEY);

    /**
     * DEPRECATED: the prompt {@code message.finish.malformed_tool_call}. Shown when the model's tool call was
     * unusable and the retries did not fix it.
     */
    private String malformedToolCallNotice = NudgeTexts.shipped(ReplyFinish.MALFORMED_TOOL_CALL_KEY);

    /** DEPRECATED: the prompt {@code message.finish.empty_reply}. Shown when the model returned nothing at all. */
    private String emptyReplyMessage = NudgeTexts.shipped(ReplyFinish.EMPTY_REPLY_KEY);

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = ChatFinishProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents-turn module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    /** The shipped default of {@code empty-reply-message}, for the constant tests compare against. */
    static String defaultEmptyReplyMessage() {
        return NudgeTexts.shipped(ReplyFinish.EMPTY_REPLY_KEY);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxContinuations() {
        return maxContinuations;
    }

    public void setMaxContinuations(int maxContinuations) {
        this.maxContinuations = Math.max(0, maxContinuations);
    }

    public int getMaxToolCallRetries() {
        return maxToolCallRetries;
    }

    public void setMaxToolCallRetries(int maxToolCallRetries) {
        this.maxToolCallRetries = Math.max(0, maxToolCallRetries);
    }

    public String getContinueNudge() {
        return continueNudge;
    }

    public void setContinueNudge(String continueNudge) {
        this.continueNudge = continueNudge;
    }

    public String getRetryToolCallNudge() {
        return retryToolCallNudge;
    }

    public void setRetryToolCallNudge(String retryToolCallNudge) {
        this.retryToolCallNudge = retryToolCallNudge;
    }

    public String getTruncatedNotice() {
        return truncatedNotice;
    }

    public void setTruncatedNotice(String truncatedNotice) {
        this.truncatedNotice = truncatedNotice;
    }

    public String getRefusalNotice() {
        return refusalNotice;
    }

    public void setRefusalNotice(String refusalNotice) {
        this.refusalNotice = refusalNotice;
    }

    public String getMalformedToolCallNotice() {
        return malformedToolCallNotice;
    }

    public void setMalformedToolCallNotice(String malformedToolCallNotice) {
        this.malformedToolCallNotice = malformedToolCallNotice;
    }

    public String getEmptyReplyMessage() {
        return emptyReplyMessage;
    }

    public void setEmptyReplyMessage(String emptyReplyMessage) {
        this.emptyReplyMessage = emptyReplyMessage;
    }

    /** {@link #getRefusalNotice()} with {@code {reason}} filled in. */
    public String renderRefusalNotice(String providerReason) {
        String reason = providerReason == null ? "" : providerReason.strip();
        return refusalNotice.replace("{reason}", reason);
    }
}
