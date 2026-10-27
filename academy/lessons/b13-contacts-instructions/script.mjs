export default {
  slug: "b13-contacts-instructions",
  title: "VegaDūta Academy — Contacts & instructions",
  subtitle: "Who reaches the newsroom, and standing orders",
  next: "b14 — Approvals deep-dive",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Contacts and routing is the newsroom's front door directory — every person who can reach your agents, which channel they come in on, and which agent answers them.",
      do: [["goto", "/admin/contacts"], ["wait", 2500]],
    },
    {
      say: "These are The Ledger's seeded sources: two WhatsApp contacts and an email stringer, each bound to the Senior Reporter — tips they send land in that agent's queue, not a shared pile.",
      do: [["goto", "/admin/contacts"], ["wait", 1500],
           ["tryHighlight", "text=/WhatsApp|Senior Reporter/i", 2000], ["scroll", 350]],
    },
    {
      say: "Bound versus unbound matters. A contact tied to an agent is a private line; anything unrouted falls to the inbox for a human to triage — nothing messages an agent silently.",
      do: [["goto", "/admin/contacts"], ["wait", 2000],
           ["tryHighlight", "text=/agent|routing/i", 1800]],
    },
    {
      say: "Input requests are the reverse direction — places an agent paused mid-run to ask a person something, waiting here until a human answers. In a newsroom: 'confirm this source name before I publish.'",
      do: [["goto", "/admin/input-requests"], ["wait", 2500]],
    },
    {
      say: "Contacts decide who reaches the desk; input requests decide when the desk reaches back. Next: the approval queue — where agent actions wait for a human signature.",
      do: [["goto", "/admin/approvals"], ["wait", 2500]],
    },
  ],
};
