// When to ask the server about a research run again. Pure so the policy -
// back off on failures, slow down while parked on an approval, idle while the
// tab is hidden, and eventually stop - is unit-tested.

export const BASE_POLL_MS = 4000;
export const PARKED_POLL_MS = 30000;
export const HIDDEN_POLL_MS = 15000;
export const MAX_POLL_MS = 60000;
/** After this many consecutive failed polls the loop stops until the user asks again. */
export const GIVE_UP_AFTER = 8;

export function nextPollDelay(opts: { failures: number; parked?: boolean; hidden?: boolean }): number {
  let delay = opts.parked ? PARKED_POLL_MS : BASE_POLL_MS;
  if (opts.hidden) delay = Math.max(delay, HIDDEN_POLL_MS);
  if (opts.failures > 0) delay = delay * 2 ** Math.min(opts.failures, 5);
  return Math.min(MAX_POLL_MS, delay);
}

export function shouldGiveUp(failures: number): boolean {
  return failures >= GIVE_UP_AFTER;
}
