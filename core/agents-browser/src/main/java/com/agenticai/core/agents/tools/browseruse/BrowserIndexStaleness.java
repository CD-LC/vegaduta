package com.agenticai.core.agents.tools.browseruse;

/**
 * Decides whether an element index the model passes to an action tool still refers to what it saw
 * in its last {@code browser_snapshot}. Pure (all inputs are plain values read by the caller), so
 * the rules are unit-testable without a browser.
 *
 * <p>Inputs come from two places: the server-side copy of the last snapshot ({@link
 * BrowserSnapshot.Data}) and one cheap in-page probe ({@link BrowserSnapshotScripts#CHECK}) that
 * reports the page's current DOM generation counter and whether the indexed element is still
 * attached and still has the role/name that was listed.
 *
 * <ul>
 *   <li>{@link Status#NO_SNAPSHOT}: the model never took a snapshot in this session.
 *   <li>{@link Status#OUT_OF_RANGE}: the index is not one the snapshot listed.
 *   <li>{@link Status#PAGE_CHANGED}: the document was replaced (navigation wiped the in-page
 *       registry), or - in {@code strict} mode - the DOM generation counter moved since the
 *       snapshot.
 *   <li>{@link Status#ELEMENT_GONE}: the element is detached, or (checked on EVERY call, whether or
 *       not the generation moved) now has a different role/name - clicking "the same index" could
 *       hit something else, and the irreversible-click heuristic must never run on a stale recorded
 *       name.
 *   <li>{@link Status#OK}: safe to act.
 * </ul>
 *
 * <p>{@code strict} (default false, {@code app.browser.v2.strict-staleness}) treats ANY structural
 * DOM change as stale, which is safest but unusable on pages that mutate constantly (carousels,
 * live widgets); the relaxed default only refuses when the specific element changed. Either way a
 * navigation always makes every index stale.
 */
final class BrowserIndexStaleness {

    private BrowserIndexStaleness() {}

    enum Status {
        OK,
        NO_SNAPSHOT,
        OUT_OF_RANGE,
        PAGE_CHANGED,
        ELEMENT_GONE
    }

    /** What the in-page probe reported for one index. */
    record Probe(
            boolean registryPresent,
            long currentGen,
            long snapshotGenInPage,
            boolean elementPresent,
            boolean elementConnected,
            String role,
            String name) {}

    /**
     * @param snapshot the server-side copy of the last snapshot, or null when none was taken
     * @param index the index the model supplied
     * @param probe the in-page probe result for that index
     * @param strict true = any DOM generation change is stale
     */
    static Status evaluate(BrowserSnapshot.Data snapshot, int index, Probe probe, boolean strict) {
        if (snapshot == null) {
            return Status.NO_SNAPSHOT;
        }
        BrowserSnapshot.Element listed = snapshot.element(index).orElse(null);
        if (listed == null) {
            return Status.OUT_OF_RANGE;
        }
        if (probe == null
                || !probe.registryPresent()
                || probe.snapshotGenInPage() != snapshot.gen()) {
            // Navigation (or a reload) wiped the in-page registry, or the registry belongs to
            // another snapshot.
            return Status.PAGE_CHANGED;
        }
        boolean genMoved = probe.currentGen() != snapshot.gen();
        if (genMoved && strict) {
            return Status.PAGE_CHANGED;
        }
        if (!probe.elementPresent() || !probe.elementConnected()) {
            return genMoved ? Status.PAGE_CHANGED : Status.ELEMENT_GONE;
        }
        // Always compared, not only when the generation counter moved: the counter counts
        // added/removed ELEMENT
        // nodes, so an in-place relabel ("Continue" -> "Pay $50" as a text-node edit) leaves it
        // unchanged.
        if (!sameIdentity(listed, probe)) {
            return Status.ELEMENT_GONE;
        }
        return Status.OK;
    }

    private static boolean sameIdentity(BrowserSnapshot.Element listed, Probe probe) {
        return listed.role().equalsIgnoreCase(probe.role() == null ? "" : probe.role())
                && listed.name().equals(BrowserSnapshot.clean(probe.name()));
    }

    /** The model-facing error for a non-OK status. */
    static String message(Status status, int index) {
        return switch (status) {
            case OK -> "";
            case NO_SNAPSHOT ->
                    "No snapshot yet for this browser session - call browser_snapshot first, then use the "
                            + "index it lists.";
            case OUT_OF_RANGE ->
                    "Index "
                            + index
                            + " is not in the latest snapshot - call browser_snapshot and use one of "
                            + "the listed indexes.";
            case PAGE_CHANGED, ELEMENT_GONE ->
                    "The page changed since the last snapshot, so index "
                            + index
                            + " may no longer be the same element - take a new snapshot (browser_snapshot) and use the fresh index.";
        };
    }
}
