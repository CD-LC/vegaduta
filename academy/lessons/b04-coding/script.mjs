export default {
  slug: "b04-coding",
  title: "VegaDūta Academy — Coding & code intelligence",
  subtitle: "An agent that can read the codebase, not just chat about it",
  next: "b05 — Research & build modes",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Coding tasks hand the agent real repository work — describe the change, and it operates on the actual code with the same approvals and audit as everything else on the desk.",
      do: [["goto", "/coding"], ["wait", 2500]],
    },
    {
      say: "Local code is the bridge to what's on this machine — the agent can look at your workspace files instead of you pasting diffs into a chat box.",
      do: [["goto", "/code"], ["wait", 2500]],
    },
    {
      say: "Code Intelligence is what makes that safe to trust — the repo indexed and mapped, so the agent navigates symbols and dependencies instead of guessing from file contents.",
      do: [["goto", "/code-intel"], ["wait", 3000]],
    },
    {
      say: "For The Ledger this is the tooling desk: the scraper fix, the template tweak, the pipeline glue — scoped, reviewed, and journaled like every other agent act. Next: research mode, the long-form desk.",
      do: [["goto", "/code-intel"], ["wait", 2500]],
    },
  ],
};
