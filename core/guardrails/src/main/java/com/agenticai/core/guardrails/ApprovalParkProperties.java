package com.agenticai.core.guardrails;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.guardrails.approval-park.*}: what {@link GuardedToolCallback} tells the model when a
 * tool call is held for human approval, and how many follow-up calls it will queue behind the first.
 *
 * <p>The texts are model-facing instructions, so they are configuration rather than Java literals:
 * they must say the action is awaiting approval, that the task resumes automatically once it is
 * decided, and to tell the user briefly and end the turn - WITHOUT telling the model to abandon the
 * goal, which would contradict the standing "work to completion" directive. Defaults are read from
 * {@code /guardrails/approval-park.properties} on the classpath (the same text is in {@code
 * application.yml}); an operator overrides any of them in configuration.
 *
 * <p>Placeholders: {@code {tool}} (the tool the text is about), {@code {requestId}} (its approval
 * request), {@code {parkedTool}} (the call the turn is parked behind). Registered by {@link
 * ApprovalParkConfiguration}.
 */
@ConfigurationProperties(prefix = "app.guardrails.approval-park")
public class ApprovalParkProperties {

    private static final String DEFAULTS_RESOURCE = "/guardrails/approval-park.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** Tool result for the call that parks the turn. */
    private String awaitingMessage = DEFAULTS.getProperty("awaiting-message");

    /** Tool result for a call made after the turn parked, once it has been queued as an approval. */
    private String queuedMessage = DEFAULTS.getProperty("queued-message");

    /** Tool result for a call made after the park when the queue is already at its limit. */
    private String queueFullMessage = DEFAULTS.getProperty("queue-full-message");

    /** Tool result when the approval request itself could not be recorded (nothing is waiting). */
    private String recordFailedMessage = DEFAULTS.getProperty("record-failed-message");

    /**
     * How many calls made after the park are recorded as their own pending approvals. Beyond it a
     * call is refused unrecorded, so a model that keeps calling tools cannot flood the approval
     * queue. {@code 0} queues nothing (every later call gets the queue-full text).
     */
    private int maxQueuedCalls = Integer.parseInt(DEFAULTS.getProperty("max-queued-calls"));

    /**
     * How much of the user message that started the parked turn is stored in the approval's replay
     * envelope, to restate the goal when the task is continued after the decision. Longer messages
     * are cut at this length (with a marker); {@code 0} stores no goal, which makes the request
     * replayable but not continuable.
     */
    private int maxGoalChars = Integer.parseInt(DEFAULTS.getProperty("max-goal-chars"));

    /**
     * Appended to a goal cut at {@link #maxGoalChars}, so the agent reading it when the task is
     * continued knows the message was shortened. Model-facing.
     */
    private String goalCutMarker = DEFAULTS.getProperty("goal-cut-marker");

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = ApprovalParkProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the guardrails module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    /** Fills {@code {tool}}, {@code {requestId}} and {@code {parkedTool}} in {@code template}. */
    static String render(String template, String tool, String requestId, String parkedTool) {
        String text = template == null ? "" : template;
        return text.replace("{tool}", String.valueOf(tool))
            .replace("{requestId}", String.valueOf(requestId))
            .replace("{parkedTool}", String.valueOf(parkedTool));
    }

    public String getAwaitingMessage() {
        return awaitingMessage;
    }

    public void setAwaitingMessage(String awaitingMessage) {
        this.awaitingMessage = awaitingMessage;
    }

    public String getQueuedMessage() {
        return queuedMessage;
    }

    public void setQueuedMessage(String queuedMessage) {
        this.queuedMessage = queuedMessage;
    }

    public String getQueueFullMessage() {
        return queueFullMessage;
    }

    public void setQueueFullMessage(String queueFullMessage) {
        this.queueFullMessage = queueFullMessage;
    }

    public String getRecordFailedMessage() {
        return recordFailedMessage;
    }

    public void setRecordFailedMessage(String recordFailedMessage) {
        this.recordFailedMessage = recordFailedMessage;
    }

    public int getMaxQueuedCalls() {
        return maxQueuedCalls;
    }

    public void setMaxQueuedCalls(int maxQueuedCalls) {
        this.maxQueuedCalls = Math.max(0, maxQueuedCalls);
    }

    public String getGoalCutMarker() {
        return goalCutMarker;
    }

    public void setGoalCutMarker(String goalCutMarker) {
        this.goalCutMarker = goalCutMarker;
    }

    public int getMaxGoalChars() {
        return maxGoalChars;
    }

    public void setMaxGoalChars(int maxGoalChars) {
        this.maxGoalChars = Math.max(0, maxGoalChars);
    }
}
