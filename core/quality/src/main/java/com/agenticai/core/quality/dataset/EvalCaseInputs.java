package com.agenticai.core.quality.dataset;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Validation and encoding of the fields a caller writes on an {@link EvalDataset} or {@link EvalDatasetCase}:
 * name, user message, pass threshold and the {@code expected_tools} JSON-array column. Split out of
 * {@link EvalDatasetService}, which applies them on every write; each failure is an
 * {@link IllegalArgumentException} (a 400).
 */
final class EvalCaseInputs {

    /** Same static-mapper pattern as {@link EvalDatasetService}'s reads of the same column. */
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private EvalCaseInputs() {
    }

    static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("'name' is required and cannot be blank.");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("'name' must be at most 255 characters.");
        }
        return name.trim();
    }

    static String requireUserMessage(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("'userMessage' is required and cannot be blank.");
        }
        return userMessage;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * [0,1] with a default of 0.7 - the same "meaningfully above coin-flip, below perfection"
     * default the migration bakes into the column.
     */
    static double normalizeThreshold(Double passThreshold) {
        if (passThreshold == null) {
            return 0.7;
        }
        if (passThreshold < 0.0 || passThreshold > 1.0) {
            throw new IllegalArgumentException("'passThreshold' must be between 0.0 and 1.0.");
        }
        return passThreshold;
    }

    /** List form -> stored JSON text; null/empty -> null (no assertion). */
    static String serializeExpectedTools(List<String> expectedTools) {
        if (expectedTools == null || expectedTools.isEmpty()) {
            return null;
        }
        if (expectedTools.size() > EvalDatasetService.MAX_EXPECTED_TOOLS) {
            throw new IllegalArgumentException(
                    "'expectedTools' can list at most " + EvalDatasetService.MAX_EXPECTED_TOOLS + " tool names.");
        }
        List<String> cleaned =
                expectedTools.stream()
                        .filter(t -> t != null && !t.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(cleaned);
        } catch (Exception e) {
            // Serializing a List<String> cannot realistically fail; guard anyway.
            throw new IllegalArgumentException(
                    "Could not encode 'expectedTools': " + e.getMessage());
        }
    }
}
