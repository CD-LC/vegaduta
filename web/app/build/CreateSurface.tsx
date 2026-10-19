"use client";

// Create - the merged Build surface. ONE blueprint object drives two outputs:
//
//   Agent  ->  the blueprint compiles to a Brief (brief-schema.v1.json) and is
//              created through core's brief/apply pipeline: it arrives as an
//              ENVOY, under watch on the trust ladder, with a starter eval set
//              in its ledger - not an unmanaged agent with no evidence.
//   App    ->  the blueprint compiles to a scaffold instruction for the
//              app-builder service: a real project committed to the person's
//              own Git repository with a PR open.
//
// The free on-device model (WebLLM) drafts the blueprint first because it
// costs the workspace nothing and the goal never leaves the browser; the
// built-in recipes are the always-works fallback. See blueprint.ts for the
// model, toBrief/toAppPrompt for the two compilers.
//
// UX rules for this page (it is the front door - people judge the platform here):
//   - The Build button is never silently dead. If something blocks it, clicking
//     scrolls to and names the problem.
//   - Only a missing AI model is "required". Channels, tools, CRMs are optional
//     add-ons, shown as such, and never as "Fix".
//   - A failed build always explains itself (explainError.ts) - what happened,
//     why, what to do - with the raw message behind a fold-out.

import { useCallback, useEffect, useRef, useState } from "react";
import { AnimatePresence } from "motion/react";
import { api } from "../lib/api";
import { appBuilder, newProjectId, type AppBuilderGithubStatus } from "./lib";
import { RECIPES } from "../lib/capabilities";
import BuildStepper from "./BuildStepper";
import {
  blueprintFromGoal,
  blueprintFromRecipe,
  defaultAppSpec,
  postApplyAgentPatches,
  readiness,
  toAppPrompt,
  toBrief,
  validate,
  withOutput,
  type Blueprint,
  type Level,
  type OutputKind,
  type WorkspaceFacts,
} from "../lib/build/blueprint";
import { draftWithCloud } from "../lib/build/cloudDraft";
import { draftWithEdge, edgeStatus, type EdgeStatus } from "../lib/build/edgeDraft";
import { explainBuildError, stepLabel, type RawBuildError } from "../lib/build/explainError";
import { summariseApply } from "../components/brief/logic";
import type { ApplyJob, BriefDetail } from "../components/brief/types";
import BlueprintStage from "./create/BlueprintStage";
import BuildingStage from "./create/BuildingStage";
import DescribeStage from "./create/DescribeStage";
import DoneStage from "./create/DoneStage";
import {
  POLL_MAX_FAILURES,
  POLL_MAX_MS,
  POLL_MS,
  prefersReducedMotion,
  toRaw,
  type DraftEngine,
  type Stage,
} from "./create/createShared";

export default function CreateSurface({
  onOpenApp,
}: {
  /** Hands a finished app scaffold to the Custom apps tab. */
  onOpenApp: (projectId: string) => void;
}) {
  const [stage, setStage] = useState<Stage>("describe");
  const [goal, setGoal] = useState("");
  const [output, setOutput] = useState<OutputKind>("agent");
  const [bp, setBp] = useState<Blueprint | null>(null);
  const [level, setLevel] = useState<Level>("simple");
  const [drafting, setDrafting] = useState(false);
  const [draftNote, setDraftNote] = useState<string | null>(null);
  const [edge, setEdge] = useState<EdgeStatus | null>(null);
  const [facts, setFacts] = useState<WorkspaceFacts>({ hasProviderKey: false, platformModelAvailable: false });
  const [factsLoaded, setFactsLoaded] = useState(false);
  const [providerLabel, setProviderLabel] = useState("");
  const [showProblems, setShowProblems] = useState(false);
  // null = not known yet (or the app-builder service did not answer): never block a build on a guess.
  const [gh, setGh] = useState<AppBuilderGithubStatus | null>(null);
  const [elapsed, setElapsed] = useState(0);
  const alive = useRef(true);
  const problemsRef = useRef<HTMLDivElement>(null);
  const topRef = useRef<HTMLDivElement>(null);
  const buildingRef = useRef<HTMLDivElement>(null);
  // Latest workspace facts for async callbacks (draft() outlives the render it started in).
  const factsRef = useRef({ facts, loaded: false });
  factsRef.current = { facts, loaded: factsLoaded };
  // The brief created for the current blueprint, so Retry reuses it instead of
  // orphaning a new one each time. `key` is the compiled brief; `jobId` is kept
  // only while that apply job may still be running.
  const briefRef = useRef<{ id: string; key: string; jobId: string | null } | null>(null);

  // Build state
  const [buildError, setBuildError] = useState<RawBuildError | null>(null);
  const [job, setJob] = useState<ApplyJob | null>(null);
  const [briefId, setBriefId] = useState<string | null>(null);
  const [, setAgentId] = useState<string | null>(null);
  const [patchNote, setPatchNote] = useState<string | null>(null);
  const [projectId, setProjectId] = useState<string | null>(null);
  // "customers should know what is powering" - which engine wrote the
  // blueprint (on-device model vs built-in recipes) and, once built, which
  // provider/model the new agent runs on. Set alongside the existing
  // draftNote/patch plumbing; null = nothing to disclose.
  const [draftEngine, setDraftEngine] = useState<DraftEngine | null>(null);
  const [builtEngine, setBuiltEngine] = useState<{ providerName: string | null; model: string | null } | null>(null);

  useEffect(() => {
    alive.current = true;
    void edgeStatus(true).then(setEdge).catch(() => setEdge(null));
    void Promise.all([
      api.listProviders().catch((): [] => []),
      api.platformLlm().catch((): null => null),
    ]).then(([providers, platform]) => {
      if (!alive.current) return;
      const list = Array.isArray(providers) ? providers : [];
      const p = platform && typeof platform === "object" ? (platform as Record<string, unknown>) : null;
      setFacts({
        hasProviderKey: list.length > 0,
        platformModelAvailable: !!(p && (p.available ?? p.enabled ?? p.provider)),
      });
      setFactsLoaded(true);
      if (list[0]?.providerName) setProviderLabel(`${list[0].providerName} (your key)`);
      else if (p?.provider || p?.available) setProviderLabel(`${p.provider ?? "Hosted model"} (hosted by the platform)`);
    });
    return () => {
      alive.current = false;
    };
  }, []);

  // A visible clock while building: silence reads as "it hung".
  useEffect(() => {
    if (stage !== "building" || buildError) return undefined;
    setElapsed(0);
    const id = window.setInterval(() => setElapsed((n) => n + 1), 1000);
    return () => window.clearInterval(id);
  }, [stage, buildError]);

  const edgeReady = edge?.state === "ready";

  const draftToken = useRef(0);
  const toTop = useCallback(() => {
    topRef.current?.scrollIntoView({ behavior: prefersReducedMotion() ? "auto" : "smooth", block: "start" });
  }, []);

  // Move keyboard/screen-reader focus to the building panel when it appears (or fails).
  useEffect(() => {
    if (stage === "building") buildingRef.current?.focus({ preventScroll: true });
  }, [stage, buildError]);

  // ---- Stage 1 -> 2 --------------------------------------------------------
  const draft = async (text: string, out: OutputKind) => {
    const g = text.trim();
    if (!g || drafting) return;
    const token = ++draftToken.current;
    setDrafting(true);
    setDraftNote(null);
    let next: Blueprint | null = null;
    let fromDevice = false;
    let fromCloud = false;
    let cloudSummary = "";
    let cloudReason: string | null = null;
    // 1. On-device model, if the person set one up (nothing leaves the browser).
    //    The on-device schema only knows agents, so apps go straight to the cloud.
    if (out === "agent") {
      try {
        next = await draftWithEdge(g);
        fromDevice = !!next;
      } catch {
        /* cloud below */
      }
    }
    // 2. The workspace's own model reads the request and designs the blueprint.
    if (!next) {
      let cloud: Awaited<ReturnType<typeof draftWithCloud>>;
      try {
        cloud = await draftWithCloud(g, out);
      } catch {
        cloud = { bp: null, summary: "", reason: null } as typeof cloud;
      }
      if (cloud.bp) {
        next = cloud.bp;
        fromCloud = true;
        cloudSummary = cloud.summary;
      } else {
        cloudReason = cloud.reason;
      }
    }
    // 3. Last resort: keyword-matched recipes (no AI involved).
    if (!next) next = blueprintFromGoal(g);
    // Edge drafts may predate the app section; normalize, then set the output.
    if (!next.app) next = { ...next, app: defaultAppSpec(g) };
    next = withOutput(next, out);
    // The user moved on (changed stage / started another draft) while we waited.
    if (token !== draftToken.current) {
      setDrafting(false);
      return;
    }
    setBp(next);
    // Only name the cloud source once the workspace facts have loaded; before
    // that we cannot tell BYOK from the platform pool, so show no chip rather
    // than a guess (the note below still says it was written by AI).
    const known = factsRef.current;
    setDraftEngine(
      fromDevice
        ? { kind: "device", model: edge && "modelName" in edge ? edge.modelName : null }
        : fromCloud
          ? known.loaded
            ? { kind: known.facts.hasProviderKey ? "byok" : "platform", model: null }
            : null
          : { kind: "recipes", model: null }
    );
    setDraftNote(
      fromDevice
        ? "Drafted privately on this device with a free, open-source model. Check every setting - small models can misjudge."
        : fromCloud
          ? `${cloudSummary ? `Here is what I understood: ${cloudSummary} ` : ""}Written by AI from your description - read the instructions and steps, and edit anything that is off.`
          : `${cloudReason ? `${cloudReason} ` : ""}Drafted from the built-in recipes instead (no AI): your words are dropped into a template, so check every setting.`
    );
    setDrafting(false);
    setShowProblems(false);
    setStage("blueprint");
    toTop();
  };

  const switchOutput = (out: OutputKind) => {
    setOutput(out);
    if (bp) setBp(withOutput(bp, out));
  };

  // ---- Stage 3: build ------------------------------------------------------
  const applyPatches = async (detail: BriefDetail, current: Blueprint) => {
    const patches = postApplyAgentPatches(current);
    const binding = (detail.bindings ?? []).find(
      (b) => b.ref === "a1" || /agent/i.test(b.kind)
    );
    if (!binding?.entityId) {
      if (patches) setPatchNote("The agent was created but its id was not reported, so the extra tuning dials were not applied - set them on the agent's page.");
      return;
    }
    const id = binding.entityId;
    setAgentId(id);
    // One listAgents read serves both the model patch below and the done
    // screen's "runs on {provider} · {model}" disclosure - best-effort, a
    // failed read leaves the chip hidden rather than blocking the build.
    const agents = await api.listAgents().catch((): unknown[] => []);
    const me = Array.isArray(agents)
      ? agents.find((a: unknown) => (a as { id?: string; agentId?: string })?.id === id || (a as { agentId?: string })?.agentId === id)
      : null;
    setBuiltEngine(
      me
        ? {
            providerName: (me as { providerName?: string }).providerName ?? null,
            model: (me as { model?: string }).model ?? null,
          }
        : null
    );
    if (!patches) return;
    const notes: string[] = [];
    if (patches.generation) {
      await api
        .setAgentGenerationParams(id, {
          maxTokens: current.agent.maxTokens,
          temperature: current.agent.temperature,
          topP: current.agent.topP,
          ...patches.generation,
        })
        .then(() => notes.push("sampling dials"))
        .catch(() => notes.push("sampling dials FAILED - set them on the agent's page"));
    }
    if (patches.autonomyTier) {
      await api
        .setAgentAutonomyTier(id, patches.autonomyTier)
        .then(() => notes.push("freedom level"))
        .catch(() => notes.push("freedom level FAILED - set it on the agent's page"));
    }
    if (patches.model) {
      if ((me as { providerName?: string })?.providerName) {
        await api
          .updateAgentModel(id, (me as { providerName: string }).providerName, patches.model)
          .then(() => {
            notes.push("model");
            // `me` was read before this patch - reflect the applied model in
            // the done-screen disclosure too.
            setBuiltEngine((cur) =>
              cur ? { ...cur, model: patches.model } : cur
            );
          })
          .catch(() => notes.push("model FAILED - set it on the agent's page"));
      } else {
        notes.push("model SKIPPED - provider unknown, set it on the agent's page");
      }
    }
    if (notes.length) setPatchNote(`Blueprint extras applied: ${notes.join(", ")}.`);
  };

  const buildAgent = async (current: Blueprint) => {
    const brief = toBrief(current);
    const key = JSON.stringify(brief);
    // Retry of the same blueprint: reuse its brief (and a still-running job)
    // instead of creating another brief nobody will ever open.
    let rec = briefRef.current && briefRef.current.key === key ? briefRef.current : null;
    if (!rec) {
      const { id: newId } = await api.createBrief(brief);
      rec = { id: newId, key, jobId: null };
      briefRef.current = rec;
    }
    if (!alive.current) return;
    const id = rec.id;
    setBriefId(id);
    if (!rec.jobId) {
      const { jobId: newJob } = await api.applyBrief(id, { background: true });
      rec.jobId = newJob;
    }
    const jobId = rec.jobId;
    const startedAt = Date.now();
    let failures = 0;
    for (;;) {
      let next: ApplyJob;
      try {
        next = await api.getApplyJob(id, jobId);
        failures = 0;
      } catch (e) {
        if (!alive.current) return;
        if (++failures >= POLL_MAX_FAILURES) throw e;
        await new Promise((r) => setTimeout(r, POLL_MS));
        continue;
      }
      if (!alive.current) return;
      setJob(next);
      if (summariseApply(next).terminal) {
        if (next.status !== "SUCCEEDED") {
          rec.jobId = null; // a retry must start a fresh apply on the same brief
          const failed = next.failedStep;
          const where = failed ? `While ${stepLabel(failed.kind).toLowerCase()}: ` : "";
          setBuildError({ message: `${where}${next.error || failed?.error || "The apply job failed."}`.trim() });
          return;
        }
        break;
      }
      if (Date.now() - startedAt > POLL_MAX_MS) {
        // Keep rec.jobId: Try again resumes watching the same job.
        setBuildError({
          message: "The build is taking much longer than usual (over 10 minutes) and timed out while we were watching it. It may still finish - check your Agents list, or press Try again to keep watching.",
        });
        return;
      }
      await new Promise((r) => setTimeout(r, POLL_MS));
    }
    const detail = await api.getBrief(id).catch((): null => null);
    if (detail) await applyPatches(detail, current);
    else if (postApplyAgentPatches(current)) {
      setPatchNote("The agent was created, but its details could not be read back, so the extra tuning dials (sampling, freedom level, model) were not applied - set them on the agent's page.");
    }
    setStage("done");
    toTop();
  };

  const buildApp = async (current: Blueprint) => {
    const pid = newProjectId();
    await appBuilder.createProject({
      project_id: pid,
      template_id: current.app.templateId,
      prompt: toAppPrompt(current),
    });
    if (!alive.current) return;
    setProjectId(pid);
    setStage("done");
    toTop();
  };

  const build = async () => {
    if (!bp) return;
    setBuildError(null);
    setJob(null);
    setPatchNote(null);
    setStage("building");
    toTop();
    try {
      if (bp.output === "app") await buildApp(bp);
      else await buildAgent(bp);
    } catch (e: unknown) {
      if (alive.current) setBuildError(toRaw(e));
    }
  };

  // The Build button is always live. If the blueprint has blocking problems, say so
  // where the person is looking instead of doing nothing.
  const tryBuild = () => {
    if (!bp) return;
    if (validate(bp).errors.length + gitErrors(bp) > 0) {
      setShowProblems(true);
      window.setTimeout(() => problemsRef.current?.scrollIntoView({ behavior: prefersReducedMotion() ? "auto" : "smooth", block: "center" }), 30);
      return;
    }
    void build();
  };

  const reset = () => {
    briefRef.current = null;
    setStage("describe");
    setBp(null);
    setJob(null);
    setBriefId(null);
    setAgentId(null);
    setProjectId(null);
    setBuildError(null);
    setPatchNote(null);
    setDraftNote(null);
    setDraftEngine(null);
    setBuiltEngine(null);
    setShowProblems(false);
    toTop();
  };

  const gitErrors = (b: Blueprint) => (b.output === "app" && gh && !gh.connected ? 1 : 0);
  const journeyIndex = stage === "describe" ? 0 : stage === "blueprint" ? 1 : stage === "building" ? 2 : 3;
  const v0 = bp ? validate(bp) : { errors: [], warnings: [] };
  const v = {
    ...v0,
    errors: bp && gitErrors(bp) ? [...v0.errors, "Connect your GitHub repository below - the app is saved there as a pull request you own."] : v0.errors,
  };
  const items = bp ? readiness(bp, facts) : [];
  const modelItem = items.find((i) => i.id === "model");
  const modelBlocked = factsLoaded && modelItem?.state === "blocked";
  const addOns = items.filter((i) => i.state === "optional");
  const explanation = buildError ? explainBuildError(buildError, bp?.output ?? "agent") : null;
  const progress = job && job.steps.length ? summariseApply(job) : null;
  const pct = progress ? Math.round((progress.done / Math.max(progress.total, 1)) * 100) : null;
  const reduceMotion = prefersReducedMotion();
  // A recipe pick supersedes any in-flight draft.
  const pickRecipe = (r: (typeof RECIPES)[number]) => {
    draftToken.current++;
    briefRef.current = null;
    setBp(withOutput(blueprintFromRecipe(r, goal, false), output));
    setDraftEngine({ kind: "recipes", model: null });
    setShowProblems(false);
    setStage("blueprint");
    toTop();
  };
  const importBlueprint = (next: Blueprint) => {
    briefRef.current = null;
    setOutput(next.output);
    setBp(next);
    setDraftEngine(null);
    setDraftNote("Loaded from JSON. Check every setting - nothing is created until you press Build.");
    setShowProblems(false);
    toTop();
  };

  return (
    <div ref={topRef} className="mx-auto max-w-4xl scroll-mt-4 space-y-6">
      <BuildStepper index={journeyIndex} failed={!!buildError} />

      <AnimatePresence mode="wait" initial={false}>
        {/* ---- Stage 1: describe --------------------------------------------- */}
        {stage === "describe" && (
          <DescribeStage
            goal={goal}
            setGoal={setGoal}
            output={output}
            switchOutput={switchOutput}
            drafting={drafting}
            edgeModelName={edgeReady ? edge.modelName : null}
            onDraft={(text, out) => void draft(text, out)}
            onPickRecipe={pickRecipe}
          />
        )}

        {/* ---- Stage 2: blueprint -------------------------------------------- */}
        {stage === "blueprint" && bp && (
          <BlueprintStage
            bp={bp}
            setBp={setBp}
            level={level}
            setLevel={setLevel}
            switchOutput={switchOutput}
            draftEngine={draftEngine}
            draftNote={draftNote}
            validation={v}
            showProblems={showProblems}
            problemsRef={problemsRef}
            reduceMotion={reduceMotion}
            onImport={importBlueprint}
            gh={gh}
            onGhStatusChange={setGh}
            modelItem={modelItem}
            factsLoaded={factsLoaded}
            addOns={addOns}
            modelBlocked={modelBlocked}
            providerLabel={providerLabel}
            onBack={() => setStage("describe")}
            onBuild={tryBuild}
          />
        )}

        {/* ---- Stage 3: building --------------------------------------------- */}
        {stage === "building" && bp && (
          <BuildingStage
            bp={bp}
            innerRef={buildingRef}
            buildError={buildError}
            explanation={explanation}
            job={job}
            progress={progress}
            pct={pct}
            elapsed={elapsed}
            reduceMotion={reduceMotion}
            onRetry={() => void build()}
            onBack={() => {
              setBuildError(null);
              setStage("blueprint");
            }}
          />
        )}

        {/* ---- Stage 4: done ------------------------------------------------- */}
        {stage === "done" && bp && (
          <DoneStage
            bp={bp}
            builtEngine={builtEngine}
            patchNote={patchNote}
            briefId={briefId}
            projectId={projectId}
            onOpenApp={onOpenApp}
            onReset={reset}
          />
        )}
      </AnimatePresence>
    </div>
  );
}
