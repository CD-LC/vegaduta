import type { TourStep } from "./TourSpotlight";
import type { TourTarget } from "./StudioTour";

// The interactive tour, in the order a first-time visitor should meet things.
// Steps before the first agent exists are about the briefing and end with a
// real "press Build" action; the tour then carries on into the agent's tabs.
// A returning visitor who already has an agent skips straight past the
// briefing. Every `target` is a data-tour attribute on a real control.

export type TourCtx = {
  goTo: (t: TourTarget) => void;
  hasAgent: () => boolean;
  /** Opens the longer feature guide (with the workspace map). */
  openGuide: () => void;
};

export function buildTourSteps(c: TourCtx): TourStep[] {
  const noAgent = () => !c.hasAgent();
  const hasAgent = () => c.hasAgent();

  return [
    {
      id: "welcome",
      target: null,
      title: "Let's build an agent together",
      body: "About two minutes. I'll point at the real controls and you'll use them - nothing here is a picture. Skip any time.",
    },
    {
      id: "stages",
      target: "stages",
      title: "Four stages, four colours",
      body: "Build (violet) → Run (green) → Chain (amber) → Deploy (blue). The coloured one is where you are; the tick means you've done it.",
      placement: "bottom",
    },
    {
      id: "job",
      target: "brief-job",
      title: "Tell it the job - in your own words",
      body: "Nothing is pre-built. What you write here becomes the agent's brief. Be specific: who it helps and what a good result looks like.",
      action: "type any job (6+ characters)",
      advanceWhen: () => {
        const el = document.querySelector<HTMLTextAreaElement>('[data-tour="brief-job"]');
        return !!el && el.value.trim().length >= 6;
      },
      skipIf: hasAgent,
      placement: "bottom",
    },
    {
      id: "brief-steps",
      target: "brief-steps",
      title: "Four more short questions",
      body: "Who it serves, what it must never do (the most valuable one), what tools it needs, and how precise it should be. Click any step to jump.",
      skipIf: hasAgent,
      optional: true,
      placement: "bottom",
    },
    {
      id: "blueprint-preview",
      target: "blueprint-preview",
      title: "Watch it assemble",
      body: "Its name, tools, thinking depth and brief appear here as you answer - built from your words alone. You can rewrite all of it afterwards.",
      skipIf: hasAgent,
      optional: true,
      placement: "left",
    },
    {
      id: "build",
      target: "build-btn",
      title: "Press Build",
      body: "This creates a real agent in the demo tenant. The tour continues as soon as it exists.",
      action: "press Build my agent",
      advanceWhen: hasAgent,
      skipIf: hasAgent,
      placement: "top",
    },
    {
      id: "tabs",
      target: "designer-tabs",
      title: "Five tabs to shape it",
      body: "Brief (who it is), Hands (tools), Knowledge (your documents), Connectors (MCP tools) and Behavior (how careful it is). Changes to Brief, Hands and Behavior save together.",
      onEnter: () => c.goTo({ stage: "design", tab: "brief" }),
      skipIf: noAgent,
      placement: "bottom",
    },
    {
      id: "hands",
      target: "designer-tabs",
      title: "Hands: switch tools on and off",
      body: "Green switches are tools it can use. Switch them all off and it's a chatbot - later you can run the same job both ways and see what the tools added.",
      onEnter: () => c.goTo({ stage: "design", tab: "hands" }),
      skipIf: noAgent,
      placement: "bottom",
    },
    {
      id: "knowledge",
      target: "dropzone",
      title: "Give it something to read",
      body: "Drop a text file, paste notes, or load the sample. It splits, embeds and searches them - and only this agent can see them.",
      action: "click \"Try a sample\" or drop a file",
      onEnter: () => c.goTo({ stage: "design", tab: "knowledge" }),
      skipIf: noAgent,
      optional: true,
      placement: "top",
    },
    {
      id: "connectors",
      target: "mcp-search",
      title: "Search the MCP registry",
      body: "The public directory of tools agents can use. The search is live; connecting servers opens up in your own workspace.",
      onEnter: () => c.goTo({ stage: "design", tab: "connectors" }),
      skipIf: noAgent,
      optional: true,
      placement: "bottom",
    },
    {
      id: "behavior",
      target: "dials",
      title: "The precision dials",
      body: "Thinking depth, autonomy, rules and creativity. Turn thinking up when a wrong answer is expensive; keep sensitive actions behind approval.",
      onEnter: () => c.goTo({ stage: "design", tab: "behavior" }),
      skipIf: noAgent,
      optional: true,
      placement: "top",
    },
    {
      id: "run-stage",
      target: "stage-run",
      title: "Now put it to work",
      body: "Head to the Run stage - that's where the agent actually does the job while you watch.",
      action: "click Run",
      advanceOnClick: true,
      skipIf: noAgent,
      placement: "bottom",
    },
    {
      id: "mission",
      target: "mission-box",
      title: "Give it a real task",
      body: "Every tool call, search and safety check appears next to the answer as it happens. There's a time cap on each run, and you can stop it any time.",
      onEnter: () => c.goTo({ stage: "run" }),
      skipIf: noAgent,
      optional: true,
      placement: "bottom",
    },
    {
      id: "fair-use",
      target: "fair-use",
      title: "Unlimited - with guard rails",
      body: "No message allowance, but there's a speed limit, a time cap per run, and safety rules that can't be switched off. Your network address is recorded - please keep it legitimate.",
      placement: "bottom",
    },
    {
      id: "chain",
      target: "stage-chain",
      title: "Chain agents, keep a human in the loop",
      body: "Chain puts agents in sequence with conditions and an approval step that genuinely pauses until you decide.",
      action: "click Chain",
      advanceOnClick: true,
      placement: "bottom",
    },
    {
      id: "deploy",
      target: "stage-deploy",
      title: "Put it where people are",
      body: "Deploy makes one agent live behind a share link or a Telegram bot.",
      action: "click Deploy",
      advanceOnClick: true,
      placement: "bottom",
    },
    {
      id: "finish",
      target: null,
      title: "That's the studio",
      body: "Restart with a new idea any time - nothing carries over. The Feature guide shows how everything you built maps onto the full workspace after signup.",
    },
  ];
}
