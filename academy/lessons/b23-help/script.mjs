export default {
  slug: "b23-help",
  title: "VegaDūta Academy — Help & learning",
  subtitle: "The platform that explains itself",
  next: "Back to ep00 — Orientation",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "The help surface is built for exactly the problem you started with — too many controls, nowhere to start. Usage Guide is the manual, written in the same newsroom language as these videos.",
      do: [["goto", "/help"], ["wait", 2500]],
    },
    {
      say: "Docs is the deep shelf — every topic these episodes cover, written long-form for when a ninety-second video isn't enough.",
      do: [["goto", "/help/docs"], ["wait", 2500]],
    },
    {
      say: "'Where do I control...?' is the index that actually answers the question users type — search 'budget' or 'voice' and it tells you the exact page and field. When the console feels like too many controls, start here.",
      do: [["goto", "/help/controls"], ["wait", 2500],
           ["tryHighlight", "css=input[type='search'], input[placeholder*='earch']", 2000]],
    },
    {
      say: "And the glossary translates the agentic jargon to plain terms — turn, guardrail, MCP, journal — the vocabulary every one of these videos used.",
      do: [["goto", "/glossary"], ["wait", 2500]],
    },
    {
      say: "That's the full tour of a personal install — every control shown working on a real newsroom, and every page pointing back here when you forget. Welcome to The Ledger.",
      do: [["goto", "/home"], ["wait", 2500]],
    },
  ],
};
