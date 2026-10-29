"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { motion, AnimatePresence } from "motion/react";
import { RefreshCw, Sparkles } from "lucide-react";
import { Button } from "../ui/button";
import { resetSession } from "../try/demoClient";
import { studio, type StudioAgent, type AgentSpec } from "./studioClient";
import AgentDesigner, { type DesignerTab } from "./AgentDesigner";
import Genesis from "./Genesis";
import MissionConsole from "./MissionConsole";
import WorkflowCanvas from "./WorkflowCanvas";
import DeployPanel from "./DeployPanel";
import FairUseBar from "./FairUseBar";
import StudioPledge, { pledgeAccepted, rememberPledge } from "./StudioPledge";
import StudioTour, { type TourTarget } from "./StudioTour";
import TourSpotlight from "./TourSpotlight";
import { buildTourSteps } from "./tourSteps";
import StudioHero from "./StudioHero";
import FlightPlan from "./FlightPlan";
import Possibilities from "./Possibilities";
import AfterSandbox from "./AfterSandbox";
import PhoneStage, { type PhoneScene } from "./PhoneStage";
import { ConvertBar, type LaunchProgress } from "./Conversion";
import Showtime from "./showtime/Showtime";
import type { Draft } from "./topicDraft";
import { useStudioSession } from "./useStudioSession";
import AgentsRail from "./AgentsRail";
import { EmptyState, NextStepNudge, StageRail, StudioSiteHeader, StudioStatusBar, type Stage } from "./StudioPanels";
import { DeleteAgentDialog, RestartSandboxDialog } from "./StudioDialogs";

// /try - the Agent Studio.
//
// Not a chatbot. The visitor describes ANY job, gets a real agent, arms it with
// documents and MCP connectors, gives it a mission and watches the real tool
// trace, chains agents into a workflow with a real approval pause, and puts
// the result on a share link or Telegram. Every agent lives in the shared demo
// tenant.
//
// Nothing about a visitor's topic outlives the sandbox and nothing is
// pre-chosen for them: opening or restarting a sandbox lands on the blank
// launcher, and every agent - the first one included - can be deleted.
//
// The boot screen is content (see app/try/page.tsx), so this component owns
// its own loading narration rather than hiding behind the route cover.
//
// Split for the file-size cap: useStudioSession owns the sandbox lifecycle
// (boot, loads, fair-use refresh); StudioPanels, AgentsRail and StudioDialogs
// are the presentational pieces. This component keeps the UI state and wiring.

/** What the launcher learned about an agent - kept beside it so the Run and Knowledge tabs can be specific. */
type AgentContext = {
  topic: string;
  firstMission: string;
  sampleMissions: string[];
  mcpQuery: string;
  docIdea: string;
};

export default function TryStudio() {
  const {
    phase,
    bootText,
    unavailable,
    token,
    setToken,
    session,
    setSession,
    agents,
    setAgents,
    workflows,
    setWorkflows,
    palette,
    setPalette,
    workflowsMax,
    selectedId,
    setSelectedId,
    fresh,
    setFresh,
    status,
    refreshAgents,
    loadStudio,
    clearSeeded,
    onBudget,
  } = useStudioSession();
  const [mode, setMode] = useState<"launch" | "hand" | null>(null); // null = show the selected agent
  const [building, setBuilding] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [stage, setStage] = useState<Stage>("design");
  const [designerTab, setDesignerTab] = useState<DesignerTab | undefined>(undefined);
  const [done, setDone] = useState<Set<Stage>>(new Set());
  const [contexts, setContexts] = useState<Record<string, AgentContext>>({});
  const [resetting, setResetting] = useState(false);
  const [confirmRestart, setConfirmRestart] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<StudioAgent | null>(null);
  const [pledged, setPledged] = useState(true); // assume yes until the browser says otherwise - avoids a flash of the dialog for returning visitors
  const [tourOpen, setTourOpen] = useState(false); // the longer feature guide (modal)
  const [spotOpen, setSpotOpen] = useState(false); // the interactive walkthrough
  const [seed, setSeed] = useState<{ text: string; n: number } | null>(null); // words from the hero box / a card, dropped into the launcher
  const [showtime, setShowtime] = useState<null | "day" | "watch" | "build">(null); // full-screen film / trailer / live build
  const [atStudio, setAtStudio] = useState(false); // the visitor has scrolled (or jumped) to the studio - only then does the fair-use pledge ask for anything
  const [shareUrl, setShareUrl] = useState<string | null>(null); // the real share link, once minted - drives the phone's QR
  const hasAgentRef = useRef(false);
  const studioRef = useRef<HTMLDivElement | null>(null);

  const mine = useMemo(() => agents.filter((a) => a.mine), [agents]);
  hasAgentRef.current = mine.length > 0;
  const selected = useMemo(() => agents.find((a) => a.id === selectedId) ?? null, [agents, selectedId]);
  const ctx = selected ? contexts[selected.id] : undefined;
  const readOnly = !!session?.readOnly && !session?.expired;
  // No lifetime allowance any more: the only thing that ever blocks a run is the fair-use gateway, which answers per request.
  const outOfBudget = false;
  const signupHref = token ? `/signup?demo=${encodeURIComponent(token)}` : "/signup";
  // Blank launcher whenever the visitor has no agent of their own to look at.
  const launching = mode === "launch" || (mode === null && !selected);

  useEffect(() => {
    setPledged(pledgeAccepted());
  }, []);

  // /try#trailer opens the film straight away (shareable link).
  useEffect(() => {
    if (window.location.hash === "#trailer") setShowtime("watch");
    else if (window.location.hash === "#day") setShowtime("day");
  }, []);

  // The pledge is a gate to USING the studio, not to reading the page: asking on arrival would put a dialog over the hero.
  useEffect(() => {
    const el = studioRef.current;
    if (!el || atStudio) return undefined;
    if (typeof IntersectionObserver === "undefined") {
      setAtStudio(true);
      return undefined;
    }
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting || e.boundingClientRect.top < 0)) {
          // or already scrolled past it
          setAtStudio(true);
          io.disconnect();
        }
      },
      { rootMargin: "0px 0px -35% 0px" }
    );
    io.observe(el);
    return () => io.disconnect();
  }, [atStudio]);

  const acceptPledge = () => {
    rememberPledge();
    setPledged(true);
    try {
      if (window.localStorage.getItem("vegaduta.studioTour.v1") !== "1") {
        window.localStorage.setItem("vegaduta.studioTour.v1", "1");
        setSpotOpen(true);
      }
    } catch {
      // storage refused - no auto tour, the button is still there
    }
  };

  const goTo = (t: TourTarget) => {
    setStage(t.stage);
    if (t.tab) setDesignerTab(t.tab);
    setMode(null);
    studioRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  const markDone = (s: Stage) => setDone((d) => (d.has(s) ? d : new Set([...d, s])));

  const reloadPalette = async (tok: string, agentId: string) => {
    const p = await studio.palette(tok, agentId);
    if (p.ok) setPalette(p.value);
    return p.ok ? p.value : null;
  };

  /** The launcher: create from a drafted topic, trim what this deployment cannot offer, take the live slot if it is free. */
  const createFromDraft = async (d: Draft) => {
    if (!token) return;
    setBuilding(true);
    setSaveError(null);
    const res = await studio.createAgent(token, d.spec);
    if (!res.ok) {
      setSaveError(res.error.message);
      setBuilding(false);
      return;
    }
    let agent = res.value;
    const pal = await reloadPalette(token, agent.id);
    if (pal) {
      const availableTools = new Set(pal.tools.filter((t) => t.available).map((t) => t.name));
      const availableRules = new Set(pal.guardrails.filter((g) => g.available).map((g) => g.id));
      // The knowledge tool only resolves once a document exists; it switches itself on at the first upload.
      const keepTools = d.spec.tools.filter((t) => availableTools.has(t));
      const keepRules = d.spec.guardrails.filter((g) => availableRules.has(g));
      if (keepTools.length !== agent.tools.length || keepRules.length !== agent.guardrails.length) {
        const fixed = await studio.updateAgent(token, agent.id, { tools: keepTools, guardrails: keepRules });
        if (fixed.ok) agent = fixed.value;
      }
    }
    if (!mine.some((a) => a.live)) {
      const live = await studio.makeLive(token, agent.id);
      if (live.ok) agent = live.value;
    }
    setAgents((list) => [...list.filter((a) => a.id !== agent.id), agent]);
    setContexts((c) => ({
      ...c,
      [agent.id]: {
        topic: d.spec.name,
        firstMission: d.firstMission,
        sampleMissions: d.sampleMissions,
        mcpQuery: d.mcpQuery,
        docIdea: d.docIdea,
      },
    }));
    setSelectedId(agent.id);
    setMode(null);
    setFresh(false);
    setStage("design");
    setDesignerTab("knowledge");
    markDone("design");
    setBuilding(false);
  };

  const saveAgent = async (spec: AgentSpec): Promise<boolean> => {
    if (!token) return false;
    setSaving(true);
    setSaveError(null);
    const creating = mode === "hand";
    const res = creating
      ? await studio.createAgent(token, spec)
      : selected
        ? await studio.updateAgent(token, selected.id, spec)
        : null;
    setSaving(false);
    if (!res) return false;
    if (!res.ok) {
      setSaveError(res.error.message);
      return false;
    }
    let agent = res.value;
    if (creating && !mine.some((a) => a.live)) {
      const live = await studio.makeLive(token, agent.id);
      if (live.ok) agent = live.value;
    }
    setAgents((list) => (creating ? [...list, agent] : list.map((a) => (a.id === agent.id ? agent : a))));
    setSelectedId(agent.id);
    setMode(null);
    setFresh(false);
    markDone("design");
    if (creating) {
      setDesignerTab("knowledge");
      await reloadPalette(token, agent.id);
    }
    return true;
  };

  const deleteAgent = async (agent: StudioAgent) => {
    if (!token) return;
    setConfirmDelete(null);
    const res = await studio.deleteAgent(token, agent.id);
    if (!res.ok) {
      setSaveError(res.error.message);
      return;
    }
    setContexts((c) => {
      const { [agent.id]: _gone, ...rest } = c;
      return rest;
    });
    await refreshAgents(token, false);
  };

  const startOver = async () => {
    if (!token || resetting) return;
    setConfirmRestart(false);
    setResetting(true);
    const res = await resetSession(token, { blank: true });
    if (res.ok && res.session?.demoToken) {
      const tok = res.session.demoToken;
      setToken(tok);
      setSession(res.session);
      await clearSeeded(tok);
      setAgents([]);
      setWorkflows([]);
      setSelectedId(null);
      setContexts({});
      setDone(new Set());
      setStage("design");
      setMode(null);
      setSaveError(null);
      setDesignerTab(undefined);
      setFresh(true);
      await loadStudio(tok, res.session);
    } else {
      setSaveError(res.message ?? "We couldn't restart the sandbox just now. Please try again.");
    }
    setResetting(false);
  };

  const tourSteps = useMemo(
    () => buildTourSteps({ goTo, hasAgent: () => hasAgentRef.current, openGuide: () => setTourOpen(true) }),
    // goTo only closes over stable setters and a ref

    []
  );

  const atCap = palette ? mine.length >= palette.maxAgentsPerSession : false;
  const openLauncher = () => {
    setMode("launch");
    setStage("design");
    setSaveError(null);
  };

  /** The hero box and the idea cards: carry words into the launcher, open it, and take the visitor there. */
  const launchWith = (text: string) => {
    if (text) setSeed({ text, n: Date.now() });
    if (phase === "ready") {
      setMode("launch");
      setStage("design");
      setSaveError(null);
    }
    window.setTimeout(() => studioRef.current?.scrollIntoView({ behavior: "smooth", block: "start" }), 30);
  };

  const progress: LaunchProgress = {
    built: mine.length > 0,
    armed: mine.some((a) => !a.toolsOff && a.tools.length > 0),
    ran: done.has("run"),
    chained: done.has("chain"),
    shipped: done.has("deploy"),
  };

  // The phone shows the stage the visitor is in, rendered from their real agent.
  const phoneScene: PhoneScene =
    launching || !selected
      ? seed?.text
        ? "brief"
        : "idle"
      : stage === "design"
        ? "built"
        : stage === "run"
          ? "run"
          : stage === "chain"
            ? "ask"
            : "pocket";

  return (
    <main className="mx-auto w-full max-w-[1400px] overflow-x-clip px-4 pb-32 pt-4 sm:px-6">
      <StudioSiteHeader signupHref={signupHref} />

      <StudioHero phase={phase} bootLine={bootText} onLaunch={launchWith} onShowtime={() => setShowtime("day")} />
      <FlightPlan onStart={() => launchWith("")} onShowtime={() => setShowtime("day")} />
      <Possibilities onPick={launchWith} />

      <div ref={studioRef} className="mt-24 scroll-mt-20">
        <div className="max-w-2xl">
          <div className="mb-2 inline-flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-violet-700 dark:text-violet-300">
            <Sparkles aria-hidden="true" className="h-4 w-4 shrink-0" /> Now you - this part is real
          </div>
          <h2 className="text-3xl font-semibold tracking-tight sm:text-4xl">
            Your studio. An empty page and a live phone.
          </h2>
        </div>
      </div>

      <AnimatePresence mode="wait">
        {phase === "unavailable" && (
          <motion.div
            key="unavailable"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            className="mt-10 rounded-2xl border border-border/60 p-6"
          >
            <div className="mb-1 text-lg font-semibold">The studio is taking a breather</div>
            <p className="mb-4 text-sm text-muted-foreground">{unavailable}</p>
            <div className="flex gap-2">
              <Button onClick={() => window.location.reload()}>
                <RefreshCw aria-hidden="true" className="me-2 h-4 w-4 shrink-0" /> Try again
              </Button>
              <Button variant="outline" asChild>
                <Link href="/signup">Create a free account instead</Link>
              </Button>
            </div>
          </motion.div>
        )}

        {phase === "ready" && token && session && (
          <motion.section key="studio" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} className="mt-10">
            <StudioStatusBar
              session={session}
              readOnly={readOnly}
              resetting={resetting}
              onTour={() => setSpotOpen(true)}
              onShowtime={() => setShowtime("day")}
              onGuide={() => setTourOpen(true)}
              onRestart={() => setConfirmRestart(true)}
            />

            <div className="mb-4" data-tour="fair-use">
              <FairUseBar status={status} />
            </div>

            <StageRail stage={stage} done={done} onStage={setStage} />

            <div className="grid gap-6 lg:grid-cols-[260px_minmax(0,1fr)] xl:grid-cols-[260px_minmax(0,1fr)_268px]">
              <AgentsRail
                mine={mine}
                maxAgents={palette ? palette.maxAgentsPerSession : null}
                selectedId={selectedId}
                showingSelected={mode === null}
                readOnly={readOnly}
                atCap={atCap}
                progress={progress}
                onSelect={(id) => {
                  setSelectedId(id);
                  setMode(null);
                }}
                onDelete={setConfirmDelete}
                onNew={openLauncher}
              />

              {/* Stage body */}
              <div
                className="min-w-0 rounded-2xl border border-border/60 bg-background/60 p-5 backdrop-blur"
                data-tour="stage-body"
              >
                {saveError && (
                  <div className="mb-4 rounded-lg border border-destructive/40 bg-destructive/5 p-3 text-sm">
                    {saveError}
                  </div>
                )}
                {stage === "design" &&
                  (launching ? (
                    <Genesis
                      onBuild={createFromDraft}
                      onBlank={() => setMode("hand")}
                      busy={building}
                      readOnly={readOnly || atCap}
                      fresh={fresh}
                      seed={seed}
                    />
                  ) : mode === "hand" ? (
                    <AgentDesigner
                      token={token}
                      agent={null}
                      palette={palette}
                      readOnly={readOnly}
                      saving={saving}
                      onSave={saveAgent}
                      onCancelNew={() => setMode(null)}
                      onAgentRefresh={() => token && void refreshAgents(token)}
                    />
                  ) : selected ? (
                    <AgentDesigner
                      token={token}
                      agent={selected}
                      palette={palette}
                      readOnly={readOnly || !selected.mine}
                      saving={saving}
                      mcpHint={ctx?.mcpQuery}
                      topicHint={ctx?.docIdea}
                      initialTab={designerTab}
                      onSave={saveAgent}
                      onAgentRefresh={() => token && void refreshAgents(token)}
                    />
                  ) : null)}
                {stage === "run" &&
                  (selected ? (
                    <MissionConsole
                      token={token}
                      agent={selected}
                      readOnly={readOnly}
                      outOfBudget={outOfBudget}
                      suggested={ctx?.sampleMissions}
                      timeoutSeconds={status?.missionTimeoutSeconds}
                      prefill={ctx?.firstMission}
                      onBudget={onBudget}
                      onAgentChanged={(a) => setAgents((list) => list.map((x) => (x.id === a.id ? a : x)))}
                      onFirstRun={() => markDone("run")}
                    />
                  ) : (
                    <EmptyState
                      onNew={() => {
                        openLauncher();
                      }}
                    />
                  ))}
                {stage === "chain" && (
                  <WorkflowCanvas
                    token={token}
                    agents={agents}
                    workflows={workflows}
                    readOnly={readOnly}
                    outOfBudget={outOfBudget}
                    workflowsMax={workflowsMax}
                    onBudget={onBudget}
                    onWorkflowsChanged={setWorkflows}
                    onFirstRun={() => markDone("chain")}
                  />
                )}
                {stage === "deploy" && (
                  <DeployPanel
                    token={token}
                    session={session}
                    agents={agents}
                    readOnly={readOnly}
                    signupHref={signupHref}
                    onAgentsChanged={(list) => {
                      setAgents(list);
                      markDone("deploy");
                    }}
                    onTurnSpent={onBudget}
                    onShareUrl={(u) => {
                      setShareUrl(u);
                      markDone("deploy");
                    }}
                  />
                )}
              </div>

              {/* The phone: the same object as in the hero, now rendered from the visitor's own agent. */}
              <aside className="hidden xl:block" aria-label="Your agent on a phone" data-tour="phone">
                <div className="sticky top-20">
                  <PhoneStage
                    scene={phoneScene}
                    agentName={selected?.name}
                    job={seed?.text}
                    mission={ctx?.firstMission ?? selected?.mission}
                    tools={selected && !selected.toolsOff ? selected.tools : []}
                    shareUrl={shareUrl}
                  />
                  <p className="mx-auto mt-4 max-w-[230px] text-center text-[11px] leading-relaxed text-muted-foreground">
                    It follows you down the page. When you reach Deploy, this is the screen you scan.
                  </p>
                </div>
              </aside>
            </div>

            <NextStepNudge stage={stage} launching={launching} signupHref={signupHref} onStage={setStage} />
          </motion.section>
        )}
      </AnimatePresence>

      {phase !== "booting" && (
        <AfterSandbox
          signupHref={signupHref}
          hasAgent={mine.length > 0}
          onRestart={() => (mine.length > 0 ? setConfirmRestart(true) : launchWith(""))}
        />
      )}

      <Showtime
        open={showtime !== null}
        initialMode={showtime ?? "day"}
        onLaunch={(job) => {
          setShowtime(null);
          launchWith(job);
        }}
        onClose={() => setShowtime(null)}
        token={token}
        signupHref={signupHref}
        onBudget={onBudget}
        clientIp={status?.clientIp ?? null}
      />

      <ConvertBar
        progress={progress}
        agentName={selected?.name ?? mine[0]?.name ?? null}
        signupHref={signupHref}
        hidden={spotOpen || tourOpen || !pledged || showtime !== null}
      />

      <StudioPledge
        open={phase === "ready" && !pledged && atStudio}
        ip={status?.clientIp ?? null}
        missionSeconds={status?.missionTimeoutSeconds ?? 120}
        runsPerMinute={status?.fairUse?.missionsPerMinute ?? 6}
        onAccept={acceptPledge}
      />
      <TourSpotlight open={spotOpen} steps={tourSteps} onClose={() => setSpotOpen(false)} />
      <StudioTour open={tourOpen} onClose={() => setTourOpen(false)} onGo={goTo} signupHref={signupHref} />

      <RestartSandboxDialog open={confirmRestart} onOpenChange={setConfirmRestart} onRestart={() => void startOver()} />
      <DeleteAgentDialog
        agent={confirmDelete}
        onCancel={() => setConfirmDelete(null)}
        onDelete={(agent) => void deleteAgent(agent)}
      />
    </main>
  );
}
