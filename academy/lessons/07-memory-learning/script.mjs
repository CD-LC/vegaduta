export default {
  slug: "ep07-memory-learning",
  title: "VegaDūta Academy — Ep 7 · Memory & learning",
  subtitle: "What the newsroom remembers, and what it learns",
  next: "Ep 8 — Quality: grading the paper",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Episode seven: memory and learning — the difference between an agent that starts every conversation cold and one that knows the beat. Everything the platform remembers lives here, and everything on this page is yours to read, edit and delete.",
      do: [["goto", "/memory"], ["tryClick", "text=Skip for now"], ["wait", 2500]],
    },
    {
      say: "This is real state, not a demo: two notes written by hand, three facts the platform learned from your corrections, and a switchboard deciding which assistants may read them. Notes only reach chats where shared memory is on — right now none are.",
      do: [["goto", "/memory"], ["wait", 1500],
           ["highlight", "text=Your notes", 1600], ["highlight", "text=Learned facts", 1600]],
    },
    {
      say: "Turn shared memory on for an assistant and your notes start arriving in its conversations — the Ledger's house style, your deadline, which sources are approved — without you repeating yourself. Export and erase are one click when you want it gone.",
      do: [["goto", "/memory"], ["highlight", "text=Choose assistants", 1800], ["wait", 1400]],
    },
    {
      say: "Proof it works: I ask the Editor-in-Chief what the Fact-Checker must verify before anything publishes. It answers from the newsroom's stored memory — the six AM embargo, verified names and figures — rules we seeded once, recalled every time.",
      do: [["goto", "/agents?agentId=67eb3d14-ded1-4055-af0d-ea754945ca73"], ["wait", 4000],
           ["waitFor", "textarea[placeholder*='Send a test message']", 20000],
           ["fill", "textarea[placeholder*='Send a test message']", "What must the Fact-Checker verify before any story publishes? Answer in two short sentences."],
           ["keys", "Enter"], ["waitFor", "text=Run finished", 60000], ["wait", 2000], ["tryHighlight", "text=/embargo|verif|6:00|6 AM/i", 2000]],
    },
    {
      say: "Skills are the other kind of memory — packaged expertise an agent loads only when a task needs it. A story engine, a decision helper: each card opens the Playground with a ready-made prompt so you can try it on a real agent.",
      do: [["goto", "/skills"], ["wait", 2200], ["highlight", "text=story-engine", 1600]],
    },
    {
      say: "And the library is where learned skills land for review — when an agent figures out a better way to do a task, it proposes the improvement here as Pending review, and it does nothing until a human approves it. Import a .zip from elsewhere, or gate what your agents earned themselves.",
      do: [["goto", "/skills/library"], ["wait", 2500]],
    },
    {
      say: "Memory is what you tell it; learning is what it earns — and both stay readable, editable and reviewable by a human. Next episode: grading the paper — agent quality.",
      do: [["goto", "/home"], ["wait", 2500]],
    },
  ],
};
