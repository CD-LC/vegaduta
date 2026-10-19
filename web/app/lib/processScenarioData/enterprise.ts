// Process Theater scenarios for the "Enterprise" audience. Enterprise processes: finance close, insurance claims and mainframe operations.
// Assembled, in display order, by ../processScenarios.ts (see its honesty rules).
import { designPrompt, type ProcessScenario } from "./types";

export const invoiceScenario: ProcessScenario = {
  id: "invoice",
  audience: "Enterprise",
  title: "Invoice to payment, end to end",
  pitch:
    "Hundreds of supplier invoices a week: read, matched to purchase orders, exceptions chased, payment released only after sign-off.",
  span: "Runs for days per invoice",
  needs: "Email inbox, your ERP or accounting API",
  steps: [
    {
      node: "trigger",
      label: "Invoice arrives by email",
      tech: "Email channel trigger starts a background run per message",
      clock: "T+0s",
    },
    {
      node: "agent",
      label: "Read the PDF",
      tech: "Vision-capable model extracts supplier, lines, totals into JSON",
      clock: "T+8s",
    },
    {
      node: "http",
      label: "Fetch the purchase order",
      tech: "HTTP node calls your ERP API with the PO number",
      clock: "T+10s",
    },
    {
      node: "code",
      label: "Three-way match",
      tech: "Sandboxed code node compares invoice, PO and receipt line by line",
      clock: "T+11s",
    },
    {
      node: "condition",
      label: "Mismatch?",
      tech: "Condition node branches: clean match vs. exception",
      clock: "T+11s",
    },
    {
      node: "agent",
      label: "Chase the supplier",
      tech: "Agent drafts and sends the query, then the run parks until a reply",
      clock: "T+1 day",
      waits: true,
    },
    {
      node: "approval",
      label: "Finance sign-off",
      tech: "Approval node holds the run; nothing is paid without a named approver",
      clock: "T+2 days",
      waits: true,
    },
    {
      node: "output",
      label: "Post and notify",
      tech: "Posts to the ERP, writes the audit trail, messages the requester",
      clock: "T+2 days",
    },
  ],
  agents: [
    {
      name: "Invoice Reader",
      role: "Reads each invoice PDF or photo into structured fields — supplier, lines, totals, tax.",
      tools: ["Vision model", "Document store"],
    },
    {
      name: "Match & Exceptions",
      role: "Three-way match against PO and receipt; explains every mismatch.",
      tools: ["ERP / accounting API", "Code sandbox"],
    },
    {
      name: "Supplier Liaison",
      role: "Drafts and sends the mismatch query, then parks the run until a reply lands.",
      tools: ["Email inbox"],
    },
    {
      name: "Payment Poster",
      role: "Posts approved invoices to the ERP and writes the audit trail.",
      tools: ["ERP / accounting API"],
    },
  ],
  connectors: [
    { name: "Email inbox", kind: "channel", detail: "Inbound channel — each supplier invoice email starts a run." },
    {
      name: "ERP / accounting API",
      kind: "http",
      detail: "HTTP Request node against your ERP — fetch PO, post payment.",
    },
    { name: "Vision model", kind: "builtin", detail: "Document reading is built in — PDFs and photos become JSON." },
    { name: "Code sandbox", kind: "builtin", detail: "Sandboxed Python for the line-by-line three-way match." },
    {
      name: "Document store",
      kind: "mcp",
      detail: "Attach a storage MCP server (S3, Drive, SharePoint) after sign-in.",
    },
  ],
  prompt: designPrompt(
    "supplier invoices arrive by email; extract them, match against purchase orders, chase suppliers on mismatches, get finance approval, then post for payment"
  ),
};

export const claimsScenario: ProcessScenario = {
  id: "claims",
  audience: "Enterprise",
  title: "Claims processing with evidence",
  pitch:
    "Every claim read, checked against policy, missing evidence requested, and only the hard ones sent to a human adjuster.",
  span: "Days to weeks per claim",
  needs: "Claims system API, document storage, email or WhatsApp",
  steps: [
    {
      node: "trigger",
      label: "Claim submitted",
      tech: "Workflow run API call from your claims system, or an inbound channel message",
      clock: "T+0s",
    },
    {
      node: "agent",
      label: "Read the documents",
      tech: "Vision model reads forms, photos and bills into structured fields",
      clock: "T+15s",
    },
    {
      node: "knowledge",
      label: "Check policy wording",
      tech: "Retrieval over the policy documents, with citations",
      clock: "T+18s",
    },
    {
      node: "condition",
      label: "Evidence complete?",
      tech: "Condition node: complete, incomplete, or suspicious",
      clock: "T+18s",
    },
    {
      node: "agent",
      label: "Request what is missing",
      tech: "Messages the claimant on their channel; run parks until they reply",
      clock: "T+3 days",
      waits: true,
    },
    {
      node: "approval",
      label: "Adjuster decides",
      tech: "Approval node with the agent's recommendation and its sources",
      clock: "T+4 days",
      waits: true,
    },
    {
      node: "output",
      label: "Decision and audit trail",
      tech: "Writes the decision back with every step recorded",
      clock: "T+4 days",
    },
  ],
  agents: [
    {
      name: "Document Intake",
      role: "Reads forms, photos and bills into structured claim fields.",
      tools: ["Vision model", "Document store"],
    },
    {
      name: "Policy Checker",
      role: "Checks the claim against policy wording, with citations.",
      tools: ["Policy wording"],
    },
    {
      name: "Evidence Chaser",
      role: "Requests what's missing on the claimant's own channel and waits.",
      tools: ["Email / WhatsApp"],
    },
    {
      name: "Adjuster Briefing",
      role: "Hands the human a recommendation plus every source it used.",
      tools: ["Claims system API"],
    },
  ],
  connectors: [
    {
      name: "Claims system API",
      kind: "custom",
      detail: "A small MCP tool you write: get_claim(id), post_decision(...).",
    },
    { name: "Document store", kind: "mcp", detail: "Attach a storage MCP server for claim evidence files." },
    { name: "Email / WhatsApp", kind: "channel", detail: "Evidence requests go out on the claimant's channel." },
    { name: "Vision model", kind: "builtin", detail: "Reads scanned forms, photos and bills — built in." },
    { name: "Policy wording", kind: "builtin", detail: "Knowledge retrieval over your policy documents." },
  ],
  prompt: designPrompt(
    "an insurance or medical claim is submitted; read the documents, check policy wording with citations, request missing evidence from the claimant, route to a human adjuster for decision, and record an audit trail"
  ),
};

export const mainframeScenario: ProcessScenario = {
  id: "mainframe",
  audience: "Enterprise",
  title: "Mainframe batch abend, before the branches open",
  pitch:
    "A nightly job dies at 02:40 with an S0C7. Agents read the SYSOUT, find the bad record, and the on-call engineer wakes up to a fix and a rerun plan, not a pager.",
  span: "Minutes, inside the batch window",
  needs:
    "Job scheduler that can call a webhook (Control-M, OPC/TWS, CA7), SYSOUT or SDSF export, your runbooks, Teams or Slack",
  steps: [
    {
      node: "trigger",
      label: "Scheduler posts the abend",
      tech: "Control-M or OPC calls the workflow run API with job, step and the SYSOUT excerpt",
      clock: "T+0s",
    },
    {
      node: "agent",
      label: "Decode the abend",
      tech: "Agent reads S0C7 / SB37 / SQLCODE -911 and names the failing field, dataset or lock",
      clock: "T+5s",
    },
    {
      node: "knowledge",
      label: "Check the runbook",
      tech: "Retrieval over your job dependency charts, cut-offs and approved workarounds",
      clock: "T+8s",
    },
    {
      node: "parallel",
      label: "Investigate in parallel",
      tech: "Branches: data agent finds the bad record, JCL agent checks SPACE and STEPLIB, downstream agent lists what is now blocked",
      clock: "T+10s",
    },
    {
      node: "condition",
      label: "Already posted?",
      tech: "Condition node: did the failed step commit to DB2 or write the GL file? Decides restart point",
      clock: "T+40s",
    },
    {
      node: "approval",
      label: "Operator approves the rerun",
      tech: "Approval node: a human authorises the rerun from step N with the listed datasets deleted first",
      clock: "T+2 min",
      waits: true,
    },
    {
      node: "output",
      label: "Post the fix and the plan",
      tech: "Cause, fix, rerun steps and the business-open risk land in the on-call channel",
      clock: "T+3 min",
    },
  ],
  agents: [
    {
      name: "Abend Decoder",
      role: "Reads the abend code and SYSOUT and names the probable cause.",
      tools: ["Scheduler webhook"],
    },
    {
      name: "Bad-Record Finder",
      role: "Locates the uninitialised COMP-3 or invalid date in the input file.",
      tools: ["Dataset export"],
    },
    {
      name: "JCL & Space Checker",
      role: "Checks SPACE, volume counts, STEPLIB and GDG generations.",
      tools: ["Dataset export", "Runbooks"],
    },
    {
      name: "Downstream Mapper",
      role: "Lists which jobs are now blocked and the time left before business opens.",
      tools: ["Runbooks"],
    },
  ],
  connectors: [
    {
      name: "Scheduler webhook",
      kind: "http",
      detail: "Control-M, OPC/TWS or CA7 calls the workflow run API on failure; the payload is the run input.",
    },
    {
      name: "Dataset export",
      kind: "custom",
      detail: "A small MCP tool you write: read_sysout(job), sample_dataset(dsn, n) - read-only.",
    },
    {
      name: "Runbooks",
      kind: "builtin",
      detail: "Upload job dependency charts, cut-off times and approved workarounds; retrieval does the rest.",
    },
    {
      name: "Teams / Slack",
      kind: "channel",
      detail: "The triage and the approval request land in the on-call channel.",
    },
  ],
  prompt: designPrompt(
    "a mainframe batch job abends overnight; decode the abend code from the SYSOUT, check the runbook, investigate the bad data, the JCL and the downstream impact in parallel, decide whether the step already posted, require operator approval for the rerun, and post the fix and rerun plan to the on-call channel"
  ),
};
