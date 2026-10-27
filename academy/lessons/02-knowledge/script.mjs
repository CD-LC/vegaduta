export default {
  slug: "ep02-knowledge",
  title: "VegaDūta Academy — Ep 2 · Knowledge",
  subtitle: "Feeding the newsroom",
  next: "Ep 3 — Tools, MCP & SaaS connectors",
  voice: "en-US-ChristopherNeural",
  scenes: [
    {
      say: "Episode two: we feed the newsroom. Knowledge is the reference library — anything you upload here becomes searchable by meaning, so the Editor-in-Chief can quote the paper's rules instead of guessing.",
      do: [["goto", "/knowledge"], ["wait", 2500]],
    },
    {
      say: "Documents live in collections — each one is its own shelf with its own embedding model. I'm making one called Ledger Style Guide for our house rules, source archive and past editions.",
      do: [["goto", "/knowledge"], ["wait", 1500],
           ["click", "role=button[name='New collection']"], ["wait", 1200],
           ["fill", "#kb-new-collection-name", "Ledger Style Guide"], ["wait", 800],
           ["fill", "#kb-new-collection-description", "House style rules, source archive and past editions for The Ledger."], ["wait", 800],
           ["click", "role=button[name='Create']"], ["wait", 2000]],
    },
    {
      say: "Upload is one click per file, and the picker lists the formats this deployment accepts. Where the Docling converter is switched on, that list grows to PowerPoint decks, Excel sheets, CSVs, even TIFF and BMP scans.",
      do: [["goto", "/knowledge"], ["wait", 1500],
           ["highlight", "text=Choose a file to upload", 2400]],
    },
    {
      say: "Watch a file land and the pipeline strip tells the whole story: upload, extract the text, embed it into vectors, index it. When the badge flips to ready, every agent with access can find it.",
      do: [["goto", "/knowledge"], ["wait", 1500],
           ["highlight", "text=INGESTION PIPELINE", 2400], ["wait", 800]],
    },
    {
      say: "Before trusting a collection, test it. The retrieval box answers one honest question: which chunks would an agent actually see? Ask it like a reader would, not like a keyword search.",
      do: [["goto", "/knowledge"], ["wait", 1500],
           ["fill", "input[placeholder*='damaged product']", "house style rules"], ["wait", 800],
           ["click", "role=button[name='Go']"], ["wait", 3500]],
    },
    {
      say: "Retrieval quality is the tuning bench — how documents are split into chunks and which embedding model indexes them both change what search returns. This panel is how you measure whether a change actually helped.",
      do: [["goto", "/knowledge"], ["wait", 1500],
           ["highlight", "text=Retrieval quality", 2400], ["wait", 800]],
    },
    {
      say: "A collection only earns its keep once it's attached. Agents pick their collections in the workbench — wiring the Editor-in-Chief to the style guide means every draft starts on-rule.",
      do: [["goto", "/knowledge"], ["wait", 1500],
           ["highlight", "text=Agent workbench", 2200], ["wait", 800]],
    },
    {
      say: "The library is open: the style guide has a shelf, the search box proves agents will find it, and attaching is one checkbox away. Next episode we give the newsroom hands — tools, MCP servers and a thousand SaaS connectors.",
      do: [["goto", "/agents"], ["wait", 2500]],
    },
  ],
};
