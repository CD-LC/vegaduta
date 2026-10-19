// Where to get Ollama. Install pages are links only: the wizard never downloads or runs a binary for the person.
export const OLLAMA_INSTALL_LINKS = {
  mac: "https://ollama.com/download/mac",
  windows: "https://ollama.com/download/windows",
  linux: "https://ollama.com/download/linux",
} as const;
export type Os = keyof typeof OLLAMA_INSTALL_LINKS;

export function detectOs(userAgent: string): Os | null {
  if (/Mac/i.test(userAgent) && !/iPhone|iPad/i.test(userAgent)) return "mac";
  if (/Windows/i.test(userAgent)) return "windows";
  if (/Linux|X11/i.test(userAgent) && !/Android/i.test(userAgent)) return "linux";
  return null;
}

export const fmtGb = (n: number) => (Number.isInteger(n) ? String(n) : n.toFixed(1));
