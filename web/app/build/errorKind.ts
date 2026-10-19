// The "check that the app-builder service is reachable at <url>" hint is only
// useful when the service really could not be reached. A 409 (archived), a
// validation message or a clipboard failure must not blame the URL.
// lib.ts words its own network/timeout errors so this can tell them apart.

export function isNetworkErrorMessage(message: string | null | undefined): boolean {
  return /can'?t reach the app builder|took too long to respond|failed to fetch|networkerror|load failed|network request failed/i.test(
    String(message || "")
  );
}
