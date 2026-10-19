package com.agenticai.core.rl.domain;

/**
 * The kind of human reward signal a {@link TurnFeedback} row carries. The
 * constant NAMES are deliberately identical to the string signals the web
 * Playground's {@code TurnFeedback.tsx} sends over the wire ({@code THUMBS_UP
 * | THUMBS_DOWN | RATING | EDIT | RESOLVED | UNRESOLVED}), so the REST request
 * body deserializes straight onto this enum with no name mapping - stored as
 * its name via {@code @Enumerated(STRING)} into {@code turn_feedback.signal
 * VARCHAR(16)} (longest name, {@code THUMBS_DOWN}, is 11 chars).
 *
 * <ul>
 *   <li>{@link #THUMBS_UP} / {@link #THUMBS_DOWN} - a binary thumbs judgement.</li>
 *   <li>{@link #RATING} - a 1-5 star score (see {@code TurnFeedback.rating}).</li>
 *   <li>{@link #EDIT} - the human corrected the answer; the corrected text is
 *       kept in {@code TurnFeedback.editedAnswer} and becomes the "chosen"
 *       side of a DPO preference pair on export.</li>
 *   <li>{@link #RESOLVED} / {@link #UNRESOLVED} - whether the turn actually
 *       resolved the user's need; a positive / negative reward respectively
 *       (see {@code FeedbackService.signalToReward}).</li>
 * </ul>
 */
public enum FeedbackSignal {
    THUMBS_UP,
    THUMBS_DOWN,
    RATING,
    EDIT,
    RESOLVED,
    UNRESOLVED
}
