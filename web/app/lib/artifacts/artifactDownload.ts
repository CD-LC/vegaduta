// Artifacts pane - browser-only helpers for Copy and Download. Kept out of the
// pure modules so those stay importable from any test environment.

/** Copy text. Resolves false (never throws) when the clipboard is unavailable or denied. */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (typeof navigator === "undefined" || !navigator.clipboard) return false;
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

/**
 * Save `content` as a file via a Blob object URL. The ORIGINAL body is saved,
 * unmodified: what the person reads in the Code tab is what lands on disk. (An
 * .html/.svg saved this way and later opened as a file runs as any local file
 * would - the preview sandbox protects THIS page only.)
 */
export function downloadText(filename: string, mime: string, content: string): void {
  if (typeof document === "undefined") return;
  const blob = new Blob([content], { type: `${mime};charset=utf-8` });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.rel = "noopener";
  a.style.display = "none";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
