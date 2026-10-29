export default {
  slug: "b17-audit",
  title: "VegaDūta Academy — Audit trail",
  subtitle: "Every decision leaves a record",
  next: "b18 — Usage & data usage",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "The audit log is the newsroom's permanent record — every setting change, every approval decision, every sign-in, stamped with who, what and when.",
      do: [["goto", "/admin/audit"], ["wait", 2500]],
    },
    {
      say: "And it's already telling today's story. The runtime override the desk made this morning — finish cut-off replies switched off — is here, with the reason attached. So is the approval we denied on camera.",
      do: [["goto", "/admin/audit"], ["wait", 1500],
           ["tryHighlight", "text=/chat.finish|settings|override/i", 2200], ["scroll", 350]],
    },
    {
      say: "Filter narrows the paper trail — by actor, by action type, by time. When a reader disputes what the agent printed, this is where you prove exactly which run produced it.",
      do: [["goto", "/admin/audit"], ["wait", 1500],
           ["tryHighlight", "text=/filter|actor|action/i", 2000]],
    },
    {
      say: "The live tail is the same log streaming in real time — watch a turn land as it happens. For a desk running overnight editions, this is the window into the machine room.",
      do: [["goto", "/admin/audit-tail"], ["wait", 3500]],
    },
    {
      say: "Audit is not surveillance for its own sake — it's what makes autonomous agents trustable: if it can't be replayed and explained, it shouldn't have run. Next: usage and what it costs.",
      do: [["goto", "/admin/audit"], ["wait", 2500]],
    },
  ],
};
