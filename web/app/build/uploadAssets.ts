// Shared asset upload for the chat pane and the Design tab (moved out of page.tsx).
import { appBuilder } from "./lib";

/** File -> base64 in chunks (the byte-at-a-time `+=` this replaced was
 * quadratic on anything bigger than a thumbnail). */
async function fileToBase64(file: File) {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let bin = "";
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return btoa(bin);
}

/** Shared asset upload for ChatPane's paperclip button and the Design tab's
 * Upload button (they used to carry two copies of the same loop). Each file
 * uploads independently; a failure on one doesn't abort the rest, and the
 * error surfaces through the page's alert like every other call here. */
export async function uploadAssets(
  projectId: string,
  fileList: FileList | File[] | null | undefined,
  onError: (message: string) => void,
  onDone?: () => void
) {
  for (const file of Array.from(fileList || [])) {
    try {
      await appBuilder.uploadAsset(projectId, file.name, await fileToBase64(file));
    } catch (e) {
      onError(e instanceof Error ? e.message : String(e));
    }
  }
  onDone?.();
}
