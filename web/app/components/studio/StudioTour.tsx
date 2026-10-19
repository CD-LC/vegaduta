"use client";

import { useState } from "react";
import Link from "next/link";
import {
  Target,
  Hand,
  FileText,
  Plug,
  SlidersHorizontal,
  Play,
  Workflow as WorkflowIcon,
  Rocket,
  ShieldCheck,
  LayoutDashboard,
  ArrowRight,
  ArrowLeft,
  X,
  Bot,
  BookOpen,
  Blocks,
  KeyRound,
  CheckCircle2,
  Radio,
  Activity,
  Gauge,
  ShieldAlert,
  Users,
} from "lucide-react";
import { Button } from "../ui/button";
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";
import type { DesignerTab } from "./AgentDesigner";

// The tour: every feature the studio has, how to control it, and where to go to
// try it. "Take me there" closes the tour and lands on the exact stage or tab.
// The last stop is a look at the real workspace - the left-hand navigation a
// signed-in account has - mapped back to what was built here, so nothing on
// this page is a dead end.

export type TourTarget = { stage: "design" | "run" | "chain" | "deploy"; tab?: DesignerTab };

type Step = {
  id: string;
  icon: typeof Target;
  title: string;
  lead: string;
  controls: string[];
  target?: TourTarget;
  cta?: string;
};

const STEPS: Step[] = [
  {
    id: "brief",
    icon: Target,
    title: "1 - Brief the agent",
    lead: "You start by answering five questions. Nothing is pre-built: the agent is composed from your answers.",
    controls: [
      "The job, who it serves, what it must never do, what it needs, how precise it should be.",
      "Every answer is editable afterwards in the Brief tab - the mission is plain text you can rewrite.",
      "New agent or Restart with a new idea gives you a clean briefing. Any agent, the first one included, can be deleted.",
    ],
    target: { stage: "design", tab: "brief" },
    cta: "Open the Brief",
  },
  {
    id: "hands",
    icon: Hand,
    title: "2 - Give it hands",
    lead: "Tools turn an answer into an action. Each is a switch; switch them all off and you have a chatbot.",
    controls: [
      "Search the web, read a page, call an API, run code, remember and recall.",
      "Tools that aren't available in this deployment are shown with a reason, never hidden.",
      "On the Run stage, 'Same mission, no hands' runs it twice so you can see what the tools added.",
    ],
    target: { stage: "design", tab: "hands" },
    cta: "Open Hands",
  },
  {
    id: "knowledge",
    icon: FileText,
    title: "3 - Hand it your documents",
    lead: "Upload a policy, price list, contract or notes. The agent searches them and answers from them.",
    controls: [
      "Drag files in, choose files, or paste notes. The sandbox reads plain text: .txt, .md and .csv. For a PDF or Word file, paste its text; full file support comes with your workspace.",
      "Each agent has its own private collection - another agent can't read it.",
      "Delete a document and it stops being used at once. The trace shows every search it makes.",
    ],
    target: { stage: "design", tab: "knowledge" },
    cta: "Open Knowledge",
  },
  {
    id: "connectors",
    icon: Plug,
    title: "4 - Plug in connectors",
    lead: "The MCP registry is the public directory of tools agents can use. Search it live and attach one to an agent.",
    controls: [
      "Only hosted servers that need no credentials are listed here.",
      "Each connector belongs to one agent; remove it and that access is gone.",
      "In your own workspace, connectors can carry your keys and cover far more.",
    ],
    target: { stage: "design", tab: "connectors" },
    cta: "Open Connectors",
  },
  {
    id: "behavior",
    icon: SlidersHorizontal,
    title: "5 - Tune the precision",
    lead: "Four dials decide how exact it is: thinking depth, autonomy, rules and creativity.",
    controls: [
      "Thinking: fast, careful, rigorous or planned - up when a mistake is costly.",
      "Autonomy: asks before sensitive acts, or runs on its own for jobs you trust.",
      "Rules: personal-data, toxic-tone and other checks on every turn. The studio's safety rules are always on.",
    ],
    target: { stage: "design", tab: "behavior" },
    cta: "Open Behavior",
  },
  {
    id: "run",
    icon: Play,
    title: "6 - Run a mission, watch the trace",
    lead: "Give it a real job. Every tool call, search and rule check appears as it happens, next to the answer.",
    controls: [
      "Stop a run at any time. Every run has a wall-clock time cap and stops when it is reached.",
      "Missions are unlimited; only a burst limit applies (see the bar at the top).",
      "Run the same job with and without hands to see exactly what agency adds.",
    ],
    target: { stage: "run" },
    cta: "Go to Run",
  },
  {
    id: "chain",
    icon: WorkflowIcon,
    title: "7 - Chain agents, keep a human in the loop",
    lead: "Put agents in sequence with conditions and an approval step that genuinely pauses until you decide.",
    controls: [
      "Steps: agent, condition, approval, output. Build your own or start from a template.",
      "Approve or reject in the run panel - the run waits for you, up to its time cap.",
      "The same step types exist in the workspace's workflow designer.",
    ],
    target: { stage: "chain" },
    cta: "Go to Chain",
  },
  {
    id: "deploy",
    icon: Rocket,
    title: "8 - Put it where people are",
    lead: "Make one agent live, then share it by link or connect a Telegram bot.",
    controls: [
      "The live agent is the one behind the share link and the Telegram bridge.",
      "Public links keep their own message allowance - a link anyone can open can't be unlimited.",
      "Deleting the live agent pauses the link until you make another live.",
    ],
    target: { stage: "deploy" },
    cta: "Go to Deploy",
  },
  {
    id: "safety",
    icon: ShieldCheck,
    title: "9 - How the studio stays open",
    lead: "Unlimited only works with gateways. These are the ones in place - all visible in the bar at the top.",
    controls: [
      "Speed, not a quota: a burst limit per sandbox, per network and per day; a cap on runs in flight.",
      "A wall-clock time cap on every run and every workflow.",
      "Safety rules on every agent that can't be removed; repeated refusals pause the sandbox for a while.",
      "Your network address is recorded with each sandbox and logged against every run. Please refrain from illegal or abusive activity.",
    ],
  },
];

const WORKSPACE: { icon: typeof Bot; label: string; note: string }[] = [
  { icon: LayoutDashboard, label: "Home", note: "Your dashboard - what your agents are doing now." },
  { icon: Bot, label: "Agents", note: "Where agents from this studio follow you when you sign up." },
  { icon: WorkflowIcon, label: "Workflows", note: "The full designer behind the Chain stage." },
  { icon: BookOpen, label: "Knowledge", note: "Documents at scale - the same search as the Knowledge tab." },
  { icon: Blocks, label: "Tools", note: "Connect tools and MCP servers, with your own keys." },
  { icon: KeyRound, label: "Secrets", note: "A vault for the credentials connectors need." },
  { icon: ShieldAlert, label: "Guardrails", note: "The rules that run on every turn, tenant-wide." },
  { icon: CheckCircle2, label: "Approvals", note: "Where an agent's request for a yes lands." },
  { icon: Radio, label: "Channels", note: "Reach people on the channels you connect." },
  { icon: Activity, label: "Activity and audit", note: "A record of what ran and who did what." },
  { icon: Gauge, label: "Usage", note: "What you have used, against your plan." },
  { icon: Users, label: "Access", note: "Invite teammates and control what they can do." },
];

type Props = {
  open: boolean;
  onClose: () => void;
  onGo: (target: TourTarget) => void;
  signupHref: string;
};

export default function StudioTour({ open, onClose, onGo, signupHref }: Props) {
  const [i, setI] = useState(0);
  const total = STEPS.length + 1; // + the workspace stop
  const onWorkspace = i === STEPS.length;
  const step = STEPS[Math.min(i, STEPS.length - 1)];
  const Icon = onWorkspace ? LayoutDashboard : step.icon;

  return (
    <AlertDialog open={open} onOpenChange={(o) => !o && onClose()}>
      <AlertDialogContent className="max-w-3xl gap-0 p-0">
        <div className="grid min-h-[420px] md:grid-cols-[210px_minmax(0,1fr)]">
          <nav aria-label="Tour steps" className="hidden border-r border-border/60 bg-muted/30 p-3 md:block">
            <ol className="space-y-0.5">
              {[
                ...STEPS.map((s) => ({ id: s.id, icon: s.icon, title: s.title.replace(/^\d+ - /, "") })),
                { id: "workspace", icon: LayoutDashboard, title: "Your workspace" },
              ].map((s, idx) => {
                const SIcon = s.icon;
                return (
                  <li key={s.id}>
                    <button
                      type="button"
                      onClick={() => setI(idx)}
                      aria-current={idx === i ? "step" : undefined}
                      className={`flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-xs transition-colors ${idx === i ? "bg-background font-medium shadow-sm" : "text-muted-foreground hover:text-foreground"}`}
                    >
                      <SIcon className="h-3.5 w-3.5 shrink-0" /> <span className="truncate">{s.title}</span>
                    </button>
                  </li>
                );
              })}
            </ol>
          </nav>

          <div className="flex flex-col p-5">
            <AlertDialogHeader className="text-left">
              <div className="flex items-start justify-between gap-3">
                <div className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-primary">
                  <Icon className="h-4 w-4" /> Guided tour · {i + 1} of {total}
                </div>
                <button
                  type="button"
                  onClick={onClose}
                  aria-label="Close the tour"
                  className="text-muted-foreground hover:text-foreground"
                >
                  <X aria-hidden="true" className="h-4 w-4 shrink-0" />
                </button>
              </div>
              <AlertDialogTitle className="text-xl">
                {onWorkspace ? "9b - Your workspace, after signup" : step.title}
              </AlertDialogTitle>
              <AlertDialogDescription asChild>
                <div className="space-y-3 text-sm">
                  {onWorkspace ? (
                    <>
                      <p>
                        This studio is the workshop; the workspace is the building. Its left-hand navigation looks like
                        this, and each part picks up where a studio feature leaves off. Agents you build here follow you
                        in when you sign up; documents and connectors you re-add in Knowledge and Tools.
                      </p>
                      <ul className="grid gap-1.5 sm:grid-cols-2">
                        {WORKSPACE.map((w) => {
                          const WIcon = w.icon;
                          return (
                            <li key={w.label} className="flex items-start gap-2 rounded-lg border border-border/60 p-2">
                              <WIcon className="mt-0.5 h-3.5 w-3.5 shrink-0 text-primary" />
                              <div>
                                <div className="text-xs font-medium text-foreground">{w.label}</div>
                                <div className="text-[11px] text-muted-foreground">{w.note}</div>
                              </div>
                            </li>
                          );
                        })}
                      </ul>
                    </>
                  ) : (
                    <>
                      <p className="text-foreground">{step.lead}</p>
                      <div>
                        <div className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                          How you control it
                        </div>
                        <ul className="space-y-1.5">
                          {step.controls.map((c) => (
                            <li key={c} className="flex gap-2">
                              <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-primary" /> <span>{c}</span>
                            </li>
                          ))}
                        </ul>
                      </div>
                    </>
                  )}
                </div>
              </AlertDialogDescription>
            </AlertDialogHeader>

            <div className="mt-auto flex flex-wrap items-center gap-2 pt-5">
              <Button variant="ghost" size="sm" disabled={i === 0} onClick={() => setI(i - 1)}>
                <ArrowLeft aria-hidden="true" className="mr-1 h-3.5 w-3.5 shrink-0" /> Back
              </Button>
              {!onWorkspace && (
                <Button variant="outline" size="sm" onClick={() => setI(i + 1)}>
                  Next <ArrowRight aria-hidden="true" className="ml-1 h-3.5 w-3.5 shrink-0" />
                </Button>
              )}
              <div className="ml-auto flex gap-2">
                {!onWorkspace && step.target && (
                  <Button
                    size="sm"
                    onClick={() => {
                      onGo(step.target!);
                      onClose();
                    }}
                  >
                    {step.cta} <ArrowRight aria-hidden="true" className="ml-1 h-3.5 w-3.5 shrink-0" />
                  </Button>
                )}
                {onWorkspace && (
                  <>
                    <Button variant="outline" size="sm" onClick={onClose}>
                      Back to the studio
                    </Button>
                    <Button size="sm" asChild>
                      <Link href={signupHref}>
                        Create my workspace <ArrowRight aria-hidden="true" className="ml-1 h-3.5 w-3.5 shrink-0" />
                      </Link>
                    </Button>
                  </>
                )}
              </div>
            </div>
          </div>
        </div>
      </AlertDialogContent>
    </AlertDialog>
  );
}
