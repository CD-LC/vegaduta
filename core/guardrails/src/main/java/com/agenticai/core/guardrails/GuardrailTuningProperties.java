package com.agenticai.core.guardrails;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The numbers and switches behind the guardrail checks that used to be compiled in: how much text each
 * scan or judge looks at, the toxicity block score, how the LLM judges are sampled, and what the
 * tool-call verifier does when it fails. Every default equals the value that was hard-coded before,
 * except {@link ToolVerifier#getFailMode()}, whose default now follows the global fail mode (see there).
 *
 * <ul>
 *   <li>{@code app.guardrails.judge.*} - {@link Judge}: the LLM judges (tool-output injection judge,
 *       toxicity judge, tool-call verifier, moderation policy-pack classifier);</li>
 *   <li>{@code app.guardrails.jailbreak.*} - {@link Jailbreak}: the pattern scan window;</li>
 *   <li>{@code app.guardrails.toxicity.*} - {@link Toxicity}: the block score;</li>
 *   <li>{@code app.guardrails.tool-verifier.*} - {@link ToolVerifier}: the verifier's failure mode;</li>
 *   <li>{@code app.guardrails.supervised.*} - {@link Supervised}: whether the SUPERVISED autonomy tier still asks a
 *       human before a classified read-only tool call.</li>
 * </ul>
 *
 * <p>Registered by {@link GuardrailTuningConfiguration}. Classes that are built with {@code new} (tests,
 * embedded use) fall back to {@code new GuardrailTuningProperties()}, i.e. these defaults.
 */
@ConfigurationProperties(prefix = "app.guardrails")
public class GuardrailTuningProperties {

    private final Judge judge = new Judge();
    private final Jailbreak jailbreak = new Jailbreak();
    private final Toxicity toxicity = new Toxicity();
    private final ToolVerifier toolVerifier = new ToolVerifier();
    private final Supervised supervised = new Supervised();

    public Supervised getSupervised() {
        return supervised;
    }

    public Judge getJudge() {
        return judge;
    }

    public Jailbreak getJailbreak() {
        return jailbreak;
    }

    public Toxicity getToxicity() {
        return toxicity;
    }

    public ToolVerifier getToolVerifier() {
        return toolVerifier;
    }

    /** {@code app.guardrails.judge.*}: input window and sampling of the guardrail LLM judges. */
    public static class Judge {

        /** The judge window the platform shipped with. */
        public static final int DEFAULT_MAX_INPUT_CHARS = 4000;

        /** Appended to text cut at the window. Model-facing, so configuration rather than a literal. */
        public static final String DEFAULT_TRUNCATION_MARKER = " …[truncated]";

        /**
         * How many characters of the checked text (a tool result, a user message, tool arguments) are put
         * in front of a judge. The rest is NOT judged, which is a deliberate bound on judge cost and on
         * how far padding can push the judge's own instructions out of its attention; raise it where
         * long tool results must be judged in full, at the price of one larger judge call per result.
         */
        private int maxInputChars = DEFAULT_MAX_INPUT_CHARS;

        /** Marker appended to text cut at {@link #maxInputChars}. */
        private String truncationMarker = DEFAULT_TRUNCATION_MARKER;

        /**
         * Sampling temperature for judge calls. Unset (the default) keeps whatever the calling agent's
         * own chat client uses, which is what the judges did before this setting existed; an agent at
         * temperature 0.9 then gets a noisy verdict. Set it (0.0 is the usual choice) to pin judges.
         */
        private Double temperature;

        /**
         * Output-token cap for judge calls. Unset keeps the agent's own cap; an agent configured with a
         * very small cap can truncate a verdict, so set this where agents are tuned that way. The verdicts
         * are one line, a few dozen tokens is plenty.
         */
        private Integer maxTokens;

        /**
         * Model id for judge calls, sent as a per-call override on the same provider and credentials as
         * the calling agent's chat client (it does NOT switch provider). Blank (the default) uses the
         * agent's model. Use it to judge with a cheaper or stronger model of the same provider.
         */
        private String model;

        /** The text a judge sees: {@code text} cut to {@link #getMaxInputChars()} plus the marker. */
        public String truncate(String text) {
            return truncate(text, maxInputChars);
        }

        /** {@code text} cut to {@code limit} characters plus the marker (the window the settings catalog resolved for a workspace or agent). */
        public String truncate(String text, int limit) {
            if (text == null || text.length() <= limit) {
                return text;
            }
            return text.substring(0, limit) + (truncationMarker == null ? "" : truncationMarker);
        }

        /** Whether any of temperature, max tokens or model is set, i.e. whether {@link #apply} changes the call. */
        public boolean overridesCall() {
            return temperature != null || maxTokens != null || (model != null && !model.isBlank());
        }

        /**
         * {@code spec} with the judge sampling applied as per-call options. With nothing set this returns
         * {@code spec} untouched, so a default configuration changes nothing about the call.
         */
        public ChatClient.ChatClientRequestSpec apply(ChatClient.ChatClientRequestSpec spec) {
            if (!overridesCall()) {
                return spec;
            }
            ChatOptions.Builder options = ChatOptions.builder();
            if (temperature != null) {
                options.temperature(temperature);
            }
            if (maxTokens != null) {
                options.maxTokens(maxTokens);
            }
            if (model != null && !model.isBlank()) {
                options.model(model.trim());
            }
            return spec.options(options.build());
        }

        public int getMaxInputChars() {
            return maxInputChars;
        }

        public void setMaxInputChars(int maxInputChars) {
            if (maxInputChars > 0) {
                this.maxInputChars = maxInputChars;
            }
        }

        public String getTruncationMarker() {
            return truncationMarker;
        }

        public void setTruncationMarker(String truncationMarker) {
            this.truncationMarker = truncationMarker;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens != null && maxTokens > 0 ? maxTokens : null;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    /** {@code app.guardrails.jailbreak.*}. */
    public static class Jailbreak {

        /** The scan window the platform shipped with. */
        public static final int DEFAULT_MAX_SCANNED_CHARS = 16_000;

        /**
         * How many characters of a message or reply the jailbreak patterns scan. Text beyond it is
         * ignored, not rejected (see {@code JailbreakDetectorGuardrail}), so raising it closes the
         * "pad the payload past the window" bypass at the cost of regex time; the patterns are bounded,
         * so the cost is linear.
         */
        private int maxScannedChars = DEFAULT_MAX_SCANNED_CHARS;

        public int getMaxScannedChars() {
            return maxScannedChars;
        }

        public void setMaxScannedChars(int maxScannedChars) {
            if (maxScannedChars > 0) {
                this.maxScannedChars = maxScannedChars;
            }
        }
    }

    /** {@code app.guardrails.toxicity.*}. */
    public static class Toxicity {

        /** The block score the platform shipped with. */
        public static final int DEFAULT_BLOCK_SCORE = 60;

        /**
         * The judge's 0-100 toxicity score at or above which content is blocked. Lower blocks more
         * (a 41-60 reply is "moderately toxic, hostile tone" in the judge prompt), higher blocks only
         * clearly abusive content.
         */
        private int blockScore = DEFAULT_BLOCK_SCORE;

        public int getBlockScore() {
            return blockScore;
        }

        public void setBlockScore(int blockScore) {
            if (blockScore >= 0 && blockScore <= 100) {
                this.blockScore = blockScore;
            }
        }
    }

    /** {@code app.guardrails.tool-verifier.*}: the RIGOROUS tool-call verifier. */
    public static class ToolVerifier {

        /** Which way the verifier goes when it cannot give a verdict. */
        public enum FailMode {
            /** Follow {@code app.guardrails.fail-mode} (the default). */
            INHERIT,
            /** An erroring or blank verifier approves the call. */
            OPEN,
            /** An erroring or blank verifier refuses the call. */
            CLOSED
        }

        /**
         * What the tool-call verifier does when its model call errors or answers nothing. {@code inherit}
         * (the default) uses {@code app.guardrails.fail-mode}, so the verifier agrees with the injection
         * judge and the dispatch gate; before this setting the verifier always failed open. {@code open}
         * and {@code closed} pin it independently of the global mode. A missing chat client is "not
         * configured", not a failure, and approves in every mode.
         */
        private FailMode failMode = FailMode.INHERIT;

        /**
         * The reason the model is given when the verifier refuses a call because it could not complete
         * (fail-closed). Model-facing, so configuration rather than a literal.
         */
        private String unavailableReason = "the tool-call check could not be completed - failed closed";

        /** The mode in force now: this setting, or the global mode when it is {@code inherit}. */
        public GuardrailFailMode effective() {
            return switch (failMode) {
                case OPEN -> GuardrailFailMode.OPEN;
                case CLOSED -> GuardrailFailMode.CLOSED;
                case INHERIT -> GuardrailFailMode.current();
            };
        }

        public FailMode getFailMode() {
            return failMode;
        }

        public void setFailMode(FailMode failMode) {
            this.failMode = failMode == null ? FailMode.INHERIT : failMode;
        }

        /**
         * How many of the latest messages of the conversation (both sides, oldest first, within {@code
         * app.guardrails.judge.max-input-chars}) the verifier is shown in front of the current user message.
         * The verifier used to judge the current message alone, so a follow-up such as "yes, send it" was
         * judged against "yes". 0 restores that.
         */
        private int contextMessages = DEFAULT_CONTEXT_MESSAGES;

        /** The shipped size of the conversation tail the verifier is shown. */
        public static final int DEFAULT_CONTEXT_MESSAGES = 6;

        public int getContextMessages() {
            return contextMessages;
        }

        public void setContextMessages(int contextMessages) {
            this.contextMessages = Math.max(0, contextMessages);
        }

        public String getUnavailableReason() {
            return unavailableReason;
        }

        public void setUnavailableReason(String unavailableReason) {
            if (unavailableReason != null && !unavailableReason.isBlank()) {
                this.unavailableReason = unavailableReason;
            }
        }
    }

    /** {@code app.guardrails.supervised.*}: what the SUPERVISED autonomy tier gates. */
    public static class Supervised {

        /** The shipped behaviour: SUPERVISED gates every tool call. The {@code personal} profile sets {@code false}. */
        public static final boolean DEFAULT_GATE_READ_ONLY = true;

        /**
         * {@code true} (the default): the SUPERVISED tier asks a human before EVERY tool call, including a search or a
         * lookup. {@code false}: a call is let through without approval only when ALL of these hold: the tool has an
         * explicit classification row that says read-only (a stored row or a compiled-in declaration; never an
         * unclassified tool, never an MCP server's own hint), the agent does not hold the lethal trifecta, and nothing
         * else asks for approval (taint, a procedure, a hook, the confirmation gate). Argument policies, hooks, budget
         * and step limits, the circuit breaker and every other guard still run on the call; each call let through this
         * way is audited and journaled as "auto-allowed: read-only under SUPERVISED". A security setting: a tenant or
         * an agent may only turn this back to {@code true}, never to {@code false}.
         */
        private boolean gateReadOnly = DEFAULT_GATE_READ_ONLY;

        public boolean isGateReadOnly() {
            return gateReadOnly;
        }

        public void setGateReadOnly(boolean gateReadOnly) {
            this.gateReadOnly = gateReadOnly;
        }
    }
}
