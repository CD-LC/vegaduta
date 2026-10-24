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
 * {@code app.chat.resilience.*}: what a chat turn does when it runs long or a provider fails while tools
 * are involved. The tool loop runs inside Spring AI, so the stream shows nothing while a tool works, and
 * "no text yet" says nothing about whether a tool already acted.
 *
 * <ul>
 *   <li>the stream idle timer counts model silence only: a running tool suspends it, up to {@link
 *       #getToolIdleCeiling()};
 *   <li>a turn is replayed on another provider only when no tool with side effects ran, or the effect
 *       journal recorded each of them ({@link #isReplayAfterJournaledEffects()});
 *   <li>a buffered-depth turn that runs out of time does not discard the tool work it did: it reports
 *       the steps that ran ({@link #getDepthTimeoutPartialNotice()}), and only a turn that had run no
 *       tool is answered again at FAST depth.
 * </ul>
 *
 * <p>The notices are user-facing, so they are configuration rather than Java literals. Defaults are read
 * from {@code /chat/resilience.properties}; the same values are in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "app.chat.resilience")
public class ChatResilienceProperties {

    private static final String DEFAULTS_RESOURCE = "/chat/resilience.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /**
     * How long a single tool may run before the stream idle timer counts it as hung. While a tool is in
     * flight the idle timeout is replaced by this, measured from the tool's last start, finish or progress.
     */
    private Duration toolIdleCeiling = Duration.parse(DEFAULTS.getProperty("tool-idle-ceiling"));

    /**
     * Replay a turn on another provider after write/send tools ran, as long as the effect journal
     * recorded every such call. Off, a turn that ran a tool with side effects is never replayed.
     */
    private boolean replayAfterJournaledEffects = Boolean.parseBoolean(DEFAULTS.getProperty("replay-after-journaled-effects"));

    /** Answer a timed-out CAREFUL/RIGOROUS/PLANNED turn again at FAST depth, with tools, when none had run. */
    private boolean retryAtFastWhenNoToolRan = Boolean.parseBoolean(DEFAULTS.getProperty("retry-at-fast-when-no-tool-ran"));

    /** Stands in for an empty list in a notice ("Steps still running: none"). */
    private String listNone = DEFAULTS.getProperty("list-none");

    /** Shown when a buffered-depth turn timed out after tools ran; {depth}, {seconds}, {completed}, {running}. */
    private String depthTimeoutPartialNotice = DEFAULTS.getProperty("depth-timeout-partial-notice");

    /** Step-event note when a timed-out turn is answered again at FAST depth; {depth}. */
    private String depthTimeoutDegradedNotice = DEFAULTS.getProperty("depth-timeout-degraded-notice");

    /** Step-event note when a provider failure was not retried elsewhere because tools had run; {tools}. */
    private String replayBlockedNotice = DEFAULTS.getProperty("replay-blocked-notice");

    /**
     * When the provider still refuses a tool-call history for a missing Gemini thought signature, answer the
     * turn once more WITHOUT tools - visibly - instead of failing it. Off, the provider's error is reported.
     */
    private boolean thoughtSignatureDegrade = Boolean.parseBoolean(DEFAULTS.getProperty("thought-signature-degrade"));

    /** The most characters of tool results shown to the model in the answer written without tools. */
    private int thoughtSignatureEvidenceChars = Integer.parseInt(DEFAULTS.getProperty("thought-signature-evidence-chars"));

    /** Shown at the start of a reply written without tools after that refusal; {tools} = steps already run. */
    private String thoughtSignatureNotice = DEFAULTS.getProperty("thought-signature-notice");

    /** Model-facing: added to the request of that reply; {evidence} = what the tools returned. */
    private String thoughtSignatureEvidenceNote = DEFAULTS.getProperty("thought-signature-evidence-note");

    /** How long a structured (typed input and output) agent call, such as a workflow node, waits for the reply. */
    private Duration structuredCallTimeout = Duration.parse(DEFAULTS.getProperty("structured-call-timeout"));

    /**
     * Least time between two attempts to repair the same hosted-pool row after the provider reported its model
     * id gone, so a persistently failing row does not trigger a catalog refresh on every turn.
     */
    private Duration hostedSelfHealCooldown = Duration.parse(DEFAULTS.getProperty("hosted-self-heal-cooldown"));

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = ChatResilienceProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents-turn module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    public Duration getStructuredCallTimeout() {
        return structuredCallTimeout;
    }

    public void setStructuredCallTimeout(Duration structuredCallTimeout) {
        this.structuredCallTimeout =
                structuredCallTimeout == null || structuredCallTimeout.isNegative() || structuredCallTimeout.isZero()
                        ? Duration.parse(DEFAULTS.getProperty("structured-call-timeout"))
                        : structuredCallTimeout;
    }

    public Duration getHostedSelfHealCooldown() {
        return hostedSelfHealCooldown;
    }

    public void setHostedSelfHealCooldown(Duration hostedSelfHealCooldown) {
        this.hostedSelfHealCooldown =
                hostedSelfHealCooldown == null || hostedSelfHealCooldown.isNegative()
                        ? Duration.parse(DEFAULTS.getProperty("hosted-self-heal-cooldown"))
                        : hostedSelfHealCooldown;
    }

    public Duration getToolIdleCeiling() {
        return toolIdleCeiling;
    }

    public void setToolIdleCeiling(Duration toolIdleCeiling) {
        this.toolIdleCeiling = toolIdleCeiling;
    }

    public boolean isReplayAfterJournaledEffects() {
        return replayAfterJournaledEffects;
    }

    public void setReplayAfterJournaledEffects(boolean replayAfterJournaledEffects) {
        this.replayAfterJournaledEffects = replayAfterJournaledEffects;
    }

    public boolean isRetryAtFastWhenNoToolRan() {
        return retryAtFastWhenNoToolRan;
    }

    public void setRetryAtFastWhenNoToolRan(boolean retryAtFastWhenNoToolRan) {
        this.retryAtFastWhenNoToolRan = retryAtFastWhenNoToolRan;
    }

    public String getListNone() {
        return listNone;
    }

    public void setListNone(String listNone) {
        this.listNone = listNone;
    }

    public String getDepthTimeoutPartialNotice() {
        return depthTimeoutPartialNotice;
    }

    public void setDepthTimeoutPartialNotice(String depthTimeoutPartialNotice) {
        this.depthTimeoutPartialNotice = depthTimeoutPartialNotice;
    }

    public String getDepthTimeoutDegradedNotice() {
        return depthTimeoutDegradedNotice;
    }

    public void setDepthTimeoutDegradedNotice(String depthTimeoutDegradedNotice) {
        this.depthTimeoutDegradedNotice = depthTimeoutDegradedNotice;
    }

    public String getReplayBlockedNotice() {
        return replayBlockedNotice;
    }

    public void setReplayBlockedNotice(String replayBlockedNotice) {
        this.replayBlockedNotice = replayBlockedNotice;
    }

    public boolean isThoughtSignatureDegrade() {
        return thoughtSignatureDegrade;
    }

    public void setThoughtSignatureDegrade(boolean thoughtSignatureDegrade) {
        this.thoughtSignatureDegrade = thoughtSignatureDegrade;
    }

    public int getThoughtSignatureEvidenceChars() {
        return thoughtSignatureEvidenceChars;
    }

    public void setThoughtSignatureEvidenceChars(int thoughtSignatureEvidenceChars) {
        this.thoughtSignatureEvidenceChars = Math.max(0, thoughtSignatureEvidenceChars);
    }

    public String getThoughtSignatureNotice() {
        return thoughtSignatureNotice;
    }

    public void setThoughtSignatureNotice(String thoughtSignatureNotice) {
        this.thoughtSignatureNotice = thoughtSignatureNotice;
    }

    public String getThoughtSignatureEvidenceNote() {
        return thoughtSignatureEvidenceNote;
    }

    public void setThoughtSignatureEvidenceNote(String thoughtSignatureEvidenceNote) {
        this.thoughtSignatureEvidenceNote = thoughtSignatureEvidenceNote;
    }

    /** {@link #getDepthTimeoutPartialNotice()} for a turn that stopped after {@code limit} at {@code depth}. */
    public String renderPartialNotice(String depth, Duration limit, String completed, String running) {
        return depthTimeoutPartialNotice
                .replace("{depth}", depth == null ? "" : depth.toLowerCase(java.util.Locale.ROOT))
                .replace("{seconds}", Long.toString(limit.toSeconds()))
                .replace("{completed}", completed)
                .replace("{running}", running);
    }

    /** {@link #getDepthTimeoutDegradedNotice()} with the depth filled in. */
    public String renderDegradedNotice(String depth) {
        return depthTimeoutDegradedNotice.replace(
                "{depth}", depth == null ? "" : depth.toLowerCase(java.util.Locale.ROOT));
    }

    /** {@link #getReplayBlockedNotice()} with the tools that ran filled in. */
    public String renderReplayBlockedNotice(String tools) {
        return replayBlockedNotice.replace("{tools}", tools);
    }
}
