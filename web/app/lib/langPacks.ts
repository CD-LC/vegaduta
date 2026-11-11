"use client";

import { DEFAULT_LOCALE } from "./i18n-config";
import { isPersonalEdition } from "./edition";
import { PACK_SIZES_MB } from "./i18n-pack-sizes.generated";

// Personal-edition language packs (2026-10-29): a personal install keeps every
// non-English catalog OFF the device path until the person asks for it -
// nothing is fetched, inlined or parsed for a language that was never chosen.
// The message files still ship inside the image (they are the only offline
// source of truth - a consent prompt that needed the internet would mean a
// personal install could never go offline-and-multilingual at the same time);
// "skipped" here means skipped from what the browser downloads, not from the
// container.
//
// Consent is per-locale and survives restarts in localStorage. English needs
// no consent: it is the fallback bundle every catalog merges against, already
// on the wire.
const STORAGE_KEY = "vd.lang.packs";

function readGranted(): Set<string> {
  try {
    const raw = globalThis.localStorage?.getItem(STORAGE_KEY);
    const list = raw ? (JSON.parse(raw) as unknown) : [];
    return new Set(Array.isArray(list) ? list.filter((x): x is string => typeof x === "string") : []);
  } catch {
    return new Set();
  }
}

/** Whether switching to `code` needs a pack download + consent first. */
export function packNeedsConsent(code: string): boolean {
  if (!isPersonalEdition() || code === DEFAULT_LOCALE) return false;
  return !readGranted().has(code);
}

/** Record that the person agreed to download `code`'s pack. */
export function grantPackConsent(code: string): void {
  try {
    const granted = readGranted();
    granted.add(code);
    globalThis.localStorage?.setItem(STORAGE_KEY, JSON.stringify([...granted]));
  } catch {
    // private-mode localStorage failures just mean the prompt shows again
  }
}

/** "0.9 MB" — the number the consent prompt shows, rounded UP by the generator. */
export function packSizeLabel(code: string): string {
  const mb = PACK_SIZES_MB[code];
  return mb ? `${mb.toFixed(1)} MB` : "1 MB";
}
