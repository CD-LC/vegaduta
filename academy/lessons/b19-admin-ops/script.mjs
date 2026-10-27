export default {
  slug: "b19-admin-ops",
  title: "VegaDūta Academy — Admin operations",
  subtitle: "The floor that keeps the newsroom running",
  next: "b20 — Providers",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "The Admin control panel is the machine-room door — system health, edition capabilities, and the operational dials, all in one place.",
      do: [["goto", "/admin"], ["wait", 2500]],
    },
    {
      say: "Team access is who holds keys to the desk — invite a copy editor, revoke a departed stringer, and every role maps to what that person can actually touch.",
      do: [["goto", "/admin/access"], ["wait", 2500]],
    },
    {
      say: "Issues is the platform's own incident board — problems the system noticed, surfaced for a human instead of failing quietly. A desk that reports on others should expect the same honesty from its tools.",
      do: [["goto", "/admin/issues"], ["wait", 2500]],
    },
    {
      say: "Maintenance is the operator panel — retention jobs, purges and housekeeping with their schedules visible. The unglamorous work that keeps ten thousand turns searchable.",
      do: [["goto", "/admin/maintenance"], ["wait", 3000]],
    },
    {
      say: "Access for the people, issues for the surprises, maintenance for the long haul — operations as a visible surface, not a prayer. Next: the model providers powering all of it.",
      do: [["goto", "/admin"], ["wait", 2500]],
    },
  ],
};
