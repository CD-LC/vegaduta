package com.agenticai.core.tutor;

/**
 * Grades a multiple-choice answer against the stored answer key. Pure code on purpose: a quiz is
 * never graded by a model, so the same answer always gets the same result.
 */
public final class QuizGrader {

    private QuizGrader() {
    }

    /**
     * @throws IllegalArgumentException (HTTP 400) when no option was selected or the selection is not
     *     one of the question's options.
     */
    public static boolean isCorrect(int correctIndex, int optionCount, Integer selectedIndex) {
        if (selectedIndex == null) {
            throw new IllegalArgumentException("selectedIndex is required.");
        }
        if (selectedIndex < 0 || selectedIndex >= optionCount) {
            throw new IllegalArgumentException("selectedIndex must be between 0 and " + (optionCount - 1) + ".");
        }
        return selectedIndex == correctIndex;
    }
}
