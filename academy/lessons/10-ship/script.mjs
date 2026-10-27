export default {
  slug: "ep10-ship",
  title: "VegaDūta Academy — Ep 10 · Ship it",
  subtitle: "Schedules, quick tasks and long-running work",
  next: "Series B — the deep-dive library",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Final episode: shipping. Everything we built so far answered when spoken to — now the newsroom works on its own. Scheduled tasks are prompts an agent runs by itself: this install already has a Morning briefing wired for every weekday at eight.",
      do: [["goto", "/scheduled"], ["tryClick", "text=Skip for now"], ["wait", 2500]],
    },
    {
      say: "One of twenty-five slots is taken — a real task with real next runs listed, not a mock. The result lands as a notification in the app; give it an email channel from episode five and the edition arrives in the inbox.",
      do: [["goto", "/scheduled"], ["highlight", "text=Morning briefing", 1800],
           ["wait", 1400]],
    },
    {
      say: "The fastest way in is the example gallery — morning briefing, inbox digest, industry news, weekly review, competitor watch. Each one is a prefilled form: the prompt, the agent, the days and the time. Pick one, adjust, create.",
      do: [["goto", "/scheduled"], ["click", "text=Inbox digest"], ["wait", 2000]],
    },
    {
      say: "For one-off heavy lifting there is Quick task — a single instruction an agent grinds on in the background while you do something else. Type the job, walk away, the result is waiting.",
      do: [["goto", "/quick-task"], ["wait", 2500]],
    },
    {
      say: "Long-running work still answers to the console — every scheduled run and background job shows up in the same threads, the same turn inspector, the same approval queues. Autonomous does not mean unwatched.",
      do: [["goto", "/assistant"], ["wait", 2500]],
    },
    {
      say: "That is the whole arc — build, connect, guard, grade, operate, ship. The Series-B library picks each screen apart one at a time when you need the detail. The newsroom is yours.",
      do: [["goto", "/home"], ["wait", 2800]],
    },
  ],
};
