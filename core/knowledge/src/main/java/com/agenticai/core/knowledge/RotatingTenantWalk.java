package com.agenticai.core.knowledge;

import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Walks the active tenant schemas starting from a cursor that advances across ticks. When a tick's
 * wall-clock budget runs out, the next tick resumes at the first tenant that was not served, so
 * tenants late in the iterator's order are not starved by early ones. State is a single int per
 * runner (bounded).
 *
 * <p>Relies on {@link TenantSchemaIterator} returning tenants in a stable order between ticks; if
 * the set changes the worst case is one tick of uneven order. Each runner owns its own walk and
 * calls it from one scheduler thread, so no synchronisation is needed. Cursors are per runner: in
 * per-job claiming mode several replicas each start at the first tenant and only drift apart as
 * their budgets run out, so under a persistently spent budget they can converge on the same early
 * tenants.
 */
final class RotatingTenantWalk {

    private int offset;

    void run(
            TenantSchemaIterator iterator,
            BooleanSupplier budgetSpent,
            Consumer<String> perTenant) {
        int start = offset;
        int[] idx = {0};
        int[] stopAt = {-1};
        // Pass 1: tenants from the cursor to the end.
        iterator.forEachActiveTenantSchema(
                schema -> visit(idx[0]++, schema, start, true, stopAt, budgetSpent, perTenant));
        // Pass 2 (only after a full pass 1): the tenants before the cursor.
        if (stopAt[0] < 0 && start > 0) {
            idx[0] = 0;
            iterator.forEachActiveTenantSchema(
                    schema ->
                            visit(idx[0]++, schema, start, false, stopAt, budgetSpent, perTenant));
        }
        offset = stopAt[0] >= 0 ? stopAt[0] : 0;
    }

    private static void visit(
            int i,
            String schema,
            int start,
            boolean tail,
            int[] stopAt,
            BooleanSupplier budgetSpent,
            Consumer<String> perTenant) {
        if (stopAt[0] >= 0 || (tail ? i < start : i >= start)) {
            return;
        }
        if (budgetSpent.getAsBoolean()) {
            stopAt[0] = i;
            return;
        }
        perTenant.accept(schema);
    }
}
