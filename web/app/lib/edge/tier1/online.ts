// Tiny shared probe: is the browser online, or unknown? Split out of ../tier1.ts.

export function readOnlineFlag(): boolean | undefined {
  try {
    return typeof navigator !== "undefined" && typeof navigator.onLine === "boolean" ? navigator.onLine : undefined;
  } catch {
    return undefined;
  }
}
