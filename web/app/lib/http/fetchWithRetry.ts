// Shared fetch helpers for the public, unauthenticated share / demo clients
// (a/[shareId], agent/[shareId], workflow/[shareId], try/magicClient). Each of
// those used to carry its own byte-identical copy (FE-016).

/**
 * fetch() with a hard timeout: aborts the request after `timeoutMs`.
 *
 * Note: the timeout's AbortSignal replaces any `signal` in `options` - the
 * callers never pass one, and this keeps the pre-merge behaviour exactly.
 */
export async function fetchWithTimeout(url: string, options: RequestInit, timeoutMs: number): Promise<Response> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timeout);
  }
}

/**
 * fetchWithTimeout, retried with exponential backoff (100ms, 200ms, 400ms...)
 * when the request THROWS - a network failure or the timeout abort. A request
 * that gets any HTTP response (including 4xx/5xx) resolves immediately with
 * that response; callers map non-2xx statuses themselves. After `maxRetries`
 * extra attempts the last thrown error is rethrown unchanged.
 */
export async function fetchWithRetry(
  url: string,
  options: RequestInit,
  timeoutMs: number,
  maxRetries: number = 2
): Promise<Response> {
  // unknown: a catch binding can hold any thrown value; rethrown unchanged.
  let lastError: unknown = null;
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      return await fetchWithTimeout(url, options, timeoutMs);
    } catch (error) {
      lastError = error;
      if (attempt < maxRetries) {
        await new Promise((resolve) => setTimeout(resolve, Math.pow(2, attempt) * 100));
      }
    }
  }
  throw lastError || new Error("Fetch failed after retries");
}
