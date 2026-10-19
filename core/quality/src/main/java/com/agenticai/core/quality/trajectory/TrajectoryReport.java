package com.agenticai.core.quality.trajectory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of scoring one actual trajectory against a {@link TrajectorySpec}: the headline score and verdict,
 * every metric, and a row-by-row alignment that a console renders as an expected-vs-actual diff.
 *
 * <p>Row statuses - expected: {@code match} (found in order), {@code out_of_order} (found, but not in the
 * expected order), {@code args_mismatch} (right tool called, an expected argument did not match),
 * {@code missing}; actual: {@code match}, {@code out_of_order}, {@code args_mismatch}, {@code extra}.
 */
public record TrajectoryReport(
    TrajectorySpec.Mode mode,
    double score,
    double minScore,
    boolean passed,
    Metrics metrics,
    List<ExpectedRow> expected,
    List<ActualRow> actual,
    boolean actualTruncated
) {

    public static final String MATCH = "match";
    public static final String OUT_OF_ORDER = "out_of_order";
    public static final String ARGS_MISMATCH = "args_mismatch";
    public static final String MISSING = "missing";
    public static final String EXTRA = "extra";

    /**
     * @param argMatchRate fully matched steps / (fully matched + wrong-argument steps); null when no expected
     *     step was paired with a call at all (nothing to rate)
     */
    public record Metrics(
        boolean exactMatch,
        boolean inOrderMatch,
        boolean anyOrderMatch,
        double toolPrecision,
        double toolRecall,
        double toolF1,
        Double argMatchRate,
        int extraCalls,
        double extraCallPenalty,
        double efficiency,
        int failedCalls,
        int expectedSteps,
        int actualSteps
    ) {
    }

    public record ExpectedRow(int index, String tool, String status, Integer actualIndex, String detail) {
    }

    public record ActualRow(int index, String tool, boolean ok, String status, Integer expectedIndex, String args) {
    }

    /** JSON-friendly form, stored inside the case result's scores document and served to the console. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode.wire());
        m.put("score", score);
        m.put("minScore", minScore);
        m.put("passed", passed);
        Map<String, Object> mm = new LinkedHashMap<>();
        mm.put("exactMatch", metrics.exactMatch());
        mm.put("inOrderMatch", metrics.inOrderMatch());
        mm.put("anyOrderMatch", metrics.anyOrderMatch());
        mm.put("toolPrecision", metrics.toolPrecision());
        mm.put("toolRecall", metrics.toolRecall());
        mm.put("toolF1", metrics.toolF1());
        mm.put("argMatchRate", metrics.argMatchRate());
        mm.put("extraCalls", metrics.extraCalls());
        mm.put("extraCallPenalty", metrics.extraCallPenalty());
        mm.put("efficiency", metrics.efficiency());
        mm.put("failedCalls", metrics.failedCalls());
        mm.put("expectedSteps", metrics.expectedSteps());
        mm.put("actualSteps", metrics.actualSteps());
        m.put("metrics", mm);
        List<Map<String, Object>> exp = new ArrayList<>();
        for (ExpectedRow r : expected) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("index", r.index());
            o.put("tool", r.tool());
            o.put("status", r.status());
            o.put("actualIndex", r.actualIndex());
            o.put("detail", r.detail());
            exp.add(o);
        }
        m.put("expected", exp);
        List<Map<String, Object>> act = new ArrayList<>();
        for (ActualRow r : actual) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("index", r.index());
            o.put("tool", r.tool());
            o.put("ok", r.ok());
            o.put("status", r.status());
            o.put("expectedIndex", r.expectedIndex());
            o.put("args", r.args());
            act.add(o);
        }
        m.put("actual", act);
        m.put("actualTruncated", actualTruncated);
        return m;
    }

    /** One-line human summary for the dimension's {@code feedback}. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(mode.wire()).append(' ').append(Math.round(score * 100)).append("% (pass at ")
            .append(Math.round(minScore * 100)).append("%): ");
        int matched = 0;
        List<String> missing = new ArrayList<>();
        List<String> wrongArgs = new ArrayList<>();
        List<String> outOfOrder = new ArrayList<>();
        for (ExpectedRow r : expected) {
            switch (r.status()) {
                case MATCH -> matched++;
                case OUT_OF_ORDER -> outOfOrder.add(r.tool());
                case ARGS_MISMATCH -> wrongArgs.add(r.tool());
                default -> missing.add(r.tool());
            }
        }
        sb.append(matched).append(" of ").append(metrics.expectedSteps()).append(" expected steps matched in order");
        append(sb, "out of order", outOfOrder);
        append(sb, "wrong arguments", wrongArgs);
        append(sb, "missing", missing);
        if (metrics.extraCalls() > 0) {
            sb.append("; ").append(metrics.extraCalls()).append(" extra call").append(metrics.extraCalls() == 1 ? "" : "s");
        }
        if (!passed) {
            sb.append(" - failed");
        }
        String out = sb.toString();
        return out.length() <= 500 ? out : out.substring(0, 500) + "...";
    }

    private static void append(StringBuilder sb, String label, List<String> tools) {
        if (tools.isEmpty()) {
            return;
        }
        sb.append("; ").append(label).append(": ");
        int shown = Math.min(tools.size(), 5);
        sb.append(String.join(", ", tools.subList(0, shown)));
        if (tools.size() > shown) {
            sb.append(" (+").append(tools.size() - shown).append(")");
        }
    }
}
