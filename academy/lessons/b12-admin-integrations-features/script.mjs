export default {
  slug: "b12-admin-integrations-features",
  title: "VegaDūta Academy — Integrations & features",
  subtitle: "The admin wiring closet",
  next: "b13 — Contacts & instructions",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Admin integrations is where workspace-level connections are configured — the Google Workspace account, the org-wide services every agent inherits. Per-user connects live elsewhere; this is the shared plumbing.",
      do: [["goto", "/admin/integrations"], ["wait", 3000]],
    },
    {
      say: "For The Ledger, one Google Workspace connection here feeds the whole desk — docs, mail and calendar under a single audited identity instead of five separate logins.",
      do: [["goto", "/admin/integrations"], ["wait", 1500],
           ["tryHighlight", "text=/Google|Workspace|connect/i", 2000]],
    },
    {
      say: "Features is the capability switchboard — what's enabled on this install, edition by edition. It answers 'why can't I see that page' honestly: the switch is off, or the edition doesn't carry it.",
      do: [["goto", "/features"], ["wait", 2500]],
    },
    {
      say: "Know where the plumbing lives and half the console stops being mysterious. Next: the people side — contacts, routing and standing instructions.",
      do: [["goto", "/admin/integrations"], ["wait", 2500]],
    },
  ],
};
