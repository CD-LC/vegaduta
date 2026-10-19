/**
 * A random RFC 4122 v4 UUID that works on every origin.
 *
 * `crypto.randomUUID` only exists in secure contexts (https, localhost). On a plain-http LAN install it is
 * undefined and a bare call throws. Fall back to `getRandomValues` (available everywhere), then to
 * `Math.random` as a last resort. The result always has UUID shape, so it is safe where core parses a
 * client-supplied id as a UUID (canvas run ids).
 */
export function randomUuid(): string {
  try {
    if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
      return crypto.randomUUID();
    }
  } catch {
    // fall through to the manual id below
  }
  const bytes = new Uint8Array(16);
  let filled = false;
  try {
    if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
      crypto.getRandomValues(bytes);
      filled = true;
    }
  } catch {
    // fall through to Math.random
  }
  if (!filled) {
    for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0"));
  return `${hex.slice(0, 4).join("")}-${hex.slice(4, 6).join("")}-${hex.slice(6, 8).join("")}-${hex
    .slice(8, 10)
    .join("")}-${hex.slice(10).join("")}`;
}
