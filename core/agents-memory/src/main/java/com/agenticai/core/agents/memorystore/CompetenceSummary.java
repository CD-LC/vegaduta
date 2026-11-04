package com.agenticai.core.agents.memorystore;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Renders the competence counters as a short English sentence pair, by template. No model is involved and nothing from a
 * user or a model can reach the text: it is built from fixed words, the platform task-class identifier (re-validated
 * here against a strict pattern, a row that fails it is skipped), counts and a rounded Wilson bound.
 *
 * <p>A class is mentioned only once it has at least {@code minSample} verified outcomes (UNKNOWN does not count towards
 * the sample), so "knows its limits" never rests on two lucky runs. The overflow bucket is never named.
 */
final class CompetenceSummary {

    private static final Pattern SAFE_CLASS = Pattern.compile("[a-z0-9][a-z0-9_.:+~-]{0,95}");

    private CompetenceSummary() {}

    static Optional<String> render(List<CompetenceRow> rows, CompetenceProperties p, int tokenBudget) {
        List<CompetenceRow> eligible = rows.stream()
                .filter(r -> !CompetenceStorage.OVERFLOW_CLASS.equals(r.taskClass()))
                .filter(r -> SAFE_CLASS.matcher(r.taskClass()).matches())
                .filter(r -> r.verifiedSample() >= p.getMinSample())
                .toList();
        List<CompetenceRow> good = eligible.stream()
                .filter(r -> r.lowerBound() >= p.getGoodLowerBound())
                .sorted(Comparator.comparingDouble(CompetenceRow::lowerBound)
                        .reversed()
                        .thenComparing(CompetenceRow::taskClass))
                .toList();
        List<CompetenceRow> weak = eligible.stream()
                .filter(r -> r.successRate() < p.getUnreliableRate())
                .sorted(Comparator.comparingDouble(CompetenceRow::successRate).thenComparing(CompetenceRow::taskClass))
                .toList();
        if (good.isEmpty() && weak.isEmpty()) {
            return Optional.empty();
        }
        int limit = tokenBudget > 0 ? tokenBudget : Integer.MAX_VALUE;
        for (int per = p.getMaxClassesPerSide(); per >= 1; per--) {
            String text = sentence(good, weak, per);
            if (MemoryTokens.estimate(text) <= limit) {
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }

    private static String sentence(List<CompetenceRow> good, List<CompetenceRow> weak, int per) {
        StringBuilder sb = new StringBuilder("Track record counted by the platform from verified outcomes, not self-reported.");
        append(sb, "Good at", good, per);
        append(sb, "Unreliable at", weak, per);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, List<CompetenceRow> rows, int per) {
        if (rows.isEmpty()) {
            return;
        }
        sb.append(' ').append(label).append(": ");
        for (int i = 0; i < Math.min(per, rows.size()); i++) {
            CompetenceRow r = rows.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(r.taskClass())
                    .append(" (n=")
                    .append(r.verifiedSample())
                    .append(", lower bound ")
                    .append(String.format(Locale.ROOT, "%.2f", r.lowerBound()))
                    .append(')');
        }
        sb.append('.');
    }
}
