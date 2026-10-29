export default {
  slug: "b08-skills",
  title: "VegaDūta Academy — Skills",
  subtitle: "Packaged expertise the agents load on demand",
  next: "b09 — Connect, plugins & add-ons",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Skills are packaged expertise — a playbook the agent loads only when the task calls for it. A headline-writing skill, an obituary format, an earnings-brief checklist: written once, loaded when relevant.",
      do: [["goto", "/skills"], ["wait", 2500]],
    },
    {
      say: "Each card opens a ready-made prompt so you can try the skill on a real agent before trusting it — audition before hire, like everything else on the desk.",
      do: [["goto", "/skills"], ["wait", 1500],
           ["tryHighlight", "text=/story|engine|skill|play/i", 2000], ["scroll", 350]],
    },
    {
      say: "The library is where learned skills land for review — when an agent figures out a better way to do a task, the improvement is proposed here as pending, and does nothing until a person approves it.",
      do: [["goto", "/skills/library"], ["wait", 3000],
           ["tryHighlight", "text=/pending|review|library/i", 2000]],
    },
    {
      say: "That's the trust model: expertise accumulates, but nothing self-installs. Next — connecting the workspace to the outside world.",
      do: [["goto", "/skills"], ["wait", 2500]],
    },
  ],
};
