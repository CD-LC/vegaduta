export default {
  slug: "ep03-tools-mcp",
  title: "VegaDūta Academy — Ep 3 · Tools, MCP & connectors",
  subtitle: "Giving the newsroom hands",
  next: "Ep 4 — Workflows: the production line",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Episode three: the newsroom gets hands. Tools are what let an agent actually do things — search the live web, read a file, post the finished edition. The Integrations page is the entire catalog.",
      do: [["goto", "/tools"], ["wait", 2500]],
    },
    {
      say: "The Servers tab lists everything available: built-ins like Knowledge base search that are always on, and add-on servers you enable yourself. Each card says what it does, what it needs, and whether it's verified.",
      do: [["goto", "/tools"], ["wait", 1500],
           ["highlight", "input[placeholder*='tools & servers']", 2000], ["wait", 800]],
    },
    {
      say: "First hire for our reporters: Brave Search, a verified server that gives agents current information beyond their training data. Set up means one click and an API key — after that, every agent that should see the live web can.",
      do: [["goto", "/tools"], ["wait", 1500],
           ["fill", "input[placeholder*='tools & servers']", "brave"], ["wait", 1800],
           ["highlight", "text=brave-search", 2200]],
    },
    {
      say: "Not in the list? Register a custom server — a name, a description, and a command or URL. stdio, SSE and HTTP transports are all supported, so a private newsroom API becomes a tool in minutes.",
      do: [["goto", "/tools"], ["wait", 1500],
           ["click", "text=Add MCP server"], ["wait", 1500],
           ["fill", "#mcp-server-name-input", "ledger-wire-service"], ["wait", 1000],
           ["highlight", "input[placeholder*='command']", 1800]],
    },
    {
      say: "The registry tab is the public directory — community servers for GitHub, Postgres, Slack, Puppeteer and more, filterable by how they run. Finding one here is the difference between writing an integration and checking a box.",
      do: [["goto", "/tools"], ["wait", 1500],
           ["click", "role=tab[name='Browse registry']"], ["wait", 2800]],
    },
    {
      say: "The one to know is Composio — a hosted gateway to more than a thousand SaaS apps, from Gmail and Slack to Notion and HubSpot. One connection, and the per-app sign-in plumbing is handled for you.",
      do: [["goto", "/tools"], ["wait", 1500],
           ["click", "role=tab[name='Browse registry']"], ["wait", 2200],
           ["fill", "input[placeholder*='MCP registry']", "composio"], ["wait", 2200],
           ["highlight", "text=/1000\\+ apps/", 2200]],
    },
    {
      say: "Back in the catalog, composio-connect is that same power packaged for this console — for the Ledger it's the publishing desk, the connector that can post the edition where readers are. Set up is waiting whenever we are.",
      do: [["goto", "/tools"], ["wait", 1500],
           ["fill", "input[placeholder*='tools & servers']", "composio"], ["wait", 2000],
           ["highlight", "text=composio-connect", 2400]],
    },
    {
      say: "The Connect page is the friendlier doorway — channels like Telegram, WhatsApp, email and Google, plus ready-made agents. This is where the paper eventually meets its readers.",
      do: [["goto", "/connect"], ["wait", 2000],
           ["highlight", "text=Connect Telegram", 1800], ["wait", 800]],
    },
    {
      say: "Agents plus tools is a newsroom that can move: research on the live web, publishing through Composio, every connection governed the same way. Next episode, we build the production line.",
      do: [["goto", "/tools"], ["wait", 2500]],
    },
  ],
};
