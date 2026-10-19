package com.agenticai.core.quality.calibration;

import com.agenticai.core.quality.calibration.JudgeCalibration.LabeledPair;
import com.agenticai.core.quality.calibration.JudgeCalibrationService.CalibrationResponse;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stored judge-calibration sets: CRUD over human-labelled pairwise comparisons, a run that calibrates the
 * tenant's judge against a stored set and PERSISTS the result (kappa, order-swap consistency, n, judge model,
 * timestamp) so drift is visible over time, and the judge-trust lookup that stamps eval runs
 * ({@link JudgeTrust}).
 *
 * <p>One switch for the whole feature: {@code app.quality.eval.calibration.enabled} (default false), read through
 * {@link JudgeCalibrationService#isEnabled()}. With it off every method here answers 404 (the existing endpoint's
 * contract) except {@link #trustFor}, which returns empty so eval responses are unchanged. The inline-labels
 * endpoint is untouched and still stores nothing.
 *
 * <p>Tenant isolation is the schema-per-tenant routing every quality table relies on (no tenant predicate in any
 * query); a set that belongs to a DIFFERENT agent than the URL names is a 404, never a 403, so existence is not
 * leaked across deep links.
 */
@Service
public class JudgeCalibrationSetService {

    private static final Logger log = LoggerFactory.getLogger(JudgeCalibrationSetService.class);

    static final int MAX_NAME_CHARS = 255;
    static final int MAX_RATIONALE_CHARS = 2000;
    static final int DEFAULT_HISTORY_LIMIT = 20;
    static final int MAX_HISTORY_LIMIT = 100;

    /** Trust verdicts stamped on eval runs. */
    public static final String STATUS_UNCALIBRATED = "UNCALIBRATED";
    public static final String STATUS_LOW = "LOW";
    public static final String STATUS_OK = "OK";

    /** Hard floor on a stored set before it may be run: fewer items says nothing about the judge. */
    @Value("${app.quality.eval.calibration.set-min-examples:20}")
    int setMinExamples = 20;

    /**
     * Ceiling on items per stored set. The run is synchronous with two judge calls per item, so this bounds
     * how long one HTTP call can hold a thread and how much it can spend.
     */
    @Value("${app.quality.eval.calibration.set-max-examples:30}")
    int setMaxExamples = 30;

    /** A judge whose latest kappa is below this is LOW; no calibration at all is UNCALIBRATED. */
    @Value("${app.quality.eval.calibration.kappa-threshold:0.6}")
    double kappaThreshold = 0.6;

    private final JudgeCalibrationStore store;
    private final JudgeCalibrationService calibrationService;

    public JudgeCalibrationSetService(JudgeCalibrationStore store, JudgeCalibrationService calibrationService) {
        this.store = store;
        this.calibrationService = calibrationService;
    }

    /** One row as the caller supplies it (pasted CSV rows or JSON). */
    public record ItemInput(String input, String outputA, String outputB, String humanLabel, String rationale) {
    }

    /** What an eval run reports about its judge: {@code status} is UNCALIBRATED, LOW or OK. */
    public record JudgeTrust(String status, Double kappa, Integer n) {
    }

    // ------------------------------------------------------------------ sets

    public JudgeCalibrationSet createSet(UUID agentId, String name, List<ItemInput> items) {
        requireEnabled();
        String validName = requireName(name);
        List<ItemInput> rows = items == null ? List.of() : items;
        validateItems(rows, 0);
        String createdBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        JudgeCalibrationSet set = store.saveSet(new JudgeCalibrationSet(agentId, validName, createdBy));
        saveItems(set, rows, 0);
        return set;
    }

    public List<JudgeCalibrationSet> listSets(UUID agentId) {
        requireEnabled();
        return store.listSets(agentId);
    }

    public long countItems(UUID setId) {
        return store.countItems(setId);
    }

    /** Loads a set, 404ing on absence OR on an agent mismatch. */
    public JudgeCalibrationSet requireSet(UUID agentId, UUID setId) {
        requireEnabled();
        JudgeCalibrationSet set = store.findSet(setId).orElseThrow(() -> notFound(setId));
        if (!set.getAgentId().equals(agentId)) {
            throw notFound(setId);
        }
        return set;
    }

    public List<JudgeCalibrationItem> listItems(UUID agentId, UUID setId) {
        requireSet(agentId, setId);
        return store.listItems(setId);
    }

    public JudgeCalibrationSet renameSet(UUID agentId, UUID setId, String name) {
        JudgeCalibrationSet set = requireSet(agentId, setId);
        set.rename(requireName(name));
        return store.saveSet(set);
    }

    public List<JudgeCalibrationItem> addItems(UUID agentId, UUID setId, List<ItemInput> items) {
        JudgeCalibrationSet set = requireSet(agentId, setId);
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Provide at least one labelled item.");
        }
        int existing = (int) store.countItems(setId);
        validateItems(items, existing);
        List<JudgeCalibrationItem> saved = saveItems(set, items, existing);
        set.touch();
        store.saveSet(set);
        return saved;
    }

    public void deleteItem(UUID agentId, UUID setId, UUID itemId) {
        JudgeCalibrationSet set = requireSet(agentId, setId);
        store.findItem(setId, itemId).orElseThrow(() -> notFound(itemId));
        store.deleteItem(setId, itemId);
        set.touch();
        store.saveSet(set);
    }

    public void deleteSet(UUID agentId, UUID setId) {
        JudgeCalibrationSet set = requireSet(agentId, setId);
        // The store removes the set's items and results with it.
        store.deleteSet(set.getId());
    }

    // ------------------------------------------------------------------ run + history

    /**
     * Calibrates the judge against a stored set and records the result. Not transactional: the judge calls
     * take seconds and must not hold a connection; only the single result insert is a write.
     *
     * <p>A run in which the judge produced no usable verdict at all (outage, bad key) is returned to the caller
     * with its warnings but NOT recorded: nothing was measured, and recording it would make a good judge look
     * uncalibrated for the next eval run just because a provider blipped.
     */
    public CalibrationResponse calibrateSet(UUID agentId, UUID setId) {
        JudgeCalibrationSet set = requireSet(agentId, setId);
        List<JudgeCalibrationItem> items = store.listItems(setId);
        validateRunnable(items);
        List<LabeledPair> pairs = new ArrayList<>(items.size());
        for (JudgeCalibrationItem item : items) {
            pairs.add(new LabeledPair(item.getId().toString(), item.getInput(), item.getOutputA(),
                item.getOutputB(), item.getHumanLabel()));
        }
        CalibrationResponse response = calibrationService.runPairs(agentId, pairs);
        JudgeCalibration.Report report = response.report();
        if (report.usable() == 0) {
            return response;
        }
        String createdBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        JudgeCalibrationResult saved = store.saveResult(new JudgeCalibrationResult(
            set.getId(), agentId, response.judgeModel(), response.agentModel(), report.examples(),
            report.usable(), report.consistent(), report.cohenKappa(), report.orderSwapConsistency(),
            report.rawAgreement(), report.interpretation(), response.selfPreferenceWarning(), createdBy));
        return response.withRecord(set.getId(), saved.getId(), saved.getCreatedAt());
    }

    /** Newest first; {@code setId} null = every set of the agent. */
    public List<JudgeCalibrationResult> history(UUID agentId, UUID setId, Integer limit) {
        requireEnabled();
        int size = limit == null ? DEFAULT_HISTORY_LIMIT : Math.max(1, Math.min(MAX_HISTORY_LIMIT, limit));
        if (setId != null) {
            requireSet(agentId, setId);
            return store.resultsForSet(setId, size);
        }
        return store.resultsForAgent(agentId, size);
    }

    // ------------------------------------------------------------------ judge trust

    /**
     * The trust verdict for a judge model id (an eval run's {@code judgeModel}), or empty when the feature is
     * off, the run used no LLM judge, or the lookup failed - it must never break a run response, which is why
     * every failure degrades to "no field".
     */
    public Optional<JudgeTrust> trustFor(String judgeModel) {
        if (!calibrationService.isEnabled() || judgeModel == null || judgeModel.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<JudgeCalibrationResult> latest =
                store.latestForJudgeModel(judgeModel);
            return Optional.of(classify(latest.orElse(null), kappaThreshold));
        } catch (RuntimeException e) {
            log.warn("Judge-trust lookup failed for '{}' - reporting no judgeCalibration: {}", judgeModel,
                e.toString());
            return Optional.empty();
        }
    }

    /** Pure classification, package-visible for tests. A null result (never calibrated) is UNCALIBRATED. */
    static JudgeTrust classify(JudgeCalibrationResult latest, double threshold) {
        if (latest == null) {
            return new JudgeTrust(STATUS_UNCALIBRATED, null, null);
        }
        Double kappa = latest.getKappa();
        if (kappa == null) {
            // Undefined kappa (single-label sample, nothing consistent) verifies nothing.
            return new JudgeTrust(STATUS_UNCALIBRATED, null, latest.getN());
        }
        return new JudgeTrust(kappa < threshold ? STATUS_LOW : STATUS_OK, kappa, latest.getN());
    }

    // ------------------------------------------------------------------ validation

    private void requireEnabled() {
        if (!calibrationService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Judge calibration is not enabled (app.quality.eval.calibration.enabled).");
        }
    }

    private static ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Calibration resource not found: " + id);
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A calibration set needs a name.");
        }
        if (trimmed.length() > MAX_NAME_CHARS) {
            throw new IllegalArgumentException("A calibration set name is limited to " + MAX_NAME_CHARS
                + " characters.");
        }
        return trimmed;
    }

    /** Validates new rows against the per-set ceiling given how many items the set already holds. */
    void validateItems(List<ItemInput> rows, int existing) {
        if (existing + rows.size() > setMaxExamples) {
            throw new IllegalArgumentException("A calibration set holds at most " + setMaxExamples
                + " items (it has " + existing + ", you sent " + rows.size() + "); the run is synchronous with two "
                + "judge calls per item. Limit: app.quality.eval.calibration.set-max-examples.");
        }
        int max = JudgeCalibrationService.MAX_FIELD_CHARS;
        for (int i = 0; i < rows.size(); i++) {
            ItemInput r = rows.get(i);
            String where = "Row " + (i + 1) + ": ";
            if (r == null || blank(r.input()) || blank(r.outputA()) || blank(r.outputB())) {
                throw new IllegalArgumentException(where + "'input', 'outputA' and 'outputB' are required.");
            }
            if (normalizeLabel(r.humanLabel()) == null) {
                throw new IllegalArgumentException(where + "'humanLabel' must be \"A\" or \"B\".");
            }
            if (r.input().length() > max || r.outputA().length() > max || r.outputB().length() > max) {
                throw new IllegalArgumentException(where + "each text field is limited to " + max + " characters.");
            }
            if (r.rationale() != null && r.rationale().length() > MAX_RATIONALE_CHARS) {
                throw new IllegalArgumentException(where + "'rationale' is limited to " + MAX_RATIONALE_CHARS
                    + " characters.");
            }
        }
    }

    /** The run-time guard: minimum n (configurable, default 20) and the synchronous-cost ceiling. */
    void validateRunnable(List<JudgeCalibrationItem> items) {
        if (items.size() < setMinExamples) {
            throw new IllegalArgumentException("This calibration set has " + items.size() + " item(s); at least "
                + setMinExamples + " are required before it can be run (" + JudgeCalibration.RECOMMENDED_MIN_EXAMPLES
                + " recommended) - fewer says nothing about the judge. Add more labelled rows.");
        }
        if (items.size() > setMaxExamples) {
            throw new IllegalArgumentException("This calibration set has " + items.size() + " items; at most "
                + setMaxExamples + " can run synchronously (" + (setMaxExamples * 2) + " judge calls).");
        }
        for (int i = 0; i < items.size(); i++) {
            JudgeCalibrationItem item = items.get(i);
            if (blank(item.getOutputB())) {
                throw new IllegalArgumentException("Item " + (i + 1) + " has no outputB; pairwise calibration "
                    + "needs two answers per item.");
            }
        }
    }

    private List<JudgeCalibrationItem> saveItems(JudgeCalibrationSet set, List<ItemInput> rows, int startPosition) {
        List<JudgeCalibrationItem> entities = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            ItemInput r = rows.get(i);
            entities.add(new JudgeCalibrationItem(set.getId(), startPosition + i, r.input(), r.outputA(),
                r.outputB(), normalizeLabel(r.humanLabel()), r.rationale()));
        }
        return store.saveItems(entities);
    }

    static String normalizeLabel(String label) {
        if (label == null) {
            return null;
        }
        String t = label.trim().toUpperCase(Locale.ROOT);
        return t.equals("A") || t.equals("B") ? t : null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
