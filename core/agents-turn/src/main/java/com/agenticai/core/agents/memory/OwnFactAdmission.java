package com.agenticai.core.agents.memory;

/**
 * Admission rule for a fact a user adds by hand ({@code POST /api/memory/facts}).
 *
 * <p>The memory stores are bounded and prune oldest-first across every subject, which is fine when an
 * agent decides what to save but would let one user evict everyone else's rows with a plain HTTP loop.
 * So a hand-added fact may never push another subject's row out: the caller has a per-subject cap, and
 * when the shared store is already full room is made from the caller's OWN oldest rows or not at all.
 */
final class OwnFactAdmission {

    private OwnFactAdmission() {
    }

    /**
     * @param own       rows the caller already has in this store
     * @param total     rows in the store across every subject
     * @param maxOwn    per-subject cap for hand-added facts
     * @param maxTotal  the store's own cap (its prune threshold)
     * @return how many of the caller's own oldest rows to delete before inserting (usually 0)
     * @throws MemoryCapExceededException when the caller is at their cap, or the store is full and the
     *     caller has too few rows of their own to make room
     */
    static int ownRowsToEvict(int own, int total, int maxOwn, int maxTotal) {
        if (own >= maxOwn) {
            throw new MemoryCapExceededException(
                "You have reached the limit of " + Math.max(0, maxOwn) + " memories here. Delete one to add another.");
        }
        int needed = total + 1 - maxTotal;
        if (needed <= 0) {
            return 0;
        }
        if (needed > own) {
            throw new MemoryCapExceededException("This memory is full. Try again later or ask an admin to clear space.");
        }
        return needed;
    }
}
