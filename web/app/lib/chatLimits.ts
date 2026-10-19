/**
 * Longest chat message the server accepts. Mirrors the `@Size(max = 8000)` on
 * core's ChatRequest.message; the server answers 400 "message is too long
 * (8000 character maximum)" above it, so the composer blocks the send first.
 */
export const MAX_CHAT_MESSAGE_CHARS = 8000;

/** Length from which the composer starts showing the running count. */
export const CHAT_COUNTER_THRESHOLD = Math.floor(MAX_CHAT_MESSAGE_CHARS * 0.9);
