package com.agenticai.core.agents.turn;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.chat.tool-ledger.*}: the compact record of what a turn's tools did ({@link ToolLedger}), kept
 * with the assistant turn in the conversation history and replayed to the model on later turns. Without it
 * the model on turn N+1 saw only its own prose about turn N: not the file ids a tool returned, not which
 * write calls it had already made, not that an action was still waiting on a human.
 *
 * <p>Everything bounded is a limit here, and everything the model reads (the header, the line shapes, the
 * status wording, the note that tells it the record is reliable) is configuration rather than a Java
 * literal. Defaults are read from {@code /chat/tool-ledger.properties} on the classpath; the same values
 * are in {@code application.yml}. Registered by {@link ToolLedgerConfiguration}.
 */
@ConfigurationProperties(prefix = "app.chat.tool-ledger")
public class ToolLedgerProperties {

    private static final String DEFAULTS_RESOURCE = "/chat/tool-ledger.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** Records, stores and replays the tool ledger. Off, history is text only, as before. */
    private boolean enabled = Boolean.parseBoolean(DEFAULTS.getProperty("enabled"));

    /** Stores the ledger but does not show it to the model. */
    private boolean replayEnabled = Boolean.parseBoolean(DEFAULTS.getProperty("replay-enabled"));

    /** Entries kept for one turn; calls past it are counted, not listed. */
    private int maxEntriesPerTurn = Integer.parseInt(DEFAULTS.getProperty("max-entries-per-turn"));

    /** An entry's arguments are cut to this many characters. */
    private int maxArgumentChars = Integer.parseInt(DEFAULTS.getProperty("max-argument-chars"));

    /** An entry's result is cut to this many characters. */
    private int maxResultChars = Integer.parseInt(DEFAULTS.getProperty("max-result-chars"));

    /** The rendered block of one turn is cut to this many characters. */
    private int maxReplayCharsPerTurn = Integer.parseInt(DEFAULTS.getProperty("max-replay-chars-per-turn"));

    /** Only the newest this-many assistant turns that carry a ledger show it to the model. */
    private int replayTurns = Integer.parseInt(DEFAULTS.getProperty("replay-turns"));

    /** Appended to a cut argument or result. */
    private String cutMarker = DEFAULTS.getProperty("cut-marker");

    /** First line of a rendered ledger block: says who wrote it and that the model may rely on it. */
    private String header = DEFAULTS.getProperty("header");

    /** One entry: {tool} {arguments} {status} {detail}. */
    private String line = DEFAULTS.getProperty("line");

    /** The result part of an entry that has one: {result}. */
    private String detail = DEFAULTS.getProperty("detail");

    /** Appended to an entry held for a human: {approval}. */
    private String approvalDetail = DEFAULTS.getProperty("approval-detail");

    /** Last line when calls are not listed: {count}. */
    private String omittedLine = DEFAULTS.getProperty("omitted-line");

    /** Status wording. */
    private String statusOk = DEFAULTS.getProperty("status-ok");

    /** Status wording. */
    private String statusFailed = DEFAULTS.getProperty("status-failed");

    /** Status wording. */
    private String statusNotRun = DEFAULTS.getProperty("status-not-run");

    /** Status wording. */
    private String statusAwaitingApproval = DEFAULTS.getProperty("status-awaiting-approval");

    /** Status wording. */
    private String statusReplayed = DEFAULTS.getProperty("status-replayed");

    /** Replayed after the ledger of a turn whose reply failed. */
    private String failedReplyNote = DEFAULTS.getProperty("failed-reply-note");

    /** Added to the turn's system context when a replayed turn carries a ledger: tells the model it may rely on it. */
    private String trustNote = DEFAULTS.getProperty("trust-note");

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = ToolLedgerProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    /**
     * A copy of these properties with the five numbers and flags a tenant may set replaced, for one tenant's turns
     * ({@code chat.toolLedger.*}): the shared platform object is never changed. Every wording field is kept.
     */
    public ToolLedgerProperties with(
            boolean enabled, boolean replayEnabled, int maxEntriesPerTurn, int replayTurns, int maxReplayCharsPerTurn) {
        ToolLedgerProperties c = new ToolLedgerProperties();
        c.setEnabled(enabled);
        c.setReplayEnabled(replayEnabled);
        c.setMaxEntriesPerTurn(maxEntriesPerTurn);
        c.setMaxArgumentChars(maxArgumentChars);
        c.setMaxResultChars(maxResultChars);
        c.setMaxReplayCharsPerTurn(maxReplayCharsPerTurn);
        c.setReplayTurns(replayTurns);
        c.setCutMarker(cutMarker);
        c.setHeader(header);
        c.setLine(line);
        c.setDetail(detail);
        c.setApprovalDetail(approvalDetail);
        c.setOmittedLine(omittedLine);
        c.setStatusOk(statusOk);
        c.setStatusFailed(statusFailed);
        c.setStatusNotRun(statusNotRun);
        c.setStatusAwaitingApproval(statusAwaitingApproval);
        c.setStatusReplayed(statusReplayed);
        c.setFailedReplyNote(failedReplyNote);
        c.setTrustNote(trustNote);
        return c;
    }

    /** The wording of {@code status} in a rendered entry. */
    public String statusLabel(ToolLedger.Status status) {
        return switch (status) {
            case OK -> statusOk;
            case FAILED -> statusFailed;
            // A simulated call (offline eval turn) did not run either; the model reads the same words.
            case NOT_RUN, SIMULATED -> statusNotRun;
            case AWAITING_APPROVAL -> statusAwaitingApproval;
            case REPLAYED -> statusReplayed;
        };
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isReplayEnabled() {
        return replayEnabled;
    }

    public void setReplayEnabled(boolean replayEnabled) {
        this.replayEnabled = replayEnabled;
    }

    public int getMaxEntriesPerTurn() {
        return maxEntriesPerTurn;
    }

    public void setMaxEntriesPerTurn(int maxEntriesPerTurn) {
        this.maxEntriesPerTurn = Math.max(0, maxEntriesPerTurn);
    }

    public int getMaxArgumentChars() {
        return maxArgumentChars;
    }

    public void setMaxArgumentChars(int maxArgumentChars) {
        this.maxArgumentChars = Math.min(ToolLedgerSummary.MAX_FIELD_CHARS, Math.max(0, maxArgumentChars));
    }

    public int getMaxResultChars() {
        return maxResultChars;
    }

    public void setMaxResultChars(int maxResultChars) {
        this.maxResultChars = Math.min(ToolLedgerSummary.MAX_FIELD_CHARS, Math.max(0, maxResultChars));
    }

    public int getMaxReplayCharsPerTurn() {
        return maxReplayCharsPerTurn;
    }

    public void setMaxReplayCharsPerTurn(int maxReplayCharsPerTurn) {
        this.maxReplayCharsPerTurn = Math.max(0, maxReplayCharsPerTurn);
    }

    public int getReplayTurns() {
        return replayTurns;
    }

    public void setReplayTurns(int replayTurns) {
        this.replayTurns = Math.max(0, replayTurns);
    }

    public String getCutMarker() {
        return cutMarker;
    }

    public void setCutMarker(String cutMarker) {
        this.cutMarker = cutMarker == null ? "" : cutMarker;
    }

    public String getHeader() {
        return header;
    }

    public void setHeader(String header) {
        this.header = header == null ? "" : header;
    }

    public String getLine() {
        return line;
    }

    public void setLine(String line) {
        this.line = line == null ? "" : line;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail == null ? "" : detail;
    }

    public String getApprovalDetail() {
        return approvalDetail;
    }

    public void setApprovalDetail(String approvalDetail) {
        this.approvalDetail = approvalDetail == null ? "" : approvalDetail;
    }

    public String getOmittedLine() {
        return omittedLine;
    }

    public void setOmittedLine(String omittedLine) {
        this.omittedLine = omittedLine == null ? "" : omittedLine;
    }

    public String getStatusOk() {
        return statusOk;
    }

    public void setStatusOk(String statusOk) {
        this.statusOk = statusOk == null ? "" : statusOk;
    }

    public String getStatusFailed() {
        return statusFailed;
    }

    public void setStatusFailed(String statusFailed) {
        this.statusFailed = statusFailed == null ? "" : statusFailed;
    }

    public String getStatusNotRun() {
        return statusNotRun;
    }

    public void setStatusNotRun(String statusNotRun) {
        this.statusNotRun = statusNotRun == null ? "" : statusNotRun;
    }

    public String getStatusAwaitingApproval() {
        return statusAwaitingApproval;
    }

    public void setStatusAwaitingApproval(String statusAwaitingApproval) {
        this.statusAwaitingApproval = statusAwaitingApproval == null ? "" : statusAwaitingApproval;
    }

    public String getStatusReplayed() {
        return statusReplayed;
    }

    public void setStatusReplayed(String statusReplayed) {
        this.statusReplayed = statusReplayed == null ? "" : statusReplayed;
    }

    public String getFailedReplyNote() {
        return failedReplyNote;
    }

    public void setFailedReplyNote(String failedReplyNote) {
        this.failedReplyNote = failedReplyNote == null ? "" : failedReplyNote;
    }

    public String getTrustNote() {
        return trustNote;
    }

    public void setTrustNote(String trustNote) {
        this.trustNote = trustNote == null ? "" : trustNote;
    }

}
