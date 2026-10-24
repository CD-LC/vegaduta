package com.agenticai.core.agents.turn;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.chat.parity.*}: where the streamed turn (FAST) and the buffered turns (CAREFUL, RIGOROUS,
 * PLANNED) deliberately differ, and the limits on the buffered verifiers. An agent should behave the same
 * on either path; whatever differs is a setting here, documented in {@code docs/configuration.md}, not an
 * accident of which class handled the turn.
 *
 * <p>Defaults are read from {@code /chat/parity.properties} on the classpath; the same values are in
 * {@code application.yml}. Registered by {@link ChatParityConfiguration}.
 */
@ConfigurationProperties(prefix = "app.chat.parity")
public class ChatParityProperties {

    /** What makes a FAST turn buffer its reply instead of streaming it. */
    public enum FastBufferTrigger {
        /** The turn offers a generation tool (image, document, audio, video). */
        GENERATION_TOOLS,
        /** The turn offers any tool from an MCP server. */
        MCP_TOOLS,
        /** The agent has content guardrails on the output path. */
        OUTPUT_GUARDRAILS
    }

    /** How a FAST turn that matches a buffering trigger reaches the person. */
    public enum FastBufferingMode {
        /** Streamed live; only the part from the first link (a hold marker) on is held, checked and then sent. */
        FROM_FIRST_LINK,
        /** The whole reply is held, checked and sent as one piece. */
        WHOLE_REPLY
    }

    private static final String DEFAULTS_RESOURCE = "/chat/parity.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** See {@link FastBufferTrigger}. */
    private Set<FastBufferTrigger> fastBufferingTriggers = triggers(DEFAULTS.getProperty("fast-buffering-triggers"));

    /** See {@link FastBufferingMode}. */
    private FastBufferingMode fastBufferingMode =
            FastBufferingMode.valueOf(DEFAULTS.getProperty("fast-buffering-mode").strip());

    /**
     * Text that starts the held part of a {@link FastBufferingMode#FROM_FIRST_LINK} reply, compared ignoring case. The
     * invented-link guard's own host markers are always added.
     */
    private List<String> fastHoldMarkers = markers(DEFAULTS.getProperty("fast-hold-markers"));

    /** The verifiers see the newest this-many characters of the turn's tool results; 0 = all of it. */
    private int verifierMaxEvidenceChars = Integer.parseInt(DEFAULTS.getProperty("verifier-max-evidence-chars"));

    /** A verifier's rewrite shorter than this fraction of the answer is not accepted; 0 = accept any. */
    private double verifierMinRevisionRatio = Double.parseDouble(DEFAULTS.getProperty("verifier-min-revision-ratio"));

    /** The ratio check applies to answers longer than this many characters. */
    private int verifierRatioAppliesAboveChars =
            Integer.parseInt(DEFAULTS.getProperty("verifier-ratio-applies-above-chars"));

    /** The buffered verifiers see this many of the newest earlier messages of the conversation as grounding. */
    private int verifierHistoryWindow = Integer.parseInt(DEFAULTS.getProperty("verifier-history-window"));

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = ChatParityProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents-turn module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    private static Set<FastBufferTrigger> triggers(String commaList) {
        Set<FastBufferTrigger> out = EnumSet.noneOf(FastBufferTrigger.class);
        for (String name : commaList.split(",")) {
            if (!name.isBlank()) {
                out.add(FastBufferTrigger.valueOf(name.strip()));
            }
        }
        return out;
    }

    private static List<String> markers(String commaList) {
        List<String> out = new ArrayList<>();
        if (commaList != null) {
            for (String marker : commaList.split(",")) {
                if (!marker.isBlank()) {
                    out.add(marker.strip());
                }
            }
        }
        return List.copyOf(out);
    }

    public FastBufferingMode getFastBufferingMode() {
        return fastBufferingMode;
    }

    public void setFastBufferingMode(FastBufferingMode fastBufferingMode) {
        this.fastBufferingMode = fastBufferingMode == null ? FastBufferingMode.FROM_FIRST_LINK : fastBufferingMode;
    }

    public List<String> getFastHoldMarkers() {
        return fastHoldMarkers;
    }

    public void setFastHoldMarkers(List<String> fastHoldMarkers) {
        this.fastHoldMarkers = fastHoldMarkers == null ? List.of() : markers(String.join(",", fastHoldMarkers));
    }

    public Set<FastBufferTrigger> getFastBufferingTriggers() {
        return fastBufferingTriggers;
    }

    public void setFastBufferingTriggers(Set<FastBufferTrigger> fastBufferingTriggers) {
        this.fastBufferingTriggers =
                fastBufferingTriggers == null || fastBufferingTriggers.isEmpty()
                        ? EnumSet.noneOf(FastBufferTrigger.class)
                        : EnumSet.copyOf(fastBufferingTriggers);
    }

    /** True when {@code trigger} makes a FAST turn buffer its reply. */
    public boolean buffers(FastBufferTrigger trigger) {
        return fastBufferingTriggers.contains(trigger);
    }

    public int getVerifierMaxEvidenceChars() {
        return verifierMaxEvidenceChars;
    }

    public void setVerifierMaxEvidenceChars(int verifierMaxEvidenceChars) {
        this.verifierMaxEvidenceChars = Math.max(0, verifierMaxEvidenceChars);
    }

    public double getVerifierMinRevisionRatio() {
        return verifierMinRevisionRatio;
    }

    public void setVerifierMinRevisionRatio(double verifierMinRevisionRatio) {
        this.verifierMinRevisionRatio = Math.max(0.0, Math.min(1.0, verifierMinRevisionRatio));
    }

    public int getVerifierRatioAppliesAboveChars() {
        return verifierRatioAppliesAboveChars;
    }

    public void setVerifierRatioAppliesAboveChars(int verifierRatioAppliesAboveChars) {
        this.verifierRatioAppliesAboveChars = Math.max(0, verifierRatioAppliesAboveChars);
    }

    public int getVerifierHistoryWindow() {
        return verifierHistoryWindow;
    }

    public void setVerifierHistoryWindow(int verifierHistoryWindow) {
        this.verifierHistoryWindow = Math.max(1, verifierHistoryWindow);
    }
}
