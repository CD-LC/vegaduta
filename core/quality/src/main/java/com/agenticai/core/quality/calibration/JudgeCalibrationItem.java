package com.agenticai.core.quality.calibration;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.time.Instant;
import java.util.UUID;

/** One human-labelled pairwise comparison inside a {@link JudgeCalibrationSet}. */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class JudgeCalibrationItem {
    private UUID id = UUID.randomUUID();
    private UUID setId;
    private int position;
    private String input;
    private String outputA;
    private String outputB;

    /** The human preferred answer: {@code "A"} or {@code "B"}. */
    private String humanLabel;
    private String rationale;
    private Instant createdAt;

    protected JudgeCalibrationItem() {
        // Jackson
    }

    public JudgeCalibrationItem(
        UUID setId, int position, String input, String outputA, String outputB, String humanLabel, String rationale
    ) {
        this.setId = setId;
        this.position = position;
        this.input = input;
        this.outputA = outputA;
        this.outputB = outputB;
        this.humanLabel = humanLabel;
        this.rationale = rationale;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSetId() {
        return setId;
    }

    public int getPosition() {
        return position;
    }

    public String getInput() {
        return input;
    }

    public String getOutputA() {
        return outputA;
    }

    public String getOutputB() {
        return outputB;
    }

    public String getHumanLabel() {
        return humanLabel;
    }

    public String getRationale() {
        return rationale;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
