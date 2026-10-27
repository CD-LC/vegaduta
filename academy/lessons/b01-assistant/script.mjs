export default {
  slug: "b01-assistant",
  title: "VegaDūta Academy — The Assistant",
  subtitle: "Handing work to the newsroom's duty desk",
  next: "b02 — Agent templates & learning",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "The Assistant is the page you'll live on — one box where you ask a question, hand over a task, or point at anything in the workspace. Everything on The Ledger's desk starts here.",
      do: [["goto", "/assistant"], ["wait", 2500]],
    },
    {
      say: "Watch a real ask. We tell it what tonight's edition needs — and it runs against our actual agents and knowledge, not a canned demo.",
      do: [["goto", "/assistant"], ["wait", 2500],
           ["fill", "textarea[placeholder*='Ask or tell me']", "Summarize what the Ledger newsroom has set up so far: which agents exist and what the morning schedule does. Two sentences."],
           ["keys", "Enter"], ["wait", 12000]],
    },
    {
      say: "While it works, the rail shows the run as a step-by-step job — you can watch it think, call tools and draft. That's the difference between a chatbot and an agent you can audit.",
      do: [["goto", "/assistant"], ["wait", 3000],
           ["tryHighlight", "text=/step|run|working|started/i", 2000], ["wait", 3000]],
    },
    {
      say: "Something longer than a question? Quick task hands a single job to the autonomous agent — describe the outcome, walk away.",
      do: [["goto", "/quick-task"], ["wait", 2500],
           ["fill", "textarea[placeholder*='Describe the task']", "Draft three headline options for tomorrow's markets brief in the Ledger's house style."],
           ["wait", 1500]],
    },
    {
      say: "And Jobs is where background work queues and finishes — every task above lands here with its status and output. Nothing runs silently.",
      do: [["goto", "/jobs"], ["wait", 2500]],
    },
    {
      say: "One box, three speeds: ask it now, task it for later, or queue it overnight. That's the assistant in a newsroom — next, the templates that start agents from proven patterns.",
      do: [["goto", "/assistant"], ["wait", 2500]],
    },
  ],
};
