package com.agenticai.core.providers.modelcost;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cost-ordered model choice per modality, backed by {@code
 * control_plane.media_model_cost} (migration V263).
 *
 * <h2>Why a curated, costed table and not "cheapest id the vendor lists"</h2>
 * The cheapest model a provider exposes is routinely one that cannot do the
 * job (a 1B text model that cannot call tools, a preview id that 404s next
 * week). So the rule is: <b>a model is only ever picked by cost if an operator
 * listed it</b> - being in the table IS the quality floor - and among listed,
 * enabled rows the cheapest {@code typical_call_cost_usd} wins, with {@code
 * quality_rank} (higher first) breaking ties. That replaces keyword matching
 * ("flash-lite", "mini", "haiku"...) with a number someone can read, audit
 * and change with one SQL UPDATE, no redeploy.
 *
 * <h2>Failure posture</h2>
 * Every read degrades to {@link #BUILT_IN_DEFAULTS}: an unmigrated database,
 * an empty table or a control-plane hiccup must never take image or music
 * generation down with it. The defaults are the migration's own seed rows for
 * the media modalities, so behaviour is identical with or without the table.
 */
@Service
public class MediaModelCostService {

    private static final Logger log = LoggerFactory.getLogger(MediaModelCostService.class);

    /** One candidate, cheapest-first in every list this class returns. */
    public record ModelChoice(MediaModality modality, String provider, String modelId, BigDecimal typicalCallCostUsd, int qualityRank) {
    }

    static final Comparator<ModelChoice> CHEAPEST_FIRST = Comparator
        .comparing(ModelChoice::typicalCallCostUsd)
        .thenComparing(Comparator.comparingInt(ModelChoice::qualityRank).reversed())
        .thenComparing(ModelChoice::provider)
        .thenComparing(ModelChoice::modelId);

    /** Mirrors V263's IMAGE/VIDEO/MUSIC seed rows. TEXT is deliberately absent: with no table, text falls through to the existing preference logic untouched. */
    static final List<ModelChoice> BUILT_IN_DEFAULTS = List.of(
        new ModelChoice(MediaModality.IMAGE, "OPENAI", "gpt-image-1", new BigDecimal("0.011"), 2),
        new ModelChoice(MediaModality.IMAGE, "GOOGLE", "gemini-2.5-flash-image", new BigDecimal("0.039"), 1),
        new ModelChoice(MediaModality.IMAGE, "GOOGLE_VERTEX", "imagen-3.0-generate-002", new BigDecimal("0.040"), 1),
        new ModelChoice(MediaModality.VIDEO, "GOOGLE_VERTEX", "veo-3.1-fast-generate-001", new BigDecimal("0.80"), 2),
        new ModelChoice(MediaModality.VIDEO, "GOOGLE_VERTEX", "veo-3.0-fast-generate-001", new BigDecimal("1.20"), 1),
        new ModelChoice(MediaModality.MUSIC, "ELEVENLABS", "music_v2_5", new BigDecimal("0.075"), 3),
        new ModelChoice(MediaModality.MUSIC, "ELEVENLABS", "music_v2", new BigDecimal("0.075"), 2),
        new ModelChoice(MediaModality.MUSIC, "ELEVENLABS", "music_v1", new BigDecimal("0.075"), 1)
    );

    private final MediaModelCostRepository repository;

    public MediaModelCostService(MediaModelCostRepository repository) {
        this.repository = repository;
    }

    /** Every enabled model for {@code modality}, cheapest first. Never empty for IMAGE/VIDEO/MUSIC; may be empty for TEXT/SPEECH. */
    public List<ModelChoice> cheapestFirst(MediaModality modality) {
        List<ModelChoice> fromTable = readTable(modality);
        List<ModelChoice> source = fromTable.isEmpty()
            ? BUILT_IN_DEFAULTS.stream().filter(c -> c.modality() == modality).toList()
            : fromTable;
        return order(source);
    }

    /** {@link #cheapestFirst} narrowed to one provider (case-insensitive). */
    public List<ModelChoice> cheapestFirst(MediaModality modality, String provider) {
        return cheapestFirst(modality).stream().filter(c -> c.provider().equalsIgnoreCase(provider)).toList();
    }

    /** The cheapest listed model for {@code provider}, if any row covers it. */
    public Optional<String> cheapestModel(MediaModality modality, String provider) {
        return cheapestFirst(modality, provider).stream().findFirst().map(ModelChoice::modelId);
    }

    /**
     * The cheapest listed model for {@code provider} that the caller can
     * actually use right now ({@code availableModelIds} - e.g. the provider's
     * live catalog). Empty when no listed model is available, which callers
     * treat as "fall through to the old logic", never as an error.
     */
    public Optional<String> cheapestAmong(MediaModality modality, String provider, Collection<String> availableModelIds) {
        if (availableModelIds == null || availableModelIds.isEmpty()) {
            return Optional.empty();
        }
        return cheapestFirst(modality, provider).stream()
            .map(ModelChoice::modelId)
            .filter(availableModelIds::contains)
            .findFirst();
    }

    /**
     * Position of {@code provider} in the cost order for {@code modality} -
     * its cheapest listed model's rank. Unlisted providers sort last ({@link
     * Integer#MAX_VALUE}), so a caller sorting by this keeps its own original
     * order among unlisted ones (use a stable sort).
     */
    public int providerRank(MediaModality modality, String provider) {
        List<ModelChoice> ordered = cheapestFirst(modality);
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).provider().equalsIgnoreCase(provider)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** Pure ordering - package-private so the rule is unit-testable without a repository. */
    static List<ModelChoice> order(List<ModelChoice> choices) {
        return choices.stream().sorted(CHEAPEST_FIRST).toList();
    }

    private List<ModelChoice> readTable(MediaModality modality) {
        try {
            return repository.findAllByModalityAndEnabledTrue(modality.name()).stream()
                .map(row -> new ModelChoice(
                    modality,
                    row.getProvider().toUpperCase(Locale.ROOT),
                    row.getModelId(),
                    row.getTypicalCallCostUsd(),
                    row.getQualityRank()))
                .toList();
        } catch (RuntimeException e) {
            log.warn("media_model_cost unreadable for {} - using built-in cost order", modality, e);
            return List.of();
        }
    }
}
