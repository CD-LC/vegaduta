// Helpers for the "Local / OpenAI-compatible server" provider card (LOCAL_OPENAI): one-click presets for the
// servers it is for, and the allow-list entry the platform operator needs for a private address.

/** A starting point for the base-URL field. */
export interface LocalServerPreset {
  id: string;
  /** Product name, shown as-is (a product name is not translated). */
  label: string;
  /** The port the server documents by default. */
  port: number;
}

export const LOCAL_SERVER_PRESETS: readonly LocalServerPreset[] = [
  { id: "vllm", label: "vLLM", port: 8000 },
  { id: "llamacpp", label: "llama.cpp", port: 8080 },
  { id: "lmstudio", label: "LM Studio", port: 1234 },
  { id: "jan", label: "Jan", port: 1337 },
  { id: "localai", label: "LocalAI", port: 8080 },
];

/**
 * The base URL for a preset. From inside a container `localhost` is the container itself, so a platform running
 * in Docker reaches a server on the host at host.docker.internal instead.
 */
export function localServerUrl(preset: LocalServerPreset, platformInDocker: boolean): string {
  return `http://${platformInDocker ? "host.docker.internal" : "localhost"}:${preset.port}/v1`;
}

/**
 * The `host:port` entry the platform operator lists in app.security.outbound.allowed-private-hosts so the platform
 * may call this URL (private and loopback addresses are refused unless listed). Null when the URL does not parse.
 * Mirrors core's LocalOpenAiCredential#allowListEntry.
 */
export function allowListEntryFor(baseUrl: string): string | null {
  try {
    const url = new URL(baseUrl.trim());
    if (url.protocol !== "http:" && url.protocol !== "https:") return null;
    const port = url.port || (url.protocol === "https:" ? "443" : "80");
    return `${url.hostname.toLowerCase()}:${port}`;
  } catch {
    return null;
  }
}
