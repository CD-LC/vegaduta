package com.agenticai.core.providers.localmodels;

import com.agenticai.core.providers.localmodels.ModelCatalog.Entry;
import com.agenticai.core.providers.localmodels.ModelCatalog.ToolCalling;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Picks the best catalog model for a machine and a few alternatives.
 *
 * <p><b>The pick</b> follows the catalog's memory ladder ({@code #ladder|minGB|tag} lines, the same
 * ones {@code scripts/vegaduta} uses, so the CLI and the console recommend the same model). The
 * ladder is indexed by "effective memory": system memory, except with a discrete NVIDIA GPU, where
 * it is the larger of system memory and 1.5 times the card's video memory (a model on the card is
 * much faster than the same model on the CPU, so a 24 GB card counts like a 36 GB machine). If the
 * catalog has no usable ladder, the best model that fits {@link #budgetGb} is picked instead.
 *
 * <p><b>The alternatives</b> are the other models that fit the budget, best first: native tool
 * calling beats the constrained router beats chat only, and inside a class the bigger model wins.
 * A chat-only model is never listed beside a model that can call tools. <b>The catalog</b> is every
 * row, flagged with whether it fits, so nothing is hidden from a person who wants a bigger model.
 *
 * <p><b>Memory budget</b> for "fits": 75% of system memory on Apple Silicon and CPU-only machines
 * (the rest of the stack shares it); with a discrete GPU the larger of 90% of video memory and half
 * of system memory.
 */
@Component
public class ModelRecommender {

    /** At most this many alternatives are returned. */
    static final int MAX_ALTERNATIVES = 4;

    /**
     * The pick, the runners-up that fit, and the whole catalog, in the shape of the API's {@code
     * recommended} / {@code alternatives} / {@code catalog}. {@code catalog} keeps the file's order
     * and flags every row with {@link Pick#fits}, so a picker can show "fits your machine" and
     * "needs more memory" groups without hiding anything.
     */
    public record Recommendation(Pick recommended, List<Pick> alternatives, List<Pick> catalog, double budgetGb) {}

    /**
     * One offered model with the note shown to the user and the catalog's detail columns.
     * {@code fits} is {@code ramGb <= budgetGb} for the machine the recommendation was made for.
     */
    public record Pick(
            String tag,
            String family,
            double downloadGb,
            double ramGb,
            String toolCalling,
            String note,
            double paramsB,
            long contextK,
            double vramGb,
            String licence,
            boolean thinking,
            String bestFor,
            boolean fits) {}

    private static final Comparator<Entry> BEST_FIRST = Comparator
            .comparingInt((Entry e) -> rank(e.toolCalling()))
            .thenComparing(Comparator.comparingDouble(Entry::ramGb).reversed())
            .thenComparing(Entry::tag);

    /** The memory, in GB, this machine can give a model; the cut-off for "alternatives that fit". */
    public double budgetGb(HardwareProfile hardware) {
        double ram = hardware.ramGb();
        if (hardware.hasDiscreteGpu()) {
            return Math.max(hardware.gpu().vramGb() * 0.9, ram * 0.5);
        }
        return ram * 0.75;
    }

    /** The memory figure the ladder is indexed by. */
    public double effectiveMemoryGb(HardwareProfile hardware) {
        if (hardware.hasDiscreteGpu()) {
            return Math.max(hardware.ramGb(), hardware.gpu().vramGb() * 1.5);
        }
        return hardware.ramGb();
    }

    /** Null when the catalog is empty. */
    public Recommendation recommend(HardwareProfile hardware, ModelCatalog catalog) {
        List<Entry> entries = catalog == null ? List.of() : catalog.entries();
        if (entries.isEmpty()) {
            return null;
        }
        double budget = budgetGb(hardware);
        double effective = effectiveMemoryGb(hardware);
        List<Entry> fits = entries.stream()
                .filter(e -> e.ramGb() <= budget)
                .sorted(BEST_FIRST)
                .toList();

        Entry best = ladderPick(catalog, effective)
                .orElseGet(() -> fits.isEmpty() ? smallest(entries) : fits.get(0));
        String note = best.note();
        if (effective < best.ramGb()) {
            note = ("Wants about " + trim(best.ramGb()) + " GB of memory and this machine has "
                    + (effective > 0 ? "about " + trim(effective) + " GB" : "an unknown amount")
                    + ": expect it to be slow. " + best.note()).trim();
        }
        List<Pick> alternatives = fits.stream()
                .filter(e -> !e.tag().equals(best.tag()))
                .filter(e -> best.toolCalling() == ToolCalling.NONE || e.toolCalling() != ToolCalling.NONE)
                .limit(MAX_ALTERNATIVES)
                .map(e -> pick(e, e.note(), budget))
                .toList();
        List<Pick> all = entries.stream().map(e -> pick(e, e.note(), budget)).toList();
        return new Recommendation(pick(best, note, budget), alternatives, all, budget);
    }

    private static Optional<Entry> ladderPick(ModelCatalog catalog, double effectiveGb) {
        long whole = (long) Math.floor(effectiveGb);
        for (ModelCatalog.Rung rung : catalog.ladder()) {
            if (whole >= rung.minRamGb()) {
                return catalog.find(rung.tag());
            }
        }
        return Optional.empty();
    }

    private static Entry smallest(List<Entry> entries) {
        return entries.stream()
                .filter(e -> e.toolCalling() != ToolCalling.NONE)
                .min(Comparator.comparingDouble(Entry::ramGb).thenComparing(Entry::tag))
                .orElseGet(() -> entries.stream()
                        .min(Comparator.comparingDouble(Entry::ramGb))
                        .orElseThrow());
    }

    private static Pick pick(Entry e, String note, double budgetGb) {
        return new Pick(
                e.tag(),
                e.family(),
                e.downloadGb(),
                e.ramGb(),
                e.toolCalling().wire(),
                note,
                e.paramsB(),
                e.contextK(),
                e.vramGb(),
                e.licence(),
                e.thinking(),
                e.bestFor(),
                e.ramGb() <= budgetGb);
    }

    private static int rank(ToolCalling toolCalling) {
        return switch (toolCalling) {
            case NATIVE -> 0;
            case CONSTRAINED -> 1;
            case NONE -> 2;
        };
    }

    private static String trim(double gb) {
        return gb == Math.rint(gb) ? Long.toString((long) gb) : String.format(Locale.ROOT, "%.1f", gb);
    }
}
