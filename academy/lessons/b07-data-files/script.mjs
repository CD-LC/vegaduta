export default {
  slug: "b07-data-files",
  title: "VegaDūta Academy — Data, files & clips",
  subtitle: "The newsroom's filing cabinets",
  next: "b08 — Skills",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Data Tables are structured memory — rows the agents can query like a database instead of skimming prose. Subscriber lists, story trackers, rate cards: anything with columns belongs here.",
      do: [["goto", "/data-tables"], ["wait", 2500]],
    },
    {
      say: "Files is the shared drive — documents, attachments and generated artifacts the workspace can reach. It's where a report the agent produced at dawn sits waiting for the desk.",
      do: [["goto", "/files"], ["wait", 2500]],
    },
    {
      say: "Clip Studio is for what's worth keeping verbatim — captured snippets and assets, organized for reuse. A pull-quote archive, a screenshot bank, the clip file every old newsroom kept in a drawer.",
      do: [["goto", "/clips"], ["wait", 2500]],
    },
    {
      say: "Three cabinets, three shapes of memory: tables for rows, files for documents, clips for fragments. The agent that can find all three stops asking you for the same thing twice. Next: skills — expertise that loads on demand.",
      do: [["goto", "/data-tables"], ["wait", 2500]],
    },
  ],
};
