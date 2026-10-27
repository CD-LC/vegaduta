export default {
  slug: "ep05-channels-inbox",
  title: "VegaDūta Academy — Ep 5 · Channels, contacts & the inbox",
  subtitle: "Where the newsroom meets its readers",
  next: "Ep 6 — Guardrails: the editor's red pen",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Episode five: the newspaper goes live where its readers already are. Every card here is a real door into the newsroom — WhatsApp, Telegram, email, voice. We are going to connect two for real: WhatsApp by QR, and the reader-tips mailbox over IMAP.",
      do: [["goto", "/admin/channels"], ["tryClick", "text=Skip for now"], ["wait", 2200],
           ["highlight", "text=WhatsApp QR", 1600], ["highlight", "text=Email - IMAP", 1600]],
    },
    {
      say: "WhatsApp first. On a personal install the business API is overkill, so this card links your own WhatsApp as a companion device — one scan and the newsroom can read and reply on your number.",
      do: [["goto", "/admin/channels"], ["wait", 2000],
           ["click", "text=WhatsApp QR"], ["wait", 1600],
           ["highlight", "text=/Link a device|link device/", 1800]],
    },
    {
      say: "A channel without routing answers no one, so before we link anything we give inbound conversations a destination — the Assistant for now. Pick it and save; every reader who writes in now has somewhere to land.",
      do: [["goto", "/admin/contacts"], ["wait", 1800],
           ["click", "text=choose an agent"], ["wait", 900],
           ["click", "role=option[name=\"Assistant\"]"], ["wait", 900],
           ["click", "text=Save"], ["wait", 1600]],
    },
    {
      say: "Same flow for email. The IMAP card opens with host, username and password — type the mailbox details and the app password goes into the secrets vault, never into the page or a chat log. For the Ledger this is readers@ our domain.",
      do: [["goto", "/admin/channels"], ["wait", 2000],
           ["click", "text=Email - IMAP"], ["wait", 1600],
           ["highlight", "input[type=password]", 1600]],
    },
    {
      say: "Who answers a stranger is decided here, on contacts. Review each new sender is the pairing gate: a new number writes in, gets one reply with a pairing code, and waits in a queue until a human approves it — the agent never answers someone you have not vetted.",
      do: [["goto", "/admin/contacts"], ["wait", 2200],
           ["highlight", "text=Review each new sender", 2000]],
    },
    {
      say: "Choose the agent up top — Assistant — and the list beneath is its address book: every approved sender, what channel they came in on, and who they route to. This is the newspaper's reader list.",
      do: [["goto", "/admin/contacts"], ["wait", 1500],
           ["click", "text=choose an agent"], ["wait", 900],
           ["click", "role=option[name=\"Assistant\"]"], ["wait", 1600]],
    },
    {
      say: "Everything that arrives lands in the shared inbox, triaged by what it needs. Pending pairing is the unvetted queue — those are the readers who just got a pairing code and are waiting on an approval from contacts.",
      do: [["goto", "/admin/inbox"], ["wait", 2000],
           ["click", "text=Pending pairing"], ["wait", 1600]],
    },
    {
      say: "Waiting for a human is the escalation queue — the agent decided it should not answer alone. Held conversations are parked mid-review, and agent-handled is the machine working through the routine mail on its own.",
      do: [["goto", "/admin/inbox"], ["wait", 1200],
           ["click", "text=Waiting for a human"], ["wait", 1400],
           ["click", "text=Human-held"], ["wait", 1400]],
    },
    {
      say: "Open any conversation and the right pane is the desk editor's controls — the full transcript, reply-as-a-human to take over, re-route to a different agent, or end the pairing entirely. The human is never more than one click away.",
      do: [["goto", "/admin/inbox"], ["wait", 2200]],
    },
    {
      say: "That is the loop end to end: a channel carries readers in, routing and pairing decide who answers, and the inbox keeps a human on watch. Next episode — the red pen: guardrails that check everything before it prints.",
      do: [["goto", "/home"], ["wait", 2500]],
    },
  ],
};
