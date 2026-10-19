/**
 * The "Repeats per case" Run option as the request field.
 *
 * Always sent when it is a valid 1-5 value, INCLUDING 1. The panel used to
 * omit it for 1 ("a default run stays a plain POST"), but core treats an
 * omitted value as app.quality.eval.default-repeats - 3 on prod - so a run
 * set to 1 repetition executed three and the run row said "x3 reps"
 * (prod signed-in pass 2026-10-05, bug 7). Omitted only when the field is
 * empty or out of range, where the server default is the honest answer.
 */
export function repeatsOption(raw: string): number | undefined {
  const repeats = parseInt(raw, 10);
  return Number.isFinite(repeats) && repeats >= 1 && repeats <= 5 ? repeats : undefined;
}
