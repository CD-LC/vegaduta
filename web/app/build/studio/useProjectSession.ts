import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from "react";
import { useDictation } from "./dictation";
import { canAdminister, canEdit } from "./collab";
import { CREATE_SYSTEM, EDIT_SYSTEM, createPrompt, editPrompt, runModel, type EngineKind } from "./engine";
import { PLAN_SYSTEM, findDuplicateDeclarations, integrationPrompt, needsPlan, parsePlan, planPrompt, repairPrompt, stepPrompt } from "./buildPlan";
import { applyEditBlocks, dedupeSkeletonHelpers, extractHtmlDocument, looksLikeValidApp, parseEditBlocks } from "./patch";
import { DEFAULT_ENV, HARNESS_LINES, findInSource, serializeDoc, withHarness, type PreviewEnv, type SelectedElement } from "./runtime";
import { MAX_ATTACHMENTS, readAttachment, resolveAssets, withAttachments, type Attachment } from "./attachments";
import { addVersion, currentHtml, type StudioProject, type VersionSource } from "./store";
import { CONNECT_START, stripConnections, writeConnections } from "./connect";
import { describeTarget, fixRequest, fromModelView, isWholePageRequest, locateRuntimeError, toModelView, type RuntimeErrorReport } from "./scope";
import { BLANK_APP } from "./templates";
import { readTheme, writeTheme } from "./theme";
import { type EnginePref, type Device, type SidePanel, type LogEntry, type EngineState, FORMAT_REMINDER, plannedEngine } from "./sessionTypes";
import { pendingAuthResolvers } from "./appBridge";
import { useElapsed } from "./useElapsed";
import { initialUiState, uiReducer, type Updater } from "./sessionReducer";
import { usePreviewBridge } from "./usePreviewBridge";
import { useProjectPublishing } from "./useProjectPublishing";
import { useStageScale } from "./useStageScale";

export interface WorkspaceProps {
  project: StudioProject;
  engine: EngineState;
  enginePref: EnginePref;
  setEnginePref: (p: EnginePref) => void;
  update: (fn: (p: StudioProject) => StudioProject) => void;
  onBack: () => void;
  pendingCreate: { id: string; description: string; fromTemplate: boolean } | null;
  clearPendingCreate: () => void;
  prefill?: string | null;
  clearPrefill?: () => void;
  onOpenApp?: (projectId: string) => void;
}

/** All state and handlers of one open studio project (the workspace "session"). */
export function useProjectSession({
  project,
  engine,
  enginePref,
  setEnginePref,
  update: updateRaw,
  onBack,
  pendingCreate,
  clearPendingCreate,
  prefill,
  clearPrefill,
  onOpenApp,
}: WorkspaceProps) {
  const html = currentHtml(project);
  // The freshest committed project. Async flows (attach, publish) resume after
  // awaits, when the render-time `project` above is already stale.
  const projectRef = useRef(project);
  projectRef.current = project;
  // Shared projects: a viewer cannot change anything, an editor adds versions
  // but cannot rename / publish / change settings (core enforces the same).
  const editable = canEdit(project.role);
  const isOwner = canAdminister(project.role);
  const update = useCallback(
    (fn: (p: StudioProject) => StudioProject) => {
      if (editable) updateRaw(fn);
    },
    [editable, updateRaw]
  );
  // Pure UI state (device, side panel, tap-select, scope question, compare view) lives in a reducer.
  const [ui, dispatch] = useReducer(uiReducer, initialUiState);
  const { device, panel, inspect, selected, scopeAsk, diffFrom } = ui;
  const setDevice = useCallback((v: Updater<Device>) => dispatch({ type: "set", key: "device", value: v }), []);
  const setPanel = useCallback((v: Updater<SidePanel>) => dispatch({ type: "set", key: "panel", value: v }), []);
  const setInspect = useCallback((v: Updater<boolean>) => dispatch({ type: "set", key: "inspect", value: v }), []);
  const setSelected = useCallback((v: Updater<SelectedElement | null>) => dispatch({ type: "set", key: "selected", value: v }), []);
  const setScopeAsk = useCallback((v: Updater<string | null>) => dispatch({ type: "set", key: "scopeAsk", value: v }), []);
  const setDiffFrom = useCallback((v: Updater<number | null>) => dispatch({ type: "set", key: "diffFrom", value: v }), []);
  // A whole-page request waiting on the person's "yes" (see send()).
  const wholePageOk = useRef(false);
  const [runtimeError, setRuntimeError] = useState<RuntimeErrorReport | null>(null);
  /** Mirror of runtimeError for async build loops that must read the latest value. */
  const runtimeErrorRef = useRef<RuntimeErrorReport | null>(null);
  useEffect(() => {
    runtimeErrorRef.current = runtimeError;
  }, [runtimeError]);
  // The error message the last "Fix it with AI" targeted - if the same one
  // comes back, the next fix is told the earlier attempt didn't work.
  const lastFixRef = useRef<string | null>(null);
  const [busy, setBusy] = useState<{ label: string; started: number; engineLabel: string } | null>(null);
  const [stream, setStream] = useState("");
  const [log, setLog] = useState<LogEntry[]>([]);
  const [request, setRequest] = useState(prefill ?? "");
  useEffect(() => {
    if (prefill) clearPrefill?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const dictation = useDictation((text) => setRequest((r) => (r && !/\s$/.test(r) ? r + " " : r) + text));
  // Preview-only toggles (dark mode, offline, slow network) - never saved into the app.
  const [env, setEnv] = useState<PreviewEnv>(DEFAULT_ENV);
  const envRef = useRef(env);
  envRef.current = env;
  const [attached, setAttached] = useState<Attachment[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const pickFiles = async (files: FileList | null) => {
    if (!files) return;
    for (const f of Array.from(files)) {
      if (attached.length >= MAX_ATTACHMENTS) {
        pushLog({ kind: "error", text: `You can attach up to ${MAX_ATTACHMENTS} files per change.` });
        break;
      }
      try {
        const a = await readAttachment(f);
        setAttached((l) => (l.length < MAX_ATTACHMENTS ? [...l, a] : l));
      } catch (e) {
        pushLog({ kind: "error", text: e instanceof Error ? e.message : "Couldn't attach that file." });
      }
    }
    if (fileInputRef.current) fileInputRef.current.value = "";
  };
  const [nonce, setNonce] = useState(0);
  const [pendingAuth, setPendingAuth] = useState<{ slug: string } | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  /** HTML of the last edit this session committed - lets a multi-step build chain each step on the previous result (the `project` a callback closed over is stale mid-loop). */
  const lastHtmlRef = useRef<string | null>(null);
  const frameRef = useRef<HTMLIFrameElement | null>(null);
  const logId = useRef(0);

  // The preview doc: rebuilt only when the app's HTML changes (or on reload),
  // NOT when its saved data changes - otherwise every tap inside the app
  // would reload it. Seeded with the data as it was at that moment.
  const appDataRef = useRef(project.appData);
  appDataRef.current = project.appData;
  // The slug of THIS project's published app - the only one the preview may
  // act for. The app's own vdCall message is untrusted (security review
  // SEC-011: a d.slug from the frame could borrow another app's sign-in).
  const publishSlugRef = useRef(project.publish?.slug || "");
  publishSlugRef.current = project.publish?.slug || "";
  const srcDoc = useMemo(() => withHarness(html, appDataRef.current || {}), [html, nonce]); // eslint-disable-line react-hooks/exhaustive-deps

  const pushLog = (e: Omit<LogEntry, "id">) => setLog((l) => [...l, { ...e, id: ++logId.current }]);

  usePreviewBridge({ frameRef, envRef, publishSlugRef, updateRaw, setRuntimeError, setSelected, setPanel, setPendingAuth });

  // Re-send inspect mode after every reload of the frame.
  const syncInspect = useCallback(() => {
    frameRef.current?.contentWindow?.postMessage({ vdStudio: "inspect", on: inspect }, "*");
  }, [inspect]);
  useEffect(() => {
    syncInspect();
  }, [syncInspect]);
  const syncEnv = useCallback(() => {
    frameRef.current?.contentWindow?.postMessage({ vdStudio: "env", ...envRef.current }, "*");
  }, []);
  useEffect(() => {
    syncEnv();
  }, [env, syncEnv]);

  useEffect(() => {
    setRuntimeError(null);
  }, [html]);

  const commit = useCallback(
    (next: string, note: string, source: VersionSource, opts: { resetData?: boolean } = {}) => {
      if (!editable) return;
      update((p) => {
        // Whole-document rewrites (model or hand-edited code) drop the
        // injected connections block - re-splice so attached services
        // survive every edit, or project.connections would lie.
        const withCxns =
          (p.connections?.length || p.publish) && !next.includes(CONNECT_START)
            ? writeConnections(next, p.connections || [], { publish: p.publish, auth: p.auth, ai: p.ai })
            : next;
        const added = addVersion(p, dedupeSkeletonHelpers(withCxns), note, source);
        // An adapted starter must show its NEW sample data: the preview's saved
        // localStorage snapshot from the starter run would otherwise win over the
        // rewritten load(key, defaults) and the old sample items would reappear.
        return opts.resetData ? { ...added, appData: {} } : added;
      });
    },
    [update, editable]
  );

  const runEdit = useCallback(
    async (req: string, opts: { target?: string | null; prefer?: EnginePref; baseHtml?: string; failNote?: string; assets?: Attachment[]; resetData?: boolean; quiet?: boolean } = {}) => {
      if (!editable) return false;
      const base = opts.baseHtml ?? currentHtml(project);
      // The model edits a view with the studio-owned vd:connect block (its
      // runtime + every shareId) folded into a one-line placeholder; the
      // block is restored after the edit is applied.
      const view = toModelView(base, project.connections || []);
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      const started = Date.now();
      const prefer = opts.prefer || enginePref;
      const plan = plannedEngine(engine, prefer);
      setBusy({ label: "Changing your app…", started, engineLabel: plan.label });
      const user = editPrompt(view.html, req, opts.target);
      const ask = (p: EnginePref, extra?: string) => {
        setStream("");
        return runModel({
          system: EDIT_SYSTEM,
          user: extra ? `${user}\n\n${extra}` : user,
          onToken: (d) => setStream((s) => s + d),
          signal: ctrl.signal,
          prefer: p,
          replyTokens: 1200,
        });
      };
      // The change landing matters more than which leg wrote it: try the
      // chosen engine, retry it once with a sharper format reminder (small
      // on-device models regularly answer with prose instead of edit
      // blocks), then - unless the person pinned "On my device only" - the
      // cloud leg, before any error is shown.
      const attempts: { prefer: EnginePref; extra?: string }[] = [
        { prefer },
        { prefer, extra: FORMAT_REMINDER },
        ...(prefer === "device" ? [] : [{ prefer: "cloud" as EnginePref }]),
      ];
      let lastError = "The AI couldn't make that change.";
      let lastRes: Awaited<ReturnType<typeof runModel>> | null = null;
      let applied: { html: string; note: string; engine: EngineKind | null; model: string | null } | null = null;
      // Every cloud attempt resends the whole app, so one edit costs at
      // most two cloud calls (the ask + one format-reminder retry), and none
      // more once the endpoint itself errored - the same request would only
      // fail again (no provider, quota, 5xx).
      let cloudCalls = 0;
      let cloudDown = false;
      // A reply whose edit blocks only PARTLY matched the app is kept as a
      // fallback but is not accepted yet: the unmatched block is often the
      // headline change (e.g. the rename), and "2 of 3 changes applied" would
      // otherwise read as done. Retry once for a clean apply first.
      let partial: { html: string; note: string; engine: EngineKind | null; model: string | null } | null = null;
      let retryHint = "";
      for (const a of attempts) {
        if (ctrl.signal.aborted) break;
        if (a.prefer === "cloud" && (cloudDown || cloudCalls >= 2)) continue;
        // "auto" with no device model ready IS a cloud call - same cap.
        if (a.prefer === "auto" && !engine.device && (cloudDown || cloudCalls >= 2)) continue;
        const res = await ask(a.prefer, [a.extra, retryHint].filter(Boolean).join(" ") || undefined);
        lastRes = res;
        if (res.triedCloud) cloudCalls++;
        if (ctrl.signal.aborted) break;
        if (!res.ok) {
          if (res.error && res.error !== "Stopped.") lastError = res.error;
          if (res.triedCloud) cloudDown = true;
          continue;
        }
        const blocks = parseEditBlocks(res.text);
        let next: string | null = null;
        let note = "";
        if (blocks.length > 0) {
          const r = applyEditBlocks(view.html, blocks);
          if (r.applied > 0) {
            next = r.html;
            note = r.failed.length ? `${r.applied} of ${blocks.length} changes applied` : `${r.applied} change${r.applied > 1 ? "s" : ""} applied`;
            if (r.failed.length && looksLikeValidApp(next)) {
              const keep = !partial || r.applied > Number(partial.note.split(" ")[0]);
              if (keep) partial = { html: resolveAssets(fromModelView(next, view), opts.assets || []), note, engine: res.engine, model: res.modelName };
              retryHint = `Your previous reply had ${r.failed.length} edit block(s) whose SEARCH text did not match the app. Send ALL the changes again and copy every SEARCH line character-for-character from the APP above.`;
              next = null;
              continue;
            }
          }
        }
        // A reply can carry unusable blocks AND a full rewritten document -
        // try the doc too before counting this attempt as a miss.
        if (!next) {
          next = extractHtmlDocument(res.text);
          note = "App rewritten";
        }
        if (next && looksLikeValidApp(next)) {
          applied = { html: resolveAssets(fromModelView(next, view), opts.assets || []), note, engine: res.engine, model: res.modelName };
          break;
        }
        lastError =
          prefer === "device"
            ? "The on-device model's answer didn't fit the app. This built-in model may be too small for code edits - switch the engine to Auto so the cloud can finish it."
            : "The AI's answer didn't fit the app, so nothing changed. Try saying it differently, or tap the exact element first.";
      }
      setBusy(null);
      abortRef.current = null;
      if (ctrl.signal.aborted) return false;
      if (!applied && partial) applied = partial;
      if (!applied) {
        if (opts.quiet) return false;
        pushLog({
          kind: "error",
          text: opts.failNote ? `${opts.failNote} ${lastError}` : lastError,
          engine: lastRes?.engine,
          model: lastRes?.modelName,
          // Pinned "device only" and the device couldn't do it - the pin is
          // still honoured (nothing went to the cloud), but the error is not
          // a dead end: offer an explicit one-click cloud retry.
          cloudRetry: prefer === "device" ? { req, target: opts.target } : undefined,
        });
        return false;
      }
      lastHtmlRef.current = applied.html;
      commit(applied.html, req.length > 80 ? req.slice(0, 78) + "…" : req, applied.engine === "device" ? "device" : "cloud", { resetData: opts.resetData });
      pushLog({ kind: "result", text: applied.note, engine: applied.engine, model: applied.model, ms: Date.now() - started });
      return true;
    },
    [project, enginePref, commit, engine, editable]
  );

  const runCreate = useCallback(
    async (description: string) => {
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      const started = Date.now();
      setBusy({ label: "Writing your app…", started, engineLabel: plannedEngine(engine, enginePref === "cloud" ? "cloud" : "device").label });
      // Whole-app generation needs a big reply - only the on-device model
      // can give one (the cloud draft endpoint replies ~1k tokens), so the
      // cloud leg below builds inside the skeleton with edit blocks instead.
      const tryDevice = (extra?: string) => {
        setStream("");
        return runModel({
          system: CREATE_SYSTEM,
          user: createPrompt(BLANK_APP, description) + (extra ? `\n\n${extra}` : ""),
          onToken: (d) => setStream((s) => s + d),
          signal: ctrl.signal,
          prefer: "device",
          replyTokens: 2600,
        });
      };
      let res = enginePref === "cloud" ? null : await tryDevice();
      let doc = res?.ok ? extractHtmlDocument(res.text) : null;
      // One retry with a sharper contract before giving up on the device
      // leg: a first attempt that answered with prose - or that hit the
      // engine's cold-start deadline while the load kept running in the
      // background - regularly succeeds on the second ask, now warm.
      if ((!doc || !looksLikeValidApp(doc)) && enginePref !== "cloud" && !ctrl.signal.aborted) {
        res = await tryDevice(
          "Your last reply was not a usable HTML document. Reply with ONLY the complete app HTML, starting with <!doctype html> and ending with </html>."
        );
        doc = res?.ok ? extractHtmlDocument(res.text) : null;
      }
      setBusy(null);
      abortRef.current = null;
      if (doc && looksLikeValidApp(doc)) {
        const themed = readTheme(doc) ? doc : writeTheme(doc, {});
        commit(themed, description, "device");
        pushLog({ kind: "result", text: "App written", engine: "device", model: res?.modelName, ms: Date.now() - started });
        return;
      }
      if (ctrl.signal.aborted) return;
      if (enginePref === "device") {
        pushLog({
          kind: "error",
          text: res?.ok
            ? "The on-device model's reply wasn't a usable app. Try again, or switch the engine to Auto to use the cloud."
            : res?.error || "The on-device model couldn't write this app. Switch the engine to Auto to use the cloud.",
          cloudRetry: { req: `Build this app inside the skeleton (replace the placeholder header, content and script): ${description}` },
        });
        return;
      }
      const failNote = "Your idea was NOT built: the app on screen is only an empty skeleton. Send your request again in the box below to retry.";
      // A cloud edit is ~1k tokens, far too small for a multi-screen app in one
      // go: plan it in screen-sized steps and build them one after the other.
      if (needsPlan(description)) {
        setBusy({ label: "Planning your app…", started: Date.now(), engineLabel: plannedEngine(engine, "cloud").label });
        setStream("");
        const planRes = await runModel({ system: PLAN_SYSTEM, user: planPrompt(description), signal: ctrl.signal, prefer: "cloud", replyTokens: 600 });
        const steps = planRes.ok ? parsePlan(planRes.text) : [];
        setBusy(null);
        abortRef.current = null;
        if (ctrl.signal.aborted) return;
        if (steps.length >= 2) {
          pushLog({ kind: "result", text: `Plan: ${steps.length} steps - ${steps.map((t, i) => `${i + 1}) ${t}`).join("  ")}`, engine: "cloud", model: planRes.modelName });
          lastHtmlRef.current = BLANK_APP;
          for (let i = 0; i < steps.length; i++) {
            // The cloud endpoint 500s now and then, more often as the app (and so
            // the prompt) grows; one retry after a short pause saves the build.
            const runStep = () =>
              runEdit(stepPrompt(description, steps, i), {
                prefer: "cloud",
                baseHtml: lastHtmlRef.current ?? BLANK_APP,
                failNote:
                  i === 0
                    ? failNote
                    : `Step ${i + 1} of ${steps.length} ("${steps[i]}") did not land; steps 1-${i} are built. Ask for it again in the box below.`,
                quiet: true,
              });
            let ok = await runStep();
            if (!ok && !abortRef.current?.signal.aborted) {
              await new Promise((r) => setTimeout(r, 2500));
              ok = await runEdit(stepPrompt(description, steps, i), {
                prefer: "cloud",
                baseHtml: lastHtmlRef.current ?? BLANK_APP,
                failNote:
                  i === 0
                    ? failNote
                    : `Step ${i + 1} of ${steps.length} ("${steps[i]}") did not land after a retry; steps 1-${i} are built. Ask for it again in the box below.`,
              });
            }
            if (!ok) return;
            // A step that re-declares an earlier top-level name blanks the app:
            // repair it before the next step builds on a broken script.
            const dups = findDuplicateDeclarations(lastHtmlRef.current || "");
            if (dups.length) {
              const fixed = await runEdit(repairPrompt(dups), {
                prefer: "cloud",
                baseHtml: lastHtmlRef.current ?? BLANK_APP,
                failNote: `Step ${i + 1} left duplicate declarations (${dups.join(", ")}) that blank the app. Use "Fix it with AI" to repair.`,
              });
              if (!fixed) return;
            }
          }
          // Wire every screen into the router (steps only add render functions).
          const wired = await runEdit(integrationPrompt(description, steps), {
            prefer: "cloud",
            baseHtml: lastHtmlRef.current ?? BLANK_APP,
            failNote: "The screens are built but could not be wired to the tabs. Ask: \"make each tab show its own screen\".",
          });
          if (!wired) return;
          // A finished build that throws on load (e.g. a function used before it
          // is defined) shows a blank phone: give the preview a moment to report
          // the error, then repair it the way the "Fix it with AI" button would.
          for (let k = 0; k < 2; k++) {
            await new Promise((r) => setTimeout(r, 2500));
            const err = runtimeErrorRef.current;
            if (!err || ctrl.signal.aborted) return;
            const html = lastHtmlRef.current || "";
            pushLog({ kind: "user", text: k ? "Fix the error (again, differently)" : "Fix the error (automatic)" });
            const healed = await runEdit(fixRequest(err, locateRuntimeError(html, err, HARNESS_LINES), k > 0), {
              prefer: "cloud",
              baseHtml: html || BLANK_APP,
            });
            if (!healed) return;
          }
          return;
        }
      }
      lastHtmlRef.current = BLANK_APP;
      await runEdit(`Build this app inside the skeleton (replace the placeholder header, content and script): ${description}`, {
        prefer: "cloud",
        baseHtml: BLANK_APP,
        failNote,
      });
    },
    [enginePref, commit, runEdit, engine]
  );

  // Kick off the first generation for a project created from an idea.
  const kicked = useRef(false);
  useEffect(() => {
    if (!pendingCreate || kicked.current) return;
    kicked.current = true;
    const { description, fromTemplate } = pendingCreate;
    clearPendingCreate();
    pushLog({ kind: "user", text: description });
    if (fromTemplate) {
      void runEdit(
        `Adapt this app for the following idea. Change the <title>, headings, labels and anything else needed so it fits. Also REPLACE every pre-filled sample item in the script's data (tasks, entries, names) with realistic ones that match the idea - none of the original sample items may remain. Idea: ${description}`,
        {
          resetData: true,
          failNote:
            "Your idea was NOT built: the app on screen is only the generic starter, not your app. Send your request again in the box below (or pick the cloud engine) to have it adapted.",
        }
      );
    } else {
      void runCreate(description);
    }
  }, [pendingCreate, clearPendingCreate, runEdit, runCreate]);

  const send = (opts: { wholePage?: boolean } = {}) => {
    const r = request.trim();
    if (!r || busy) return;
    // A whole-page rewrite is the edit models get wrong most - dozens of
    // SEARCH blocks, some of which miss. Suggest tapping the part first and
    // go ahead only on an explicit yes (remembered for this session).
    if (!selected && !opts.wholePage && !wholePageOk.current && isWholePageRequest(r)) {
      setScopeAsk(r);
      return;
    }
    if (opts.wholePage) wholePageOk.current = true;
    setScopeAsk(null);
    const files = attached;
    const names = files.length ? `  📎 ${files.map((f) => f.name).join(", ")}` : "";
    pushLog({ kind: "user", text: (selected ? `${r}  (on the tapped <${selected.tag}>)` : r) + names });
    setRequest("");
    setAttached([]);
    void runEdit(withAttachments(r, files), { target: selected ? describeTarget(selected, currentHtml(project)) : null, assets: files });
  };

  const stop = () => abortRef.current?.abort();

  const restore = (index: number) => {
    const v = project.versions[index];
    if (!v) return;
    // The old version carries the connections block as it was THEN. Strip it:
    // commit() re-splices the block from the CURRENT connections / publish /
    // auth / AI settings, so a restore can never resurrect a detached service
    // or an old slug.
    commit(stripConnections(v.html), `Restored "${v.note}"`, "restore");
  };
  // Undo walks the *logical* history: a "restore" entry collapses the chain
  // back to the version it restored, so repeated undo keeps stepping back
  // instead of ping-ponging between the last two versions.
  const undoTarget = () => {
    const logical: number[] = [];
    project.versions.forEach((v, i) => {
      if (v.source === "restore") {
        let at = -1;
        // Compared without the generated connections block - a restore re-splices it.
        const target = stripConnections(v.html);
        for (let k = logical.length - 1; k >= 0; k--) {
          if (stripConnections(project.versions[logical[k]].html) === target) {
            at = k;
            break;
          }
        }
        if (at >= 0) logical.length = at + 1;
        else logical.push(i);
      } else logical.push(i);
    });
    return logical.length > 1 ? logical[logical.length - 2] : -1;
  };
  const undo = () => {
    const t = undoTarget();
    if (t >= 0) restore(t);
  };

  // Direct (no-AI) edit of the tapped element in the SOURCE.
  const directEdit = (fn: (el: HTMLElement) => void, note: string) => {
    if (!selected) return false;
    const doc = new DOMParser().parseFromString(currentHtml(project), "text/html");
    const el = findInSource(doc, selected) as HTMLElement | null;
    if (!el) return false;
    fn(el);
    commit(serializeDoc(doc), note, "design");
    return true;
  };

  const { attachConnection, detachConnection, updateConnection, setAuthMode, setAiConfig, publishApp, unpublishApp } =
    useProjectPublishing({ project, projectRef, update, isOwner, pushLog });

  // The Data tab: replace the app's saved data and reload the preview so the
  // app boots from exactly that data (an empty object = its built-in samples).
  const applyData = (next: Record<string, string>) => {
    update((p) => ({ ...p, appData: next }));
    setNonce((n) => n + 1);
  };

  /** The sign-in overlay resolved - drain every queued waiter for that slug. */
  const finishPendingAuth = (slug: string, user: { id?: string; email?: string; name?: string } | null) => {
    const list = pendingAuthResolvers.get(slug) || [];
    pendingAuthResolvers.delete(slug);
    list.forEach((r) => r(user));
    setPendingAuth(null);
  };

  const { size, scale, stageRef } = useStageScale(device);

  const elapsed = useElapsed(busy?.started ?? null);

  return {
    applyData, attachConnection, attached, busy, commit, detachConnection, device, dictation, diffFrom, directEdit, editable, elapsed, engine, env, fileInputRef, finishPendingAuth, frameRef, html, inspect, isOwner, lastFixRef, log, nonce, onBack, onOpenApp, panel, pendingAuth, pickFiles, project, publishApp, pushLog, request, restore, runEdit, runtimeError, scale, scopeAsk, selected, send, setAiConfig, setAttached, setAuthMode, setDevice, setDiffFrom, setEnginePref, setEnv, setInspect, setNonce, setPanel, setRequest, setScopeAsk, setSelected, size, srcDoc, stageRef, stop, stream, syncEnv, syncInspect, undo, undoTarget, unpublishApp, update, updateConnection
  };
}

export type ProjectSession = ReturnType<typeof useProjectSession>;
