export default {
  slug: "b11-edge",
  title: "VegaDūta Academy — Edge",
  subtitle: "On-device AI, when the story can't leave the machine",
  next: "b12 — Integrations & features",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Edge is the platform's on-device lane — models that run on the machine in front of you instead of a cloud endpoint. For a newsroom that's not a feature bullet, it's how you read a leaked document without it leaving your laptop.",
      do: [["goto", "/edge"], ["wait", 3000]],
    },
    {
      say: "The trade-off is the classic one: a smaller local model answers privately and for free; the hosted model is sharper but the bytes travel. Edge lets you route each kind of work to where it belongs — sensitive drafts local, heavy analysis hosted.",
      do: [["goto", "/edge"], ["wait", 1500], ["scroll", 350],
           ["tryHighlight", "text=/local|device|model|private/i", 2000]],
    },
    {
      say: "Our Editor's test console showed the split honestly — runs stamp 'Powered by Local model' or the hosted provider right under the answer. You always know which desk produced the copy.",
      do: [["goto", "/edge"], ["wait", 2500]],
    },
    {
      say: "Edge for what can't leave, hosted for what needs muscle — one workspace, explicit about which is which. Next: the integration settings and feature switches behind all of it.",
      do: [["goto", "/edge"], ["wait", 2500]],
    },
  ],
};
