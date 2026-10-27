export default {
  slug: "ep00-orientation",
  title: "VegaDūta Academy — Ep 0 · The Map",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Welcome to VegaDūta Academy. Over this series we will build a working AI newsroom — agents that research, draft, fact-check and publish a daily newspaper. Today, ten minutes on the map, so the console stops feeling like a cockpit and starts feeling like an editor's desk.",
      do: [["goto", "/home"], ["tryClick", "text=Skip for now"], ["wait", 2500]],
    },
    {
      say: "Everything starts at the Assistant. This is a conversation with the platform itself — ask it to build, run or explain anything, and it uses the same agents and tools you configure yourself.",
      do: [["goto", "/assistant"], ["wait", 2500]],
    },
    {
      say: "Agents are your staff. Each one has instructions, a model, a budget, and tools. For the newspaper we will create an Editor-in-Chief here — but remember this row on the left rail: Agents is where anything that thinks gets born.",
      do: [["goto", "/agents"], ["highlight", "nav", 1800]],
    },
    {
      say: "Workflows are the assembly line. A visual pipeline of steps — fetch, transform, call an agent, wait for a human — that runs on a schedule or a trigger. Our newspaper's production line lives here.",
      do: [["goto", "/workflows"], ["wait", 2200]],
    },
    {
      say: "Tools and integrations are how agents touch the outside world. The catalog lists everything from web search to a thousand SaaS apps through Composio. No glue code — enable one and every agent can use it.",
      do: [["goto", "/tools"], ["wait", 2500]],
    },
    {
      say: "Knowledge is the reference library. Upload documents — our style guide, past editions, source lists — and agents retrieve them by meaning, not by keyword.",
      do: [["goto", "/knowledge"], ["wait", 2200]],
    },
    {
      say: "Guardrails are the editor's red pen: policies that check every agent's output for things like unverified claims, personal data, or overspending. In a newsroom this is the difference between confidence and a lawsuit.",
      do: [["goto", "/admin/guardrails"], ["wait", 2200]],
    },
    {
      say: "Quality is where you grade the paper before it prints — golden examples, scored runs, and instant replay of any turn that went wrong. We will use it the day our headline writer starts drifting.",
      do: [["goto", "/agents/quality"], ["wait", 2200]],
    },
    {
      say: "And the operate screens — turns, usage, ROI — answer the two questions every owner asks: what happened, and what did it cost. Every agent decision leaves a signed, auditable trail.",
      do: [["goto", "/usage"], ["wait", 2200]],
    },
    {
      say: "That's the map: Build things, Connect them, Test and Monitor, then Administer. Next episode we hire our first journalist. See you there.",
      do: [["goto", "/home"], ["wait", 2500]],
    },
  ],
};
