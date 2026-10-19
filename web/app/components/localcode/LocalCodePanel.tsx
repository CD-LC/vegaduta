"use client";

// Browser-local code assistant — the whole flow on one screen: open a folder,
// index it, ask, read the answer with citations, propose a change, apply it.
//
// Three properties are the entire point of this feature, so they are enforced
// here at the UI layer too, not just in lib/localcode:
//
//  1. No file content, chunk, embedding, question or answer leaves the
//     browser. Two kinds of transfer DO cross the network on this path, and
//     both are named here so an audit finds them documented rather than
//     hidden — an audit that turns up one undocumented fetch stops trusting
//     the other two promises as well:
//
//       - The edge model MANIFEST (GET /api/edge/manifest): a public,
//         unauthenticated catalogue of which local models exist, carrying no
//         user data in either direction. tier1.ts fetches and caches it while
//         resolving readiness (getTier1RunModel, on mount and on every ask),
//         and downloadManager touches it again to register the embedder with
//         the shared /edge download queue when the install below runs.
//       - The embedding model WEIGHTS, pulled from the Hugging Face hub by
//         @huggingface/transformers inside index/embedder.worker.ts — only
//         after the explicit "Enable local search" click, never on mount, on
//         an ask, or on an index build over already-cached weights.
//
//     This header used to call the manifest "the one network call anywhere on
//     this path", which stopped being true the moment anyone pressed that
//     button. This component still issues no request of its own, and
//     app/lib/localcode/** contained no fetch(), XMLHttpRequest or
//     sendBeacon outside its comments when this was written (grepped for
//     those three over that directory); the transfers above live in lib/edge
//     and in the worker's library. Neither carries the person's code, their
//     question, or the answer.
//  2. Nothing on this page spends a hosted quota, reads the signed-in
//     account, the plan or the tenant — which is also why there is no hosted
//     fallback when the on-device model is missing. NOTE the limit of that
//     claim: /code is a console route wrapped in AppShell, which requires a
//     session, so the PAGE is not reachable signed out even though nothing on
//     it needs a login. The copy says exactly that (privacy.signIn) rather
//     than the older, false "works signed out" — making it genuinely public
//     means a PUBLIC_ROUTES entry in lib/auth.ts plus standalone chrome,
//     because AuthProvider gives a public route no AuthContext at all and
//     AppShell's useAuth() would throw. Both files belong to other agents.
//  3. It never starts a model download by itself. Readiness is READ from the
//     existing edge stack (getTier1RunModel / tier0Availability /
//     localEditReadiness — pure queries that answer only for weights already
//     on the device). The ONE download this page can start is the embedding
//     model, and only from the explicit "Enable local search" button below.
//
// Structure and idiom follow components/edge/EdgeConsole.tsx: sections of
// rounded cards, t("localCode.*") for every visible string, usePageReady to
// hold the route cover until the capability checks resolve.

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "../../lib/i18n";
import { usePageReady } from "../RouteTransition";
import { detectCapabilities, edgeEnabled } from "../../lib/edge/capabilities";
import { tier0Availability } from "../../lib/edge/tier0";
import { getTier1RunModel } from "../../lib/edge/tier1";
import type {
  IndexStatus,
  LocalFileRef,
  LocalRepoHandle,
  LocalWalkSummary,
  RememberedLocalRepo,
  RetrievedChunk,
} from "../../lib/localcode/types";
import {
  ensureWritePermission,
  forgetLocalRepo,
  getLastWalkSummary,
  getRememberedLocalRepo,
  isLocalCodeSupported,
  listCodeFiles,
  pickLocalRepo,
  rememberLocalRepo,
  reopenLocalRepo,
} from "../../lib/localcode/fsAccess";
import * as indexer from "../../lib/localcode/index/indexer";
import { askLocalCode, type LocalAnswer } from "../../lib/localcode/ask";
import {
  applyLocalEdits,
  localEditReadiness,
  proposeLocalEdit,
  setLocalEditRepo,
  type LocalEditReadiness,
  type ProposedEdit,
} from "../../lib/localcode/edit";
import LocalCodeIndexCard from "./LocalCodeIndexCard";
import LocalCodeAnswer from "./LocalCodeAnswer";
import { type ApplyOutcome } from "./LocalCodeDiffView";
import { errorText, isAbort, type ModelGate } from "./localCodeSupport";
import { EMBEDDER_INSTALL_IS_CANCELLABLE, probeEmbedderReady, useEmbedderInstall } from "./useEmbedderInstall";
import { AskSection, ModelGateNotice, PrivacyBand, SuggestChangeSection } from "./LocalCodeSections";
import AgentTaskPanel from "../agentkit/AgentTaskPanel";

const IDLE_STATUS: IndexStatus = { state: "idle", files: 0, chunks: 0, indexedAt: null };


export default function LocalCodePanel() {
  const t = useTranslations("localCode");

  // null = "still checking", which is a genuinely different screen from
  // "false": neither an unsupported-browser notice nor a working picker
  // should flash before the probe answers.
  const [supported, setSupported] = useState<boolean | null>(null);
  const [modelGate, setModelGate] = useState<ModelGate>({ kind: "checking" });
  // Editing has STRICTER gates than asking (it also honours the hosted-only
  // routing preference), so the two are tracked separately — a single flag
  // would either grey out Ask when it would have worked, or offer Suggest a
  // change when proposeLocalEdit can only answer null. Its `detail` is the
  // library's own plain-English reason, which t() cannot produce because only
  // the library knows which gate closed.
  const [editReadiness, setEditReadiness] = useState<LocalEditReadiness | null>(null);
  // Indexing needs a DIFFERENT model from answering (an embedder, not a chat
  // model), so a device can be perfectly able to answer and unable to index.
  const [embedderReady, setEmbedderReady] = useState<boolean | null>(null);

  const [repo, setRepo] = useState<LocalRepoHandle | null>(null);
  const [files, setFiles] = useState<LocalFileRef[] | null>(null);
  const [walk, setWalk] = useState<LocalWalkSummary | null>(null);
  const [remembered, setRemembered] = useState<RememberedLocalRepo | null>(null);
  const [picking, setPicking] = useState(false);
  const [pickError, setPickError] = useState<string | null>(null);
  const [status, setStatus] = useState<IndexStatus>(IDLE_STATUS);

  const [question, setQuestion] = useState("");
  const [asking, setAsking] = useState(false);
  const [streamed, setStreamed] = useState("");
  const [answer, setAnswer] = useState<LocalAnswer | null>(null);
  // Set when the person pressed Stop: askLocalCode resolves (never throws) on
  // abort, and its detail is the honest "stopped before the model finished".
  // Held separately from `answer` so the partial text keeps rendering instead
  // of the whole card unmounting — see LocalCodeAnswer.
  const [stoppedDetail, setStoppedDetail] = useState<string | null>(null);
  const [askError, setAskError] = useState<string | null>(null);
  const [selectedCitationId, setSelectedCitationId] = useState<string | null>(null);

  const [instruction, setInstruction] = useState("");
  const [editPath, setEditPath] = useState("");
  const [editPathTouched, setEditPathTouched] = useState(false);
  const [proposing, setProposing] = useState(false);
  const [edit, setEdit] = useState<ProposedEdit | null>(null);
  const [editError, setEditError] = useState<string | null>(null);
  const [applying, setApplying] = useState(false);
  const [applyOutcome, setApplyOutcome] = useState<ApplyOutcome>({ kind: "none" });

  const indexAbort = useRef<AbortController | null>(null);
  const askAbort = useRef<AbortController | null>(null);
  const proposeAbort = useRef<AbortController | null>(null);
  const { install, installEmbedder, cancelInstall } = useEmbedderInstall(setEmbedderReady, t);

  // Capability probes. All of these are read-only queries that start nothing:
  // getTier1RunModel answers only for weights already cached on this device,
  // and tier0 "downloadable" is deliberately NOT counted as ready — Chrome
  // would have to pull the model first, and kicking that off unasked is
  // exactly what this page promises never to do. Guarded by a `cancelled`
  // flag rather than an AbortController because none of them take one.
  useEffect(() => {
    let cancelled = false;
    setSupported(isLocalCodeSupported());
    setStatus(indexer.getLocalIndexStatus());
    probeEmbedderReady()
      .then((ready) => {
        if (!cancelled) setEmbedderReady(ready);
      })
      .catch(() => {
        if (!cancelled) setEmbedderReady(false);
      });
    // The index outlives the folder handle (chunks live in IndexedDB), so a
    // returning visitor can ask questions before re-picking anything — but
    // only if we look for it.
    indexer
      .restoreLocalIndexStatus()
      .then((restored) => {
        if (!cancelled) setStatus(restored);
      })
      .catch(() => {});
    // Mirrors askLocalCode's resolveReadyTier IN ORDER, edgeEnabled() first.
    // Dropping that gate is what made Ask live on a Chrome build with the
    // built-in Prompt API and NEXT_PUBLIC_EDGE_AGENTS unset: tier0 answers
    // "available", askLocalCode refuses anyway, and every question came back
    // empty with the "no model" banner hidden.
    if (!edgeEnabled()) {
      setModelGate({ kind: "edge-off" });
    } else {
      detectCapabilities()
        .then(async (caps) => {
          const tier1 = await getTier1RunModel(caps);
          if (tier1) return true;
          return (await tier0Availability()) === "available";
        })
        .then((ready) => {
          if (!cancelled) setModelGate(ready ? { kind: "ready" } : { kind: "no-model" });
        })
        .catch(() => {
          if (!cancelled) setModelGate({ kind: "no-model" });
        });
    }
    // Read-only: queryPermission needs no user gesture, so this is safe on
    // mount. Reopening is still a button — permission does not survive a
    // reload and must be requested from inside a click.
    getRememberedLocalRepo()
      .then((found) => {
        if (!cancelled) setRemembered(found);
      })
      .catch(() => {});
    localEditReadiness()
      .then((readiness) => {
        if (!cancelled) setEditReadiness(readiness);
      })
      .catch(() => {
        if (!cancelled) setEditReadiness(null);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Any in-flight work dies with the page — an indexer walking a large repo
  // after the person navigated away is pure wasted CPU on their machine. The
  // embedder download is deliberately NOT aborted here: bytes already pulled
  // are cached by the browser, and killing it on a stray navigation would
  // throw away a download the person explicitly asked for.
  useEffect(
    () => () => {
      indexAbort.current?.abort();
      askAbort.current?.abort();
      proposeAbort.current?.abort();
    },
    []
  );

  usePageReady(supported !== null && modelGate.kind !== "checking");


  // ---- Folder + index ---------------------------------------------------

  /** Everything that has to happen once a handle exists, whichever way it
   * arrived (fresh pick or reopen). The walk summary is captured immediately
   * after listCodeFiles because it is module-level state describing the LAST
   * walk — a second walk would overwrite it. */
  const adoptRepo = useCallback(async (handle: LocalRepoHandle) => {
    setRepo(handle);
    // edit.ts keeps a module-level handle for callers that don't pass one.
    // This console always passes `repo` per call, but leaving the module
    // binding null means any sibling that relies on it silently edits
    // nothing — keeping the two in step costs one line.
    setLocalEditRepo(handle);
    setFiles(null);
    setWalk(null);
    setStatus(IDLE_STATUS);
    setAnswer(null);
    setStreamed("");
    setStoppedDetail(null);
    setAskError(null);
    setEdit(null);
    setEditError(null);
    setApplyOutcome({ kind: "none" });
    const found = await listCodeFiles(handle);
    setFiles(found);
    setWalk(getLastWalkSummary());
    // Best-effort: losing this costs one extra click next visit, never
    // correctness — so it never fails the pick.
    void rememberLocalRepo(handle).catch(() => {});
  }, []);

  const pickRepo = useCallback(async () => {
    setPicking(true);
    setPickError(null);
    try {
      const handle = await pickLocalRepo();
      // null is the browser's "user dismissed the picker" — not an error, and
      // it must not leave a stale error line on screen.
      if (!handle) return;
      await adoptRepo(handle);
    } catch (err) {
      setPickError(errorText(err, t("repo.pickFailed")));
    } finally {
      setPicking(false);
    }
  }, [adoptRepo, t]);

  const reopenRepo = useCallback(async () => {
    setPicking(true);
    setPickError(null);
    try {
      const handle = await reopenLocalRepo();
      if (!handle) {
        // reopenLocalRepo forgets a stale handle on its way out, so drop the
        // offer too rather than leaving a button that can only fail again.
        setRemembered(null);
        setPickError(t("repo.reopenFailed"));
        return;
      }
      await adoptRepo(handle);
    } catch (err) {
      setPickError(errorText(err, t("repo.reopenFailed")));
    } finally {
      setPicking(false);
    }
  }, [adoptRepo, t]);

  const runIndex = useCallback(async () => {
    if (!repo) return;
    indexAbort.current?.abort();
    const controller = new AbortController();
    indexAbort.current = controller;
    setStatus({ ...IDLE_STATUS, state: "indexing" });
    try {
      // buildLocalIndex resolves rather than throwing on abort, and its final
      // status is the honest one ("cancelled after N of M files — search
      // covers only what was indexed", or an idle status carrying "Indexing
      // cancelled." when nothing landed). Dropping it on the aborted path
      // would strand the card on "Indexing…" forever, so the only thing
      // checked here is that a NEWER build hasn't taken over.
      const final = await indexer.buildLocalIndex(repo, setStatus, controller.signal);
      if (indexAbort.current === controller) setStatus(final);
    } catch (err) {
      if (isAbort(err)) {
        setStatus(indexer.getLocalIndexStatus());
        return;
      }
      setStatus({ ...IDLE_STATUS, state: "error", detail: errorText(err, t("index.failed")) });
    } finally {
      if (indexAbort.current === controller) indexAbort.current = null;
    }
  }, [repo, t]);

  const cancelIndex = useCallback(() => {
    // Abort and nothing else: the indexer checks the signal between files and
    // between embedding batches, then resolves with the real counts. Writing a
    // state here would race that and could claim a stop that hasn't happened
    // (a batch already inside the worker cannot be interrupted).
    indexAbort.current?.abort();
  }, []);

  const forgetRepo = useCallback(async () => {
    indexAbort.current?.abort();
    askAbort.current?.abort();
    proposeAbort.current?.abort();
    setRepo(null);
    setLocalEditRepo(null);
    setFiles(null);
    setWalk(null);
    setRemembered(null);
    setStatus(IDLE_STATUS);
    setAnswer(null);
    setStreamed("");
    setStoppedDetail(null);
    setAskError(null);
    setEdit(null);
    setEditError(null);
    setApplyOutcome({ kind: "none" });
    setEditPath("");
    setEditPathTouched(false);
    // Forget BOTH halves of what we kept: the handle in IndexedDB and the
    // index built from it. "Forget folder" that leaves the chunks behind is
    // not forgetting.
    await Promise.all([indexer.clearLocalIndex(), forgetLocalRepo()]);
  }, []);

  // ---- Ask --------------------------------------------------------------

  const ask = useCallback(async () => {
    const q = question.trim();
    if (!q || asking) return;
    askAbort.current?.abort();
    const controller = new AbortController();
    askAbort.current = controller;
    setAsking(true);
    setStreamed("");
    setAnswer(null);
    setStoppedDetail(null);
    setAskError(null);
    setSelectedCitationId(null);
    try {
      const result = await askLocalCode(q, {
        // Deltas, not cumulative text — the convention every streaming surface
        // in the edge stack uses (see Tier0Session.onToken).
        onToken: (token) => setStreamed((prev) => prev + token),
        signal: controller.signal,
      });
      // Ref equality, not just signal.aborted: a SECOND ask aborts the first,
      // and the first's late resolve must write nothing at all — neither a
      // stopped state nor `asking = false` over a question already running.
      if (askAbort.current !== controller) return;
      // THE RESULT IS EXAMINED BEFORE THE ABORT FLAG. Stop and a finished
      // generation can land in the same turn of the loop: askLocalCode
      // resolves with a complete answer, then the click aborts a controller
      // nothing is waiting on any more. Checking the flag first threw that
      // finished, citable answer away and showed "stopped" over it — the
      // person pressed Stop a moment too late and lost work that already
      // existed. ranLocally === true is askLocalCode's own word that this is a
      // complete on-device answer with the exact excerpts behind it, so it is
      // kept whatever the signal now says.
      if (result.ranLocally === true) {
        setAnswer(result);
        return;
      }
      if (controller.signal.aborted) {
        // askLocalCode never throws; on abort it resolves with an empty
        // answer and a "stopped before the model finished" detail. Keeping
        // that detail (and the tokens already in `streamed`) is the whole
        // difference between Stop and Discard.
        setStoppedDetail(result.detail || t("answer.stoppedEmpty"));
        return;
      }
      setAnswer(result);
    } catch (err) {
      if (askAbort.current !== controller) return;
      if (!isAbort(err)) setAskError(errorText(err, t("ask.failed")));
    } finally {
      if (askAbort.current === controller) {
        askAbort.current = null;
        setAsking(false);
      }
    }
  }, [question, asking, t]);

  const selectCitation = useCallback(
    (chunk: RetrievedChunk) => {
      setSelectedCitationId((prev) => (prev === chunk.id ? null : chunk.id));
      // Convenience only, and only while the person hasn't typed a path of
      // their own: the file you just read is almost always the file you want
      // to change next.
      if (!editPathTouched) setEditPath(chunk.path);
    },
    [editPathTouched]
  );

  // ---- Propose / apply --------------------------------------------------

  const propose = useCallback(async () => {
    const text = instruction.trim();
    const path = editPath.trim();
    if (!repo || !text || !path || proposing) return;
    proposeAbort.current?.abort();
    const controller = new AbortController();
    proposeAbort.current = controller;
    setProposing(true);
    setEdit(null);
    setEditError(null);
    setApplyOutcome({ kind: "none" });
    try {
      // The handle goes per call as well as through setLocalEditRepo: this
      // component already holds the repo it is showing, and an explicit
      // argument can never point at a folder the person has since
      // disconnected.
      const proposed = await proposeLocalEdit(text, path, { signal: controller.signal, repo });
      if (proposeAbort.current !== controller) return;
      if (controller.signal.aborted) return;
      if (!proposed) {
        setEditError(t("edit.noChange"));
        return;
      }
      // An empty diff is the module's "the file already does this" answer,
      // deliberately distinct from a failure — showing an empty diff viewer
      // would read as a bug.
      if (!proposed.diff) {
        setEditError(t("edit.noChangeNeeded"));
        return;
      }
      setEdit(proposed);
    } catch (err) {
      if (proposeAbort.current !== controller) return;
      if (!isAbort(err)) setEditError(errorText(err, t("edit.proposeFailed")));
    } finally {
      if (proposeAbort.current === controller) {
        proposeAbort.current = null;
        setProposing(false);
      }
    }
  }, [repo, instruction, editPath, proposing, t]);

  const applyEdit = useCallback(async () => {
    if (!repo || !edit || applying) return;
    setApplying(true);
    setApplyOutcome({ kind: "none" });
    try {
      // Read access to a picked folder does not include write access — this
      // is the prompt where the person says yes to us touching their file.
      // applyLocalEdits re-checks it too; asking here first is what keeps the
      // permission prompt inside this click's user activation (a request made
      // several awaits deep can be refused by the browser outright) and lets
      // a refusal render as its own calm state instead of a failed write.
      const granted = await ensureWritePermission(repo);
      if (!granted) {
        setApplyOutcome({ kind: "denied" });
        return;
      }
      const result = await applyLocalEdits(repo, [edit]);
      const failure = result.failed.find((f) => f.path === edit.path);
      if (failure) {
        setApplyOutcome({ kind: "failed", reason: failure.reason });
        return;
      }
      if (result.applied.includes(edit.path)) {
        setApplyOutcome({ kind: "applied", path: edit.path });
        return;
      }
      setApplyOutcome({ kind: "failed", reason: t("edit.notWritten") });
    } catch (err) {
      setApplyOutcome({ kind: "failed", reason: errorText(err, t("edit.applyFailed")) });
    } finally {
      setApplying(false);
    }
  }, [repo, edit, applying, t]);

  // ---- Unsupported browser ----------------------------------------------

  if (supported === false) {
    return (
      <div className="space-y-4">
        <section className="rounded-xl border border-border bg-card p-5">
          <h2 className="text-base font-semibold text-foreground">{t("unsupported.title")}</h2>
          <p className="mt-1 max-w-2xl text-sm leading-relaxed text-muted-foreground">{t("unsupported.body")}</p>
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">{t("unsupported.hint")}</p>
        </section>
        <PrivacyBand />
      </div>
    );
  }

  const indexReady = status.state === "ready" && status.chunks > 0;
  const modelIsReady = modelGate.kind === "ready";
  const canAsk = indexReady && modelIsReady;
  // Editing needs everything asking needs, plus the library's own stricter
  // gate — and a repo handle, since the proposal reads the file through it.
  const canEdit = indexReady && repo !== null && editReadiness?.ready === true;

  return (
    <div className="space-y-6">
      <ModelGateNotice modelGate={modelGate} />

      <LocalCodeIndexCard
        repo={repo}
        fileCount={files ? files.length : null}
        walk={walk}
        remembered={repo ? null : remembered}
        embedderReady={embedderReady}
        edgeOff={modelGate.kind === "edge-off"}
        install={install}
        installCancellable={EMBEDDER_INSTALL_IS_CANCELLABLE}
        status={status}
        picking={picking}
        pickError={pickError}
        onPick={() => void pickRepo()}
        onReopen={() => void reopenRepo()}
        onIndex={() => void runIndex()}
        onCancel={cancelIndex}
        onForget={() => void forgetRepo()}
        onInstallEmbedder={() => void installEmbedder()}
        onCancelInstall={cancelInstall}
      />

      {/* ---------------- Ask ---------------- */}
      <AskSection
        question={question}
        setQuestion={setQuestion}
        canAsk={canAsk}
        asking={asking}
        indexReady={indexReady}
        modelGate={modelGate}
        onAsk={() => void ask()}
        onStop={() => askAbort.current?.abort()}
      />

      <LocalCodeAnswer
        streamed={streamed}
        answer={answer}
        running={asking}
        stoppedDetail={stoppedDetail}
        error={askError}
        selectedCitationId={selectedCitationId}
        onSelectCitation={selectCitation}
      />

      {/* ---------------- Suggest a change ---------------- */}
      <SuggestChangeSection
        files={files}
        editPath={editPath}
        onEditPath={(path) => {
          setEditPath(path);
          setEditPathTouched(true);
        }}
        instruction={instruction}
        setInstruction={setInstruction}
        canEdit={canEdit}
        indexReady={indexReady}
        editReadiness={editReadiness}
        proposing={proposing}
        onPropose={() => void propose()}
        editError={editError}
        edit={edit}
        applyOutcome={applyOutcome}
        applying={applying}
        onApply={() => void applyEdit()}
        onDiscard={() => {
          setEdit(null);
          setApplyOutcome({ kind: "none" });
        }}
      />

      {/* ---------------- Multi-step agent (2026-09-19) ----------------
          The single-file "suggest a change" above is one model call. This is
          the plan -> act -> observe loop from lib/agentkit over the SAME folder
          handle: it searches, reads and proposes edits across files, inside a
          context-window budget sized to the on-device model, and every write
          waits for approval on a diff. It needs the folder but NOT the
          embedding index (its search is a plain text scan), and it resolves
          its own model readiness — so it is not gated on canAsk/canEdit. It
          cannot run terminal commands, and says so on the card itself.
          Its copy lives in lib/agentkit/copy.ts as plain strings rather than
          t() keys; see that file for why. */}
      <AgentTaskPanel repo={repo} />

      <PrivacyBand />
    </div>
  );
}
