// Browser probes feeding callPreflight.ts. Feature-detection ONLY: nothing
// here prompts for a permission, opens a device, downloads or fetches
// anything, and nothing here throws - Safari iOS and Firefox lack several of
// these APIs (Permissions "microphone", navigator.connection, MediaRecorder on
// older Safari), and an absent API is an answer, not an error.

import type { MicState } from "./callPreflight";

export function mediaRecorderSupported(): boolean {
  try {
    return typeof window !== "undefined" && typeof (window as any).MediaRecorder !== "undefined";
  } catch {
    return false;
  }
}

/** What we can know about the microphone WITHOUT asking for it. */
export async function probeMicState(): Promise<MicState> {
  try {
    if (typeof window === "undefined" || typeof navigator === "undefined") return "unknown";
    if (window.isSecureContext === false) return "insecure";
    if (!navigator.mediaDevices || typeof navigator.mediaDevices.getUserMedia !== "function") {
      return "unsupported";
    }
    const permissions: any = (navigator as any).permissions;
    if (!permissions || typeof permissions.query !== "function") return "unknown";
    // Firefox rejects the "microphone" name with a TypeError - caught below.
    const status = await permissions.query({ name: "microphone" });
    if (status?.state === "granted") return "granted";
    if (status?.state === "denied") return "denied";
    if (status?.state === "prompt") return "prompt";
    return "unknown";
  } catch {
    return "unknown";
  }
}

export interface ConnectionHints {
  saveData: boolean;
  effectiveType: string | null;
  cellular: boolean;
  mobile: boolean;
  /** navigator.connection exists at all. False on Firefox and Safari, where
   * nothing can be known about the connection. */
  connectionKnown: boolean;
}

/** Hints for decideVoiceModelFetch. navigator.connection is Chromium-only;
 * where it is missing, "is this a phone" is the only honest stand-in for
 * "is this probably metered". */
export function readConnectionHints(): ConnectionHints {
  const hints: ConnectionHints = {
    saveData: false,
    effectiveType: null,
    cellular: false,
    mobile: false,
    connectionKnown: false,
  };
  try {
    if (typeof navigator === "undefined") return hints;
    const nav: any = navigator;
    const connection = nav.connection || nav.mozConnection || nav.webkitConnection;
    if (connection) {
      hints.connectionKnown = true;
      hints.saveData = connection.saveData === true;
      hints.effectiveType = typeof connection.effectiveType === "string" ? connection.effectiveType : null;
      hints.cellular = connection.type === "cellular";
    }
    if (typeof nav.userAgentData?.mobile === "boolean") {
      hints.mobile = nav.userAgentData.mobile;
    } else {
      const ua = String(nav.userAgent || "");
      // iPadOS reports a desktop Safari UA; touch points tell it apart.
      hints.mobile = /Mobi|Android|iPhone|iPad|iPod/i.test(ua) || (/Macintosh/.test(ua) && nav.maxTouchPoints > 1);
    }
  } catch {
    // Keep the defaults.
  }
  return hints;
}

/** transformers.js / kokoro-js progress events carry byte counts per FILE,
 * not for the whole model, so a percentage is only meaningful against the
 * file currently moving. The tiny config/tokenizer files finish instantly
 * and would flash "100%" before the weights even begin, so files under 1 MB
 * report no percentage at all. Clamp and null out anything that isn't a
 * number. */
export function progressPercent(p: { loaded: number; total: number } | null | undefined): number | null {
  if (!p || !(p.total >= 1_000_000) || !(p.loaded >= 0)) return null;
  return Math.max(0, Math.min(100, Math.round((p.loaded / p.total) * 100)));
}
