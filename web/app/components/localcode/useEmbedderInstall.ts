"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { edgeEnabled } from "../../lib/edge/capabilities";
import * as indexer from "../../lib/localcode/index/indexer";
import type { EmbedderInstallState } from "./LocalCodeIndexCard";
import { errorText, isAbort } from "./localCodeSupport";

type Translate = (key: string, values?: Record<string, unknown>) => string;

/** How long the "local search is ready" confirmation stays on screen before
 * the setup block resets itself to idle and disappears. A display choice, not
 * a measurement of anything: the point is only that a one-off confirmation
 * stops being permanent furniture on a page the person keeps using. */
export const INSTALL_DONE_NOTICE_MS = 8000;

// ---------------------------------------------------------------------------
// Embedder install — the one download this page can start
// ---------------------------------------------------------------------------

/**
 * The two indexer entries the cross-module contract names for the embedder: a
 * REAL presence check for the cached weights, and the single cancellable,
 * progress-reporting installer. indexer.ts exports both today.
 *
 * They are still reached OPTIONALLY rather than imported by name because
 * indexer.ts belongs to another agent: if either is renamed or reshaped under
 * us, this page degrades to the older pair instead of failing to build. So:
 * use the contract functions when they are there, and fall back to
 * isLocalEmbedderInstalled + ensureLocalEmbedderModel (same model, same
 * user-gesture rule) when they are not. The fallback is weaker in two named
 * ways, and the UI is told which it got rather than pretending:
 * isLocalEmbedderInstalled reads a localStorage marker (a hint that outlives
 * a cache eviction, not the Cache API walk the contract asks for), and
 * ensureLocalEmbedderModel takes no AbortSignal, so the Cancel affordance is
 * hidden instead of offered as a button that would do nothing.
 */
interface ContractEmbedderApi {
  isLocalEmbedderReady?(): Promise<boolean>;
  installLocalEmbedder?(
    onProgress: (p: { loaded: number; total: number; detail: string }) => void,
    signal?: AbortSignal
  ): Promise<boolean>;
}
const embedderContract = indexer as unknown as ContractEmbedderApi;

/** True only when the contract installer is present, because only it takes an
 * AbortSignal. Read once at module scope: it touches no browser global, so it
 * is safe under SSR and cannot change during a session. */
export const EMBEDDER_INSTALL_IS_CANCELLABLE = typeof embedderContract.installLocalEmbedder === "function";

export async function probeEmbedderReady(): Promise<boolean> {
  // edgeEnabled() first: with the flag off the indexer refuses to build at
  // all, so a leftover "installed" marker from a build where it was on must
  // not light up the Index button.
  if (!edgeEnabled()) return false;
  const contractProbe = embedderContract.isLocalEmbedderReady;
  if (contractProbe) return contractProbe();
  return indexer.isLocalEmbedderInstalled();
}

/**
 * Runs the install and answers only "did it finish". It deliberately does NOT
 * try to classify a false — see installEmbedder, which has the AbortController
 * and is the only thing on this page that knows whether a person clicked
 * Cancel.
 */
async function startEmbedderInstall(
  onProgress: (p: { loaded: number; total: number; detail: string }) => void,
  signal: AbortSignal
): Promise<boolean> {
  const contractInstall = embedderContract.installLocalEmbedder;
  if (contractInstall) return contractInstall(onProgress, signal);
  // ensureLocalEmbedderModel resolves void on success and rejects on failure,
  // so "finished without being cancelled" is the only true reading of a
  // resolve here.
  await indexer.ensureLocalEmbedderModel((p) => onProgress({ loaded: p.loaded, total: p.total, detail: p.status }));
  return true;
}

/**
 * The "Enable local search" install: its progress state, the one cancellable
 * run, and the short-lived "ready" confirmation. `setEmbedderReady` is the
 * panel's readiness flag, flipped on a finished install.
 */
export function useEmbedderInstall(setEmbedderReady: (ready: boolean) => void, t: Translate) {
  const [install, setInstall] = useState<EmbedderInstallState>({ kind: "idle" });
  const installAbort = useRef<AbortController | null>(null);
  // Holds the timer that retires the install confirmation. In a ref because it
  // has to be cancellable from a later install and from unmount, neither of
  // which can see a local variable from the run that armed it.
  const doneNotice = useRef<ReturnType<typeof setTimeout> | null>(null);

  // The confirmation timer dies with the page (the download itself does not:
  // see LocalCodePanel's unmount effect).
  useEffect(
    () => () => {
      if (doneNotice.current !== null) clearTimeout(doneNotice.current);
    },
    []
  );

  const installEmbedder = useCallback(async () => {
    if (install.kind === "running") return;
    // A retry supersedes the previous run's confirmation, timer and all.
    if (doneNotice.current !== null) {
      clearTimeout(doneNotice.current);
      doneNotice.current = null;
    }
    const controller = new AbortController();
    installAbort.current = controller;
    // The installer hands its reason for a false over the progress callback
    // (its last `detail` is documented as the explanation), so it is captured
    // in a local rather than read back out of React state, which this closure
    // cannot see.
    let lastDetail = "";
    setInstall({ kind: "running", loaded: 0, total: 0, detail: "" });
    try {
      const finished = await startEmbedderInstall((p) => {
        // A late progress callback from a superseded run must not repaint the
        // bar for the run that replaced it.
        if (installAbort.current !== controller) return;
        lastDetail = p.detail;
        setInstall({ kind: "running", loaded: p.loaded, total: p.total, detail: p.detail });
      }, controller.signal);
      if (installAbort.current !== controller) return;
      if (finished) {
        setEmbedderReady(true);
        setInstall({ kind: "done" });
        // Retire the confirmation instead of leaving it mounted for the rest
        // of the session: it is news about a moment, not the state of the
        // page, and nothing else was ever going to clear it.
        doneNotice.current = setTimeout(() => {
          doneNotice.current = null;
          setInstall((prev) => (prev.kind === "done" ? { kind: "idle" } : prev));
        }, INSTALL_DONE_NOTICE_MS);
        return;
      }
      // False is NOT a synonym for cancelled. installLocalEmbedder documents
      // false as "otherwise": a refusal (on-device AI off for this build, no
      // Worker, no room on the device) and a failed load resolve false exactly
      // as a cancel does. Telling someone they cancelled a download they
      // watched fail is a small lie that costs the whole feature its
      // credibility — so the only thing trusted for "the person stopped it" is
      // our own signal, which nothing but the Cancel button aborts. Everything
      // else is reported as a failure, with the installer's own last sentence
      // as the reason when it gave one.
      if (controller.signal.aborted) {
        setInstall({ kind: "cancelled" });
        return;
      }
      setInstall({ kind: "failed", reason: lastDetail || t("index.installFailedUnknown") });
    } catch (err) {
      if (installAbort.current !== controller) return;
      if (isAbort(err) || controller.signal.aborted) {
        setInstall({ kind: "cancelled" });
        return;
      }
      setInstall({ kind: "failed", reason: errorText(err, t("index.installFailedUnknown")) });
    } finally {
      if (installAbort.current === controller) installAbort.current = null;
    }
  }, [install.kind, t, setEmbedderReady]);

  const cancelInstall = useCallback(() => {
    installAbort.current?.abort();
  }, []);

  return { install, installEmbedder, cancelInstall };
}
