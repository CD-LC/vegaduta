export default {
  slug: "b09-connect-addons",
  title: "VegaDūta Academy — Connect, plugins & add-ons",
  subtitle: "Where the newsroom meets the outside world",
  next: "b10 — Credentials & secrets",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Connect is the wiring closet — the external accounts your agents can actually touch: email, calendars, drives. Each card is a real integration with its own consent, not a checkbox on a list.",
      do: [["goto", "/connect"], ["wait", 2500]],
    },
    {
      say: "For The Ledger: the tips inbox, the archive drive, the calendar the morning run schedules against. Connected means the agent can read it — under the same permissions and audit as a person.",
      do: [["goto", "/connect"], ["wait", 1500], ["scroll", 400],
           ["tryHighlight", "text=/connect|google|workspace/i", 2000]],
    },
    {
      say: "Plugins put VegaDūta where you already work — in your editor, your browser, your phone. The agent follows you to the story instead of waiting at a desk.",
      do: [["goto", "/plugins"], ["wait", 2500]],
    },
    {
      say: "Add-ons are optional capabilities you bolt on — extra packs for specific jobs. Same rule as the rest of the desk: added deliberately, listed openly, removable.",
      do: [["goto", "/add-ons"], ["wait", 2500]],
    },
    {
      say: "Connect for accounts, plugins for places, add-ons for powers — all of it visible and revocable here. Next: the keys that make connections work — credentials and secrets.",
      do: [["goto", "/connect"], ["wait", 2500]],
    },
  ],
};
