package com.agenticai.core.quality.dataset;

import java.util.List;

/**
 * Renders an {@link EvalRun}'s case results as a JUnit XML report (V343 -
 * the C2 CI contract: {@code GET .../runs/{id}/junit} feeds a standard
 * Jenkins/GitHub Actions test-report step).
 *
 * <p>Mapping: one {@code <testcase>} per case result, {@code name} =
 * {@code case #<position+1>} (positions are stable display order);
 * {@code <failure>} when {@code passed == Boolean.FALSE}, {@code <error>}
 * when the case's replay errored, {@code <skipped>} when the case was never
 * evaluable (UNAVAILABLE judges / majority-undecided repeats). A cancelled
 * or budget-capped run reports its completed cases truthfully - the CI
 * caller can additionally check the run's own {@code status}/{@code verdict}
 * fields.
 *
 * <p>Hand-built rather than JAXB: the document is ~200 bytes per case, the
 * module carries no XML dependency, and every string passes through {@link
 * #escape} so a case message containing {@code <tool>} markup can never
 * break the report.
 */
final class JunitReport {

    private JunitReport() {
    }

    static String of(String datasetName, EvalRun run, List<EvalRunCaseResult> results) {
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        StringBuilder cases = new StringBuilder();
        for (EvalRunCaseResult r : results) {
            String name = "case #" + (r.getPosition() + 1);
            cases.append("  <testcase classname=\"").append(escape(datasetName))
                .append("\" name=\"").append(escape(name)).append("\">");
            if (r.getStatus() == EvalCaseResultStatus.ERROR) {
                errors++;
                cases.append("<error message=\"").append(escape(r.getErrorMessage())).append("\"/>");
            } else if (Boolean.FALSE.equals(r.getPassed())) {
                failures++;
                cases.append("<failure message=\"").append(escape(
                    failureMessage(r))).append("\"/>");
            } else if (r.getPassed() == null) {
                skipped++;
                cases.append("<skipped message=\"").append(escape(
                    r.getStatus() == EvalCaseResultStatus.UNAVAILABLE
                        ? "judges unavailable - case not evaluable"
                        : "not evaluable")).append("\"/>");
            }
            cases.append("</testcase>\n");
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuite name=\"" + escape(datasetName) + "\""
            + " tests=\"" + results.size() + "\""
            + " failures=\"" + failures + "\""
            + " errors=\"" + errors + "\""
            + " skipped=\"" + skipped + "\""
            + ">\n" + cases + "</testsuite>\n";
    }

    private static String failureMessage(EvalRunCaseResult r) {
        if (Boolean.FALSE.equals(r.getExpectedToolsSatisfied())) {
            return "expected-tools assertion violated";
        }
        return r.getOverallScore() != null
            ? "score " + r.getOverallScore() + " below the run's pass bar"
            : "case did not pass";
    }

    /** XML-escape, null-safe (null becomes the empty attribute value). */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    // Strip XML-illegal control chars outright rather than
                    // emitting a document no parser accepts.
                    if (c >= 0x20 || c == '\n' || c == '\t' || c == '\r') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
