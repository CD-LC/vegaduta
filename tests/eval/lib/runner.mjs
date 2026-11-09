// Orchestration: run every task `repeats` times through an adapter, time it, score it.
// The clock and sleep are injectable so this is unit-tested offline with a fake adapter.
import { scoreRun } from "./score.mjs";
import { validateAdapter, validateRunResult } from "./adapter.mjs";

/**
 * @param {object} opts { suite, adapter, ctx, repeats, only?: string[], now?: () => number, onRun?: (rec) => void }
 * Returns scored run records (see stats.aggregate). A thrown adapter error or a malformed
 * RunResult is recorded as a failed run with `error`, never skipped.
 */
export async function runSuite({ suite, adapter, ctx, repeats = 1, only, now = () => performance.now(), onRun }) {
  const errs = validateAdapter(adapter);
  if (errs.length) throw new Error(`invalid adapter: ${errs.join("; ")}`);
  const tasks = only?.length ? suite.tasks.filter((t) => only.includes(t.id)) : suite.tasks;
  if (only?.length && tasks.length !== only.length) {
    const known = new Set(suite.tasks.map((t) => t.id));
    throw new Error(`unknown task id(s): ${only.filter((i) => !known.has(i)).join(", ")}`);
  }
  const records = [];
  for (const task of tasks) {
    for (let i = 0; i < repeats; i++) {
      const t0 = now();
      let result;
      try {
        result = await adapter.runTask(task, ctx);
      } catch (e) {
        result = { error: `adapter threw: ${e?.message ?? e}` };
      }
      // An adapter may report the time of the answer itself when its run also does bookkeeping the
      // platform under test does not do for a user (polling a ledger, deciding an approval).
      const latencyMs = Number.isFinite(result?.latencyMs) && result.latencyMs >= 0 ? Math.round(result.latencyMs) : Math.round(now() - t0);
      const bad = validateRunResult(result);
      if (bad.length) result = { error: `malformed RunResult: ${bad.join("; ")}` };
      const rec = { ...scoreRun(task, result, latencyMs), repeat: i + 1 };
      records.push(rec);
      onRun?.(rec);
    }
  }
  return records;
}
