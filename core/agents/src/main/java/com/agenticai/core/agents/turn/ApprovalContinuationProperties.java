package com.agenticai.core.agents.turn;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.approvals.continuation.*}: how the platform continues a task after a human decided one
 * of its gated tool calls - what it sends the agent, how much of the tool's output it includes, and
 * the limits around the turn it starts.
 *
 * <p>The message bodies are model-facing instructions, so they are configuration rather than Java
 * literals. Defaults are read from {@code /approval/continuation.properties} on the classpath (the
 * same values are in {@code application.yml}); an operator overrides any of them in configuration.
 * What stays in code is only what must not be editable: the {@code <approval_result ...>} opening
 * tag the stored conversation is recognised by, and the neutralising of untrusted tool output.
 *
 * <p>Registered by {@link ApprovalContinuationConfiguration}.
 */
@ConfigurationProperties(prefix = "app.approvals.continuation")
public class ApprovalContinuationProperties {

    private static final String DEFAULTS_RESOURCE = "/approval/continuation.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /**
     * Master switch. Off, a decision only runs (or refuses) the tool and writes the history note;
     * nothing continues the task, as before this feature.
     */
    private boolean enabled = Boolean.parseBoolean(DEFAULTS.getProperty("enabled"));

    /**
     * Longest tool output (or failure text, or denial reason) put into the continuation message.
     * Longer output is cut with {@link #resultShortenedSuffix}. The approval request itself keeps
     * up to 32 KiB of it. {@code 0} sends no output (only that the call ran).
     */
    private int maxResultChars = Integer.parseInt(DEFAULTS.getProperty("max-result-chars"));

    /** Longest argument JSON shown in the continuation message. */
    private int maxArgumentsChars = Integer.parseInt(DEFAULTS.getProperty("max-arguments-chars"));

    /** How many of the conversation's earlier tools are kept on offer for the continuation turn. */
    private int maxStickyTools = Integer.parseInt(DEFAULTS.getProperty("max-sticky-tools"));

    /** How far back in the tool-call log "tools this conversation already used" looks. */
    private Duration stickyToolsWindow = Duration.parse(DEFAULTS.getProperty("sticky-tools-window"));

    /**
     * How long the record that a decision has been continued is kept; a repeat of the same decision
     * inside it (a retry, a duplicate event) does not start the task again.
     */
    private Duration claimTtl = Duration.parse(DEFAULTS.getProperty("claim-ttl"));

    /** Wall-clock limit of the continuation turn the platform starts for a chat session. */
    private Duration turnTimeout = Duration.parse(DEFAULTS.getProperty("turn-timeout"));

    /** How many other still-pending actions of the same conversation are listed to the agent. */
    private int maxOtherPending = Integer.parseInt(DEFAULTS.getProperty("max-other-pending"));

    /**
     * Agent jobs only. {@code false} (default): a denied approval ends the job as {@code
     * APPROVAL_REJECTED}, as before. {@code true}: the job resumes with the denial as the result of
     * the call, so the agent can reach the goal another way (still bounded by the job's park limit).
     */
    private boolean jobsContinueAfterDenial =
            Boolean.parseBoolean(DEFAULTS.getProperty("jobs-continue-after-denial"));

    /** Appended to a shortened result; {@code {omitted}} is the number of characters left out. */
    private String resultShortenedSuffix = DEFAULTS.getProperty("result-shortened-suffix");

    /** Names the decider when none is recorded. */
    private String approverUnknown = DEFAULTS.getProperty("approver-unknown");

    /** Stands in for a denial reason when the reviewer gave none. */
    private String noReasonText = DEFAULTS.getProperty("no-reason-text");

    /** The result given to the agent for an approved call whose output was never recorded. */
    private String resultNotRecordedText = DEFAULTS.getProperty("result-not-recorded-text");

    /**
     * The bracketed note appended to the conversation history when a call is denied ({@code
     * {tool}}, {@code {approver}}, {@code {reason}}); the counterpart of the "approved action ...
     * completed" note the executor writes for a call that ran.
     */
    private String deniedNote = DEFAULTS.getProperty("denied-note");

    /**
     * The bracketed note appended to the conversation history when an approved call ran ({@code {tool}},
     * {@code {result}}: the output, cut to {@link #maxNoteResultChars}). Written for every kind of approved
     * action, whichever executor ran it.
     */
    private String completedNote = DEFAULTS.getProperty("completed-note");

    /** The note when an approved call failed for good ({@code {tool}}, {@code {failure}}). */
    private String failedNote = DEFAULTS.getProperty("failed-note");

    /** Stands in for the output of an approved call that ran but produced none (a message sent, say). */
    private String completedNoOutputText = DEFAULTS.getProperty("completed-no-output-text");

    /**
     * Longest output or failure text put into a history note. A note is re-sent to the model on every
     * later turn of the conversation, so it is far tighter than {@link #maxResultChars}, which governs the
     * one continuation message that carries the full result.
     */
    private int maxNoteResultChars = Integer.parseInt(DEFAULTS.getProperty("max-note-result-chars"));

    /** Introduces the list of other actions still waiting for a human decision. */
    private String otherPendingHeader = DEFAULTS.getProperty("other-pending-header");

    /** Message body when the approved call ran. */
    private String completedMessage = DEFAULTS.getProperty("completed-message");

    /** Message body when the approved call could not be completed. */
    private String failedMessage = DEFAULTS.getProperty("failed-message");

    /** Message body when a human denied the call. */
    private String deniedMessage = DEFAULTS.getProperty("denied-message");

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = ApprovalContinuationProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxResultChars() {
        return maxResultChars;
    }

    public void setMaxResultChars(int maxResultChars) {
        this.maxResultChars = Math.max(0, maxResultChars);
    }

    public int getMaxArgumentsChars() {
        return maxArgumentsChars;
    }

    public void setMaxArgumentsChars(int maxArgumentsChars) {
        this.maxArgumentsChars = Math.max(0, maxArgumentsChars);
    }

    public int getMaxStickyTools() {
        return maxStickyTools;
    }

    public void setMaxStickyTools(int maxStickyTools) {
        this.maxStickyTools = Math.max(0, maxStickyTools);
    }

    public Duration getStickyToolsWindow() {
        return stickyToolsWindow;
    }

    public void setStickyToolsWindow(Duration stickyToolsWindow) {
        this.stickyToolsWindow = stickyToolsWindow;
    }

    public Duration getClaimTtl() {
        return claimTtl;
    }

    public void setClaimTtl(Duration claimTtl) {
        this.claimTtl = claimTtl;
    }

    public Duration getTurnTimeout() {
        return turnTimeout;
    }

    public void setTurnTimeout(Duration turnTimeout) {
        this.turnTimeout = turnTimeout;
    }

    public int getMaxOtherPending() {
        return maxOtherPending;
    }

    public void setMaxOtherPending(int maxOtherPending) {
        this.maxOtherPending = Math.max(0, maxOtherPending);
    }

    public boolean isJobsContinueAfterDenial() {
        return jobsContinueAfterDenial;
    }

    public void setJobsContinueAfterDenial(boolean jobsContinueAfterDenial) {
        this.jobsContinueAfterDenial = jobsContinueAfterDenial;
    }

    public String getResultShortenedSuffix() {
        return resultShortenedSuffix;
    }

    public void setResultShortenedSuffix(String resultShortenedSuffix) {
        this.resultShortenedSuffix = resultShortenedSuffix;
    }

    public String getApproverUnknown() {
        return approverUnknown;
    }

    public void setApproverUnknown(String approverUnknown) {
        this.approverUnknown = approverUnknown;
    }

    public String getNoReasonText() {
        return noReasonText;
    }

    public void setNoReasonText(String noReasonText) {
        this.noReasonText = noReasonText;
    }

    public String getResultNotRecordedText() {
        return resultNotRecordedText;
    }

    public void setResultNotRecordedText(String resultNotRecordedText) {
        this.resultNotRecordedText = resultNotRecordedText;
    }

    public String getDeniedNote() {
        return deniedNote;
    }

    public void setDeniedNote(String deniedNote) {
        this.deniedNote = deniedNote;
    }

    public String getCompletedNote() {
        return completedNote;
    }

    public void setCompletedNote(String completedNote) {
        this.completedNote = completedNote;
    }

    public String getFailedNote() {
        return failedNote;
    }

    public void setFailedNote(String failedNote) {
        this.failedNote = failedNote;
    }

    public String getCompletedNoOutputText() {
        return completedNoOutputText;
    }

    public void setCompletedNoOutputText(String completedNoOutputText) {
        this.completedNoOutputText = completedNoOutputText;
    }

    public int getMaxNoteResultChars() {
        return maxNoteResultChars;
    }

    public void setMaxNoteResultChars(int maxNoteResultChars) {
        this.maxNoteResultChars = Math.max(0, maxNoteResultChars);
    }

    /** Fills {@code {tool}} and {@code {result}} in {@link #getCompletedNote}. */
    public String renderCompletedNote(String tool, String result) {
        return completedNote.replace("{tool}", String.valueOf(tool)).replace("{result}", result == null ? "" : result);
    }

    /** Fills {@code {tool}} and {@code {failure}} in {@link #getFailedNote}. */
    public String renderFailedNote(String tool, String failure) {
        return failedNote.replace("{tool}", String.valueOf(tool)).replace("{failure}", failure == null ? "" : failure);
    }

    /** Fills {@code {tool}}, {@code {approver}} and {@code {reason}} in {@link #getDeniedNote}. */
    public String renderDeniedNote(String tool, String decidedBy, String reason) {
        String approver = decidedBy == null || decidedBy.isBlank() ? approverUnknown : decidedBy.strip();
        String why = reason == null || reason.isBlank() ? noReasonText : reason.strip();
        return deniedNote
                .replace("{tool}", String.valueOf(tool))
                .replace("{approver}", approver)
                .replace("{reason}", why);
    }

    public String getOtherPendingHeader() {
        return otherPendingHeader;
    }

    public void setOtherPendingHeader(String otherPendingHeader) {
        this.otherPendingHeader = otherPendingHeader;
    }

    public String getCompletedMessage() {
        return completedMessage;
    }

    public void setCompletedMessage(String completedMessage) {
        this.completedMessage = completedMessage;
    }

    public String getFailedMessage() {
        return failedMessage;
    }

    public void setFailedMessage(String failedMessage) {
        this.failedMessage = failedMessage;
    }

    public String getDeniedMessage() {
        return deniedMessage;
    }

    public void setDeniedMessage(String deniedMessage) {
        this.deniedMessage = deniedMessage;
    }
}
