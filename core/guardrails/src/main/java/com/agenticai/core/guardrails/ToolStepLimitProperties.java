package com.agenticai.core.guardrails;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.guardrails.step-limit.*}: what happens to a tool call made after a turn has used up its
 * tool-call ceiling ({@code app.guardrails.max-tool-calls-per-turn}), and how an agent's autonomy tier
 * scales that ceiling.
 *
 * <p><b>Calls and rounds.</b> A <em>call</em> is one tool invocation; the ceiling counts calls across
 * the whole turn (every model pass of it, parallel calls each counting once). A <em>round</em> is one
 * model-then-tools iteration inside a single model invocation, and {@code
 * app.chat.tools.max-rounds-per-invocation} bounds those as a backstop. Every executed round makes at
 * least one call, so the call ceiling is the budget and the round cap only matters for loops that make
 * no countable calls (a model inventing tool names); by default it is derived from the highest call
 * ceiling plus the grace below, so it never ends a turn before the call policy has run its course.
 *
 * <p><b>Two stages past the ceiling.</b> The first {@link #getGraceCalls()} calls over the ceiling are
 * not run and are answered with the same {@code tool_budget_exhausted} tool result the tool loop uses
 * for its round cap ({@link ToolBudgetExhausted}), so the model can write a normal final answer. A model
 * that still keeps calling tools after that is looping, and the next call throws {@link
 * ToolStepLimitExceededException}, which stops the turn with an error.
 *
 * <p>{@link #getExhaustedMessage()} is model-facing text, so it is configuration rather than a Java
 * literal; the default is read from {@code /guardrails/step-limit.properties} (the same text is in
 * {@code application.yml}). Registered by {@link ToolLimitsConfiguration}.
 */
@ConfigurationProperties(prefix = "app.guardrails.step-limit")
public class ToolStepLimitProperties {

    private static final String DEFAULTS_RESOURCE = "/guardrails/step-limit.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /**
     * Calls past the ceiling that are answered with the budget-exhausted result before the turn is
     * stopped with an error. Parallel calls of the round that crossed the ceiling count here, so keep it
     * above the most calls a model makes at once. {@code 0} stops the turn at the first call over the
     * ceiling.
     */
    private int graceCalls = Integer.parseInt(DEFAULTS.getProperty("grace-calls"));

    /** SUPERVISED agents get {@code ceiling / supervisedDivisor} calls, but never fewer than {@link #supervisedMinSteps}. */
    private int supervisedDivisor = Integer.parseInt(DEFAULTS.getProperty("supervised-divisor"));

    /** The least a SUPERVISED agent gets, so it can still finish a trivial multi-step task. */
    private int supervisedMinSteps = Integer.parseInt(DEFAULTS.getProperty("supervised-min-steps"));

    /** FULLY_AUTONOMOUS agents get {@code ceiling * autonomousMultiplier} calls. */
    private int autonomousMultiplier = Integer.parseInt(DEFAULTS.getProperty("autonomous-multiplier"));

    /** The tool result for a call made after the ceiling; {@code {limit}} is the ceiling that applied. */
    private String exhaustedMessage = DEFAULTS.getProperty("exhausted-message");

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = ToolStepLimitProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the guardrails module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    /**
     * A copy of these settings with the four numbers replaced, for one agent's turn (the tenant's or the agent's
     * {@code agent.steps.*} settings): the shared platform object is never changed. The exhausted-call message is kept.
     */
    public ToolStepLimitProperties withNumbers(int graceCalls, int supervisedDivisor, int supervisedMinSteps, int autonomousMultiplier) {
        ToolStepLimitProperties copy = new ToolStepLimitProperties();
        copy.setGraceCalls(graceCalls);
        copy.setSupervisedDivisor(supervisedDivisor);
        copy.setSupervisedMinSteps(supervisedMinSteps);
        copy.setAutonomousMultiplier(autonomousMultiplier);
        copy.setExhaustedMessage(exhaustedMessage);
        return copy;
    }

    /** {@link #withNumbers} for the grace alone: the same settings with this many calls of grace past the ceiling. */
    public ToolStepLimitProperties withGraceCalls(int graceCalls) {
        return withNumbers(graceCalls, supervisedDivisor, supervisedMinSteps, autonomousMultiplier);
    }

    /** What a call made as number {@code step} of a turn whose ceiling is {@code ceiling} gets. */
    public Stage stageOf(int step, int ceiling) {
        if (step <= ceiling) {
            return Stage.WITHIN;
        }
        return step - ceiling <= graceCalls ? Stage.EXHAUSTED : Stage.ABORT;
    }

    /** The ceiling for a SUPERVISED agent, given the configured base. */
    public int supervisedCeiling(int base) {
        return Math.max(supervisedMinSteps, base / Math.max(1, supervisedDivisor));
    }

    /** The ceiling for a FULLY_AUTONOMOUS agent, given the configured base. */
    public int autonomousCeiling(int base) {
        return base * Math.max(1, autonomousMultiplier);
    }

    /** The tool result for a call over the ceiling: the shared {@code tool_budget_exhausted} shape. */
    public String exhaustedResult(int ceiling) {
        String message = exhaustedMessage == null ? "" : exhaustedMessage.replace("{limit}", String.valueOf(ceiling));
        return ToolBudgetExhausted.result(message);
    }

    /** Where a call stands against the turn's ceiling. */
    public enum Stage {
        /** Within the ceiling: the call runs. */
        WITHIN,
        /** Over the ceiling: not run; the model is handed the budget-exhausted result. */
        EXHAUSTED,
        /** Over the ceiling and past the grace: the turn is stopped with an error. */
        ABORT
    }

    public int getGraceCalls() {
        return graceCalls;
    }

    public void setGraceCalls(int graceCalls) {
        this.graceCalls = Math.max(0, graceCalls);
    }

    public int getSupervisedDivisor() {
        return supervisedDivisor;
    }

    public void setSupervisedDivisor(int supervisedDivisor) {
        this.supervisedDivisor = Math.max(1, supervisedDivisor);
    }

    public int getSupervisedMinSteps() {
        return supervisedMinSteps;
    }

    public void setSupervisedMinSteps(int supervisedMinSteps) {
        this.supervisedMinSteps = Math.max(1, supervisedMinSteps);
    }

    public int getAutonomousMultiplier() {
        return autonomousMultiplier;
    }

    public void setAutonomousMultiplier(int autonomousMultiplier) {
        this.autonomousMultiplier = Math.max(1, autonomousMultiplier);
    }

    public String getExhaustedMessage() {
        return exhaustedMessage;
    }

    public void setExhaustedMessage(String exhaustedMessage) {
        this.exhaustedMessage = exhaustedMessage;
    }
}
