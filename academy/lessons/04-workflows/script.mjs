export default {
  slug: "ep04-workflows",
  title: "VegaDūta Academy — Ep 4 · Workflows",
  subtitle: "The production line",
  next: "Ep 5 — Approvals & channels: the editor's gate",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Episode four: the production line. So far we have hired staff — an Editor-in-Chief agent, a style guide it can read, and tools it can call. A workflow is what turns that staff into a newspaper that comes out on time, every day, without anyone pushing a button.",
      do: [["goto", "/workflows"], ["tryClick", "text=Skip for now"], ["wait", 2500]],
    },
    {
      say: "The fastest way in is to describe the job in plain words. 'Every weekday at six, gather the overnight sources, draft the front page, fact-check it, wait for my sign-off, then publish.' Build draft turns that sentence into a canvas you can edit — and nothing runs until you activate it.",
      do: [["goto", "/workflows"], ["tryClick", "text=Skip for now"], ["wait", 1200],
           ["fill", "textarea[placeholder*='unpaid invoices']", "Every weekday at 6am, gather the overnight sources, draft the front page, fact-check it, wait for my sign-off, then publish the edition."],
           ["highlight", "button:has-text('Build draft')", 1800], ["wait", 1200]],
    },
    {
      say: "Here is a live example on the canvas — a weekly campaign line that is really the same shape as our newspaper. Read it left to right: a Monday trigger, a writer agent, a human gate, then a fan-out to each channel and one last check before it logs.",
      do: [["goto", "/workflows/b0db8dac-91d0-440c-9491-330ded15a84a"], ["tryClick", "text=Skip for now"], ["wait", 3200]],
    },
    {
      say: "Every pipeline starts with exactly one trigger — the thing that says go. This one fires on a weekly schedule; ours will fire every weekday morning, but a trigger can just as easily be a webhook or a message arriving on a channel.",
      do: [["goto", "/workflows/b0db8dac-91d0-440c-9491-330ded15a84a"], ["tryClick", "text=Skip for now"], ["wait", 1500],
           ["click", ".react-flow__node:has-text('Weekly Trigger')"], ["wait", 2400]],
    },
    {
      say: "The Agent step is where your staff plugs in. Pick any agent you have built — for the Ledger, the Editor-in-Chief drafts the front page right here — and the step can demand its own approval, retry on failure, or route errors down a fallback edge.",
      do: [["goto", "/workflows/b0db8dac-91d0-440c-9491-330ded15a84a"], ["tryClick", "text=Skip for now"], ["wait", 1500],
           ["click", ".react-flow__node:has-text('Content Generator')"], ["wait", 2400]],
    },
    {
      say: "And this is the most important node in the whole series: Human Approval. When a run reaches it, the run pauses and lands in the Approvals queue — the paper literally cannot print until a person says yes. That queue gets its own episode next.",
      do: [["goto", "/workflows/b0db8dac-91d0-440c-9491-330ded15a84a"], ["tryClick", "text=Skip for now"], ["wait", 1500],
           ["click", ".react-flow__node:has-text('Approve Content')"], ["wait", 2400]],
    },
    {
      say: "New steps come from the palette on the left — knowledge lookups, HTTP calls, conditions, transforms, fan-out. You wire the line by dragging from the small port on one node to the port on the next, the same way copy moves down the desk.",
      do: [["goto", "/workflows/b0db8dac-91d0-440c-9491-330ded15a84a"], ["tryClick", "text=Skip for now"], ["wait", 1500],
           ["hover", "button:has-text('Add node')"], ["wait", 900],
           ["hover", "button:has-text('Logic')"], ["wait", 1300],
           ["hover", "button:has-text('Data')"], ["wait", 1600]],
    },
    {
      say: "Before any of it runs for real, the validator walks the graph flagging dead ends and missing config — eleven warnings on this draft. Then Runs fires a live run, and the flight panel shows the work moving down the line, node by node.",
      do: [["goto", "/workflows/b0db8dac-91d0-440c-9491-330ded15a84a"], ["tryClick", "text=Skip for now"], ["wait", 1500],
           ["highlight", "button:has-text('warnings')", 2000],
           ["click", "button:has-text('Runs')"], ["wait", 3000]],
    },
    {
      say: "Every run leaves a flight recorder — each node timed, stamped and replayable, from the trigger to the last log line. When an edition goes out late, this is where you find the step that ate the morning.",
      do: [["goto", "/workflows/b0db8dac-91d0-440c-9491-330ded15a84a"], ["tryClick", "text=Skip for now"], ["wait", 1500],
           ["click", "button:has-text('Runs')"], ["wait", 3500]],
    },
    {
      say: "Drafts are safe — save early, save often, and activate only when the line is ready to print. Next episode: the human gate itself — the approvals queue, the inbox, and the channels that carry the finished paper to subscribers.",
      do: [["goto", "/workflows"], ["wait", 2500]],
    },
  ],
};
