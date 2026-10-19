package com.agenticai.core.quality.trajectory;

import com.agenticai.core.quality.trajectory.TrajectoryReport.ActualRow;
import com.agenticai.core.quality.trajectory.TrajectoryReport.ExpectedRow;
import com.agenticai.core.quality.trajectory.TrajectoryReport.Metrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scores an actual tool-call trajectory against an expected one. Pure and deterministic: no model, no I/O,
 * the same input always gives the same report, so it runs on every repetition at no cost.
 *
 * <h2>Metrics (n = expected steps, m = actual steps)</h2>
 * An actual call FULLY matches an expected step when the tool name is equal and every listed argument matcher
 * passes ({@link ExpectedStep#matches}).
 * <ul>
 *   <li><b>exactMatch</b> - m == n and call i fully matches step i for every i.</li>
 *   <li><b>inOrderMatch</b> - the expected steps are a SUBSEQUENCE of the actual calls (extra calls allowed in
 *       between): the longest in-order matching has length n.</li>
 *   <li><b>anyOrderMatch</b> - every expected step is matched by a DISTINCT actual call, in any order
 *       (maximum bipartite matching has size n; duplicates need duplicate calls).</li>
 *   <li><b>toolPrecision / toolRecall / toolF1</b> - tool NAMES only, as multisets: tp = sum over tools of
 *       min(expectedCount, actualCount); precision = tp/m, recall = tp/n, F1 their harmonic mean. Empty
 *       sides: no actual calls gives precision 1 only when nothing was expected (else 0); nothing expected
 *       gives recall 1; both empty is a perfect 1/1/1.</li>
 *   <li><b>argMatchRate</b> - of the expected steps paired with a call of the right tool, the fraction whose
 *       arguments matched; null when nothing was paired.</li>
 *   <li><b>extraCalls / extraCallPenalty</b> - actual calls paired with no expected step, and that count over m
 *       (0 when m == 0). A right-tool-wrong-arguments call is paired, not extra.</li>
 *   <li><b>efficiency</b> - step-count efficiency, min(1, n/m): 1 when the agent used no more calls than
 *       expected. m == 0 gives 0 when something was expected (doing nothing is not efficient), 1 when not.</li>
 * </ul>
 *
 * <h2>Score per mode (0..1, passes at {@code minScore})</h2>
 * <ul>
 *   <li>{@code exact}: calls fully matching at the same position / max(n, m) (both empty = 1).</li>
 *   <li>{@code in_order}: longest in-order matching / n (n = 0 = 1).</li>
 *   <li>{@code any_order}: maximum matching / n (n = 0 = 1).</li>
 *   <li>{@code f1}: toolF1 - ignores arguments and order.</li>
 * </ul>
 * Every mode's score is 1 exactly when its binary match holds (for n &gt; 0), so the default {@code minScore}
 * of 1 means "must match". The spec's {@code maxExtraCalls} and {@code minEfficiency}, when set, are extra hard
 * conditions on top of the score.
 *
 * <p>At most {@value #MAX_ACTUAL_STEPS} actual calls are considered (the rest are ignored and the report says
 * so); a trajectory that long has already failed any realistic expectation.
 */
public final class TrajectoryEvaluator {

    public static final int MAX_ACTUAL_STEPS = 200;
    private static final int ARGS_PREVIEW_CHARS = 200;
    private static final double EPS = 1e-9;

    private TrajectoryEvaluator() {
    }

    /** Scores {@code actual} against {@code spec}. Never throws on odd input (null steps are treated as empty). */
    public static TrajectoryReport evaluate(TrajectorySpec spec, List<TrajectoryToolCall> actualAll) {
        List<ExpectedStep> expected = spec.steps();
        List<TrajectoryToolCall> all = actualAll == null ? List.of() : actualAll;
        boolean truncated = all.size() > MAX_ACTUAL_STEPS;
        List<TrajectoryToolCall> actual = truncated ? all.subList(0, MAX_ACTUAL_STEPS) : all;
        int n = expected.size();
        int m = actual.size();

        boolean[][] full = new boolean[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                full[i][j] = expected.get(i).matches(actual.get(j));
            }
        }

        // 1. Longest in-order matching (LCS over "fully matches").
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                dp[i][j] = full[i - 1][j - 1] ? dp[i - 1][j - 1] + 1 : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        int lcsLen = dp[n][m];
        int[] expToAct = new int[n];
        int[] actToExp = new int[m];
        Arrays.fill(expToAct, -1);
        Arrays.fill(actToExp, -1);
        Set<Long> inOrderPairs = new HashSet<>();
        int bi = n;
        int bj = m;
        while (bi > 0 && bj > 0) {
            if (full[bi - 1][bj - 1] && dp[bi][bj] == dp[bi - 1][bj - 1] + 1) {
                expToAct[bi - 1] = bj - 1;
                actToExp[bj - 1] = bi - 1;
                inOrderPairs.add(key(bi - 1, bj - 1, m));
                bi--;
                bj--;
            } else if (dp[bi - 1][bj] >= dp[bi][bj - 1]) {
                bi--;
            } else {
                bj--;
            }
        }

        // 2. Grow that into a MAXIMUM matching (any order): augmenting paths from the in-order seed.
        for (int i = 0; i < n; i++) {
            if (expToAct[i] == -1) {
                augment(i, full, expToAct, actToExp, new boolean[m]);
            }
        }
        int anyCount = 0;
        for (int i = 0; i < n; i++) {
            if (expToAct[i] != -1) {
                anyCount++;
            }
        }

        // 3. Leftovers: the right tool called with arguments that did not match.
        String[] expStatus = new String[n];
        String[] expDetail = new String[n];
        String[] actStatus = new String[m];
        int wrongArgs = 0;
        for (int i = 0; i < n; i++) {
            if (expToAct[i] != -1) {
                boolean inOrder = inOrderPairs.contains(key(i, expToAct[i], m));
                expStatus[i] = inOrder ? TrajectoryReport.MATCH : TrajectoryReport.OUT_OF_ORDER;
                actStatus[expToAct[i]] = expStatus[i];
            }
        }
        for (int i = 0; i < n; i++) {
            if (expToAct[i] != -1) {
                continue;
            }
            for (int j = 0; j < m; j++) {
                if (actToExp[j] == -1 && expected.get(i).tool().equals(actual.get(j).tool())) {
                    expToAct[i] = j;
                    actToExp[j] = i;
                    expStatus[i] = TrajectoryReport.ARGS_MISMATCH;
                    actStatus[j] = TrajectoryReport.ARGS_MISMATCH;
                    List<String> bad = expected.get(i).mismatchedArgs(actual.get(j).args());
                    expDetail[i] = bad.isEmpty() ? "arguments differ" : "arguments differ: " + String.join(", ", bad);
                    wrongArgs++;
                    break;
                }
            }
            if (expStatus[i] == null) {
                expStatus[i] = TrajectoryReport.MISSING;
            }
        }
        for (int j = 0; j < m; j++) {
            if (actStatus[j] == null) {
                actStatus[j] = TrajectoryReport.EXTRA;
            }
        }

        // 4. Metrics.
        int positional = 0;
        for (int i = 0; i < Math.min(n, m); i++) {
            if (full[i][i]) {
                positional++;
            }
        }
        boolean exact = n == m && positional == n;
        int tp = toolNameOverlap(expected, actual);
        double precision = m == 0 ? (n == 0 ? 1.0 : 0.0) : (double) tp / m;
        double recall = n == 0 ? 1.0 : (double) tp / n;
        double f1 = precision + recall == 0.0 ? 0.0 : 2 * precision * recall / (precision + recall);
        int paired = anyCount + wrongArgs;
        Double argMatchRate = paired == 0 ? null : (double) anyCount / paired;
        int extra = m - paired;
        double penalty = m == 0 ? 0.0 : (double) extra / m;
        double efficiency = m == 0 ? (n == 0 ? 1.0 : 0.0) : Math.min(1.0, (double) n / m);
        int failed = 0;
        for (TrajectoryToolCall s : actual) {
            if (!s.ok()) {
                failed++;
            }
        }
        Metrics metrics = new Metrics(exact, lcsLen == n, anyCount == n, precision, recall, f1, argMatchRate,
            extra, penalty, efficiency, failed, n, m);

        double score = switch (spec.mode()) {
            case EXACT -> n == 0 && m == 0 ? 1.0 : (double) positional / Math.max(n, m);
            case IN_ORDER -> n == 0 ? 1.0 : (double) lcsLen / n;
            case ANY_ORDER -> n == 0 ? 1.0 : (double) anyCount / n;
            case F1 -> f1;
        };
        boolean passed = score >= spec.minScore() - EPS
            && (spec.maxExtraCalls() == null || extra <= spec.maxExtraCalls())
            && (spec.minEfficiency() == null || efficiency >= spec.minEfficiency() - EPS);

        List<ExpectedRow> expectedRows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            expectedRows.add(new ExpectedRow(i, expected.get(i).tool(), expStatus[i],
                expToAct[i] == -1 ? null : expToAct[i], expDetail[i]));
        }
        List<ActualRow> actualRows = new ArrayList<>();
        for (int j = 0; j < m; j++) {
            TrajectoryToolCall s = actual.get(j);
            actualRows.add(new ActualRow(j, s.tool(), s.ok(), actStatus[j],
                actToExp[j] == -1 ? null : actToExp[j], preview(s)));
        }
        return new TrajectoryReport(spec.mode(), score, spec.minScore(), passed, metrics, expectedRows, actualRows, truncated);
    }

    private static long key(int i, int j, int m) {
        return (long) i * (m + 1) + j;
    }

    private static boolean augment(int i, boolean[][] full, int[] expToAct, int[] actToExp, boolean[] visited) {
        for (int j = 0; j < visited.length; j++) {
            if (!full[i][j] || visited[j]) {
                continue;
            }
            visited[j] = true;
            if (actToExp[j] == -1 || augment(actToExp[j], full, expToAct, actToExp, visited)) {
                expToAct[i] = j;
                actToExp[j] = i;
                return true;
            }
        }
        return false;
    }

    private static int toolNameOverlap(List<ExpectedStep> expected, List<TrajectoryToolCall> actual) {
        Map<String, Integer> counts = new HashMap<>();
        for (ExpectedStep e : expected) {
            counts.merge(e.tool(), 1, Integer::sum);
        }
        int tp = 0;
        for (TrajectoryToolCall a : actual) {
            Integer left = counts.get(a.tool());
            if (left != null && left > 0) {
                counts.put(a.tool(), left - 1);
                tp++;
            }
        }
        return tp;
    }

    private static String preview(TrajectoryToolCall s) {
        if (s.args().isMissingNode()) {
            return null;
        }
        String text = s.args().toString();
        return text.length() <= ARGS_PREVIEW_CHARS ? text : text.substring(0, ARGS_PREVIEW_CHARS) + "...";
    }
}
