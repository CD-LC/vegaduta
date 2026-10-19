package com.agenticai.core.quality.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TrajectorySpecTest {

    private static String messageOf(String json) {
        return assertThrows(IllegalArgumentException.class, () -> TrajectorySpec.parse(json)).getMessage();
    }

    @Test
    void routingRecognisesOnlyTrajectorySpecs() {
        assertTrue(TrajectorySpec.isTrajectory("{\"type\":\"trajectory\",\"steps\":[]}"));
        assertTrue(TrajectorySpec.isTrajectory("{\"type\":\" Trajectory \",\"steps\":[]}"));
        assertFalse(TrajectorySpec.isTrajectory("{\"type\":\"regex\",\"pattern\":\"trajectory\"}"));
        assertFalse(TrajectorySpec.isTrajectory("{\"type\":\"exact\",\"value\":\"trajectory\"}"));
        assertFalse(TrajectorySpec.isTrajectory("[\"trajectory\"]"));
        assertFalse(TrajectorySpec.isTrajectory("{not json trajectory"));
        assertFalse(TrajectorySpec.isTrajectory(""));
        assertFalse(TrajectorySpec.isTrajectory(null));
    }

    @Test
    void defaultsAndACanonicalRoundTrip() {
        TrajectorySpec spec = TrajectorySpec.parse("{\"type\":\"trajectory\",\"steps\":["
            + "{\"tool\":\"search\",\"args\":{\"q\":{\"regex\":\"x\"},\"n\":5}},{\"tool\":\"send_email\"}]}");
        assertEquals(TrajectorySpec.Mode.IN_ORDER, spec.mode());
        assertEquals(1.0, spec.minScore(), 1e-9);
        assertEquals(2, spec.stepCount());
        String canonical = spec.toJson();
        assertTrue(canonical.contains("\"mode\":\"in_order\""), canonical);
        assertEquals(canonical, TrajectorySpec.parse(canonical).toJson());
    }

    @Test
    void allOptionsParse() {
        TrajectorySpec spec = TrajectorySpec.parse("{\"type\":\"trajectory\",\"mode\":\"ANY_ORDER\",\"minScore\":0.5,"
            + "\"maxExtraCalls\":2,\"minEfficiency\":0.25,\"steps\":[]}");
        assertEquals(TrajectorySpec.Mode.ANY_ORDER, spec.mode());
        assertEquals(0.5, spec.minScore(), 1e-9);
        assertEquals(Integer.valueOf(2), spec.maxExtraCalls());
        assertEquals(0.25, spec.minEfficiency(), 1e-9);
        assertEquals(0, spec.stepCount());
    }

    @Test
    void unknownKeysAndBadValuesAreRejectedWithAPreciseReason() {
        assertTrue(messageOf("{\"type\":\"trajectory\",\"steps\":[],\"extra\":1}").contains("extra"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"steps\":[{\"tool\":\"a\",\"arg\":{}}]}").contains("arg"));
        assertTrue(messageOf("{\"type\":\"trajectory\"}").contains("'steps'"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"steps\":{}}").contains("'steps'"));
        assertTrue(messageOf("{\"steps\":[]}").contains("type"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"mode\":\"sideways\",\"steps\":[]}").contains("'mode'"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"mode\":1,\"steps\":[]}").contains("'mode'"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"minScore\":2,\"steps\":[]}").contains("minScore"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"minScore\":\"1\",\"steps\":[]}").contains("minScore"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"minEfficiency\":-1,\"steps\":[]}").contains("minEfficiency"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"maxExtraCalls\":-1,\"steps\":[]}").contains("maxExtraCalls"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"maxExtraCalls\":1.5,\"steps\":[]}").contains("maxExtraCalls"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"steps\":[1]}").contains("Step #1"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"steps\":[{}]}").contains("'tool'"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"steps\":[{\"tool\":\"a b\"}]}").contains("'tool'"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"steps\":[{\"tool\":\"a*\"}]}").contains("'tool'"));
        assertTrue(messageOf("{\"type\":\"trajectory\",\"steps\":[{\"tool\":\"a\",\"args\":[]}]}").contains("'args'"));
        assertTrue(messageOf("{not json").contains("not valid JSON"));
        assertTrue(messageOf("[]").contains("JSON object"));
        assertTrue(messageOf("").contains("empty"));
    }

    @Test
    void argumentMatchersAreValidatedAtWriteTime() {
        String base = "{\"type\":\"trajectory\",\"steps\":[{\"tool\":\"a\",\"args\":%s}]}";
        assertTrue(messageOf(String.format(base, "{\"q\":{\"regex\":\"([\"}}")).contains("not a valid regular expression"));
        assertTrue(messageOf(String.format(base, "{\"q\":{\"regex\":\"\"}}")).contains("non-empty"));
        assertTrue(messageOf(String.format(base, "{\"q\":{\"regex\":\"" + "a".repeat(201) + "\"}}")).contains("limited to"));
        assertTrue(messageOf(String.format(base, "{\"q\":{\"regex\":\"a\",\"present\":true}}")).contains("exactly one"));
        assertTrue(messageOf(String.format(base, "{\"q\":{\"present\":false}}")).contains("must be true"));
        assertTrue(messageOf(String.format(base, "{\"q\":{\"ignoreCase\":true,\"present\":true}}")).contains("only applies to 'regex'"));
        assertTrue(messageOf(String.format(base, "{\"q\":{\"regex\":\"a\",\"flags\":\"i\"}}")).contains("unknown matcher key"));
        assertTrue(messageOf(String.format(base, "{\"bad path!\":1}")).contains("argument path"));
        assertTrue(messageOf(String.format(base, "{\"a..b\":1}")).contains("argument path"));
        // Exactly at the regex cap is fine.
        TrajectorySpec.parse(String.format(base, "{\"q\":{\"regex\":\"" + "a".repeat(200) + "\"}}"));
    }

    @Test
    void sizeCaps() {
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i <= TrajectorySpec.MAX_STEPS; i++) {
            steps.append(i == 0 ? "" : ",").append("{\"tool\":\"t\"}");
        }
        assertTrue(messageOf("{\"type\":\"trajectory\",\"steps\":[" + steps + "]}").contains("at most " + TrajectorySpec.MAX_STEPS));

        StringBuilder args = new StringBuilder("{");
        for (int i = 0; i <= TrajectorySpec.MAX_ARGS_PER_STEP; i++) {
            args.append(i == 0 ? "" : ",").append("\"a").append(i).append("\":1");
        }
        args.append("}");
        assertTrue(messageOf("{\"type\":\"trajectory\",\"steps\":[{\"tool\":\"t\",\"args\":" + args + "}]}")
            .contains("at most " + TrajectorySpec.MAX_ARGS_PER_STEP));

        String huge = "{\"type\":\"trajectory\",\"steps\":[],\"x\":\"" + "x".repeat(TrajectorySpec.MAX_DOCUMENT_CHARS) + "\"}";
        assertTrue(messageOf(huge).contains("limited to"));
    }

    @Test
    void theWriteGateIsTheOldUnknownTypeAnswerWhileTheFeatureIsOff() {
        IllegalArgumentException off = assertThrows(IllegalArgumentException.class,
            () -> TrajectorySpec.validateForWrite("{\"type\":\"trajectory\",\"steps\":[]}", false));
        assertEquals("Unknown grader type 'trajectory' - use regex, exact or json_schema.", off.getMessage());
        // The very message the code grader gives for any unknown type, so flag-off behaviour is unchanged.
        IllegalArgumentException legacy = assertThrows(IllegalArgumentException.class,
            () -> com.agenticai.core.quality.grader.CodeGrader.parse("{\"type\":\"trajectory\"}"));
        assertEquals(legacy.getMessage(), off.getMessage());

        TrajectorySpec.validateForWrite("{\"type\":\"trajectory\",\"steps\":[]}", true);
        assertThrows(IllegalArgumentException.class,
            () -> TrajectorySpec.validateForWrite("{\"type\":\"trajectory\",\"mode\":\"x\",\"steps\":[]}", true));
    }
}
