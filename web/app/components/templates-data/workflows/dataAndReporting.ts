import { ClipboardCheck, FileBarChart, FileSearch, Newspaper } from "lucide-react";
import { makeNode, chainEdges, extractor } from "./graphHelpers";
import type { TemplateGraph } from "./graphHelpers";
import type { WorkflowTemplate } from "./types";

function meetingNotesGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const summary = makeNode("agent", 360, 160, "Meeting summariser");
  const extract = extractor(640, 160, "Pull out decisions & action items", [
    { name: "summary", type: "string", description: "Three-sentence summary", required: true },
    { name: "decisions", type: "array", description: "Decisions that were made", required: false },
    {
      name: "actionItems",
      type: "array",
      description: "Action items, each with its owner and due date if stated",
      required: false,
    },
  ]);
  const out = makeNode("output", 920, 160, "Keep structured notes", { channel: "log" });
  return { nodes: [trigger, summary, extract, out], edges: chainEdges(trigger, summary, extract, out) };
}

function documentIntakeGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const read = makeNode("document_extractor", 360, 160, "Read the document", {
    source: "",
    format: "auto",
    outputMode: "text",
  });
  const extract = extractor(640, 160, "Extract key fields", [
    { name: "parties", type: "array", description: "Named people or companies in the document", required: false },
    { name: "documentDate", type: "string", description: "The document's date, as written", required: false },
    { name: "amount", type: "string", description: "Any headline amount, with currency", required: false },
    { name: "summary", type: "string", description: "Two-sentence summary", required: true },
  ]);
  const review = makeNode("approval", 920, 160, "Review extracted fields", {
    message: "Check the extracted fields against the document before they are filed.",
  });
  const out = makeNode("output", 1200, 160, "Save extracted fields", {
    channel: "file",
    fileType: "text",
    fileName: "extracted-fields.txt",
  });
  return { nodes: [trigger, read, extract, review, out], edges: chainEdges(trigger, read, extract, review, out) };
}

function apiPdfReportGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const fetch = makeNode("http", 360, 160, "Fetch the data", { method: "GET", url: "" });
  const totals = makeNode("aggregate", 640, 160, "Count & total", {
    itemsField: "",
    groupBy: "",
    aggregations: [
      { operation: "count", field: "", as: "rows" },
      { operation: "sum", field: "amount", as: "total" },
    ],
  });
  const write = makeNode("agent", 920, 160, "Report writer");
  const out = makeNode("output", 1200, 160, "Save PDF report", {
    channel: "file",
    fileType: "pdf",
    fileName: "report.pdf",
  });
  return { nodes: [trigger, fetch, totals, write, out], edges: chainEdges(trigger, fetch, totals, write, out) };
}

function topItemsDigestGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const fetch = makeNode("http", 360, 160, "Fetch the list", { method: "GET", url: "" });
  const sort = makeNode("sort", 640, 160, "Sort best first", {
    itemsField: "",
    sortKeys: [{ field: "score", direction: "desc", type: "number" }],
    caseSensitive: false,
  });
  const top = makeNode("limit", 920, 160, "Keep the top 10", { itemsField: "", count: 10, mode: "first" });
  const write = makeNode("agent", 1200, 160, "Digest writer");
  const out = makeNode("output", 1480, 160, "Keep digest in run history", { channel: "log" });
  return { nodes: [trigger, fetch, sort, top, write, out], edges: chainEdges(trigger, fetch, sort, top, write, out) };
}

// Workflow templates: Data & reporting.
export const dataAndReportingTemplates: WorkflowTemplate[] = [
  {
    key: "meeting-notes-action-items-workflow",
    category: "Data & reporting",
    icon: ClipboardCheck,
    description:
      "Summarises a meeting transcript, then pulls out decisions, action items and owners as structured data you " +
      "can send anywhere. Assign the summariser agent on the canvas.",
    triggerChannel: "WEBHOOK",
    buildGraph: meetingNotesGraph,
  },
  {
    key: "document-intake-extraction-workflow",
    category: "Data & reporting",
    icon: FileSearch,
    description:
      'Reads an uploaded file (payload with a "url"), pulls out the parties, date, amount and a summary as ' +
      "structured fields, and holds them for review before they are filed. No agent needed.",
    triggerChannel: "WEBHOOK",
    buildGraph: documentIntakeGraph,
  },
  {
    key: "api-data-pdf-report-workflow",
    category: "Data & reporting",
    icon: FileBarChart,
    description:
      "Fetches a JSON list from your API, totals and counts it, has an agent write the narrative and saves a PDF " +
      "report you can download from the run. Point the HTTP node's URL (and the aggregate field) at your data and " +
      "assign the writer agent on the canvas.",
    triggerChannel: "WEBHOOK",
    buildGraph: apiPdfReportGraph,
  },
  {
    key: "top-items-digest-workflow",
    category: "Data & reporting",
    icon: Newspaper,
    description:
      "Fetches a list from an API, sorts it, keeps the top 10 and has an agent write a short digest. Turn on a " +
      "daily or weekly schedule in the trigger settings. Point the HTTP node's URL and the sort field at your data " +
      "and assign the digest agent.",
    triggerChannel: "WEBHOOK",
    buildGraph: topItemsDigestGraph,
  },
];
