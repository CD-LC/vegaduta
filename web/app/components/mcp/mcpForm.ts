/** One arg per line (or space-separated on a single line). */
export function parseArgs(text: string): string[] {
  return text
    .split(/\r?\n/)
    .flatMap((line: string) => line.trim().split(/\s+/))
    .filter(Boolean);
}

/** KEY=VALUE per line into a flat string map; lines without a key are ignored. */
export function parseEnv(text: string): Record<string, string> {
  const env: Record<string, string> = {};
  text.split(/\r?\n/).forEach((line: string) => {
    const idx = line.indexOf("=");
    if (idx > 0) {
      env[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
    }
  });
  return env;
}
