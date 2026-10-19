import { ReceiptText, UserSearch } from "lucide-react";
import { makeNode, makeEdge, chainEdges, extractor, classifier } from "./graphHelpers";
import type { TemplateGraph } from "./graphHelpers";
import type { WorkflowTemplate } from "./types";

function resumeScreeningGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const read = makeNode("document_extractor", 360, 160, "Read the resume", {
    source: "",
    format: "auto",
    outputMode: "text",
  });
  const extract = extractor(640, 160, "Extract candidate profile", [
    { name: "name", type: "string", description: "The candidate's full name", required: true },
    { name: "email", type: "string", description: "Contact email", required: false },
    { name: "yearsExperience", type: "number", description: "Total years of relevant experience", required: false },
    { name: "skills", type: "array", description: "Key skills", required: false },
    { name: "summary", type: "string", description: "Two-sentence summary of the candidate", required: true },
  ]);
  const fit = classifier(
    920,
    160,
    "Shortlist or decline?",
    [
      {
        handle: "shortlist",
        label: "Shortlist",
        description: "Has clear, relevant skills and meets the experience bar.",
      },
      {
        handle: "decline",
        label: "Decline",
        description: "Does not meet the bar or the profile is too thin to judge.",
      },
    ],
    "decline",
    "Shortlist when the candidate has at least 3 years of relevant experience and clearly stated skills. Edit this rule to match your role."
  );
  const review = makeNode("approval", 1200, 60, "Recruiter review", {
    message: "Candidate shortlisted by the screen - confirm before reaching out.",
  });
  const outShort = makeNode("output", 1480, 60, "Record shortlist", { channel: "log" });
  const outDecline = makeNode("output", 1200, 260, "Record decline", { channel: "log" });
  return {
    nodes: [trigger, read, extract, fit, review, outShort, outDecline],
    edges: [
      ...chainEdges(trigger, read, extract, fit),
      makeEdge(fit.id, review.id, "shortlist"),
      makeEdge(fit.id, outDecline.id, "decline"),
      makeEdge(review.id, outShort.id),
    ],
  };
}

function overdueInvoicesGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const overdue = makeNode("filter", 360, 160, "Keep overdue invoices", {
    itemsField: "invoices",
    conditions: [{ field: "status", operator: "eq", value: "overdue" }],
    combinator: "and",
    ignoreCase: true,
  });
  const remind = makeNode("bulk_email", 640, 160, "Email each customer a reminder", {
    recipientsSource: "items",
    itemsField: "invoices",
    emailField: "email",
    subject: "Friendly reminder: an invoice on your account is overdue",
    body:
      "Hello,\n\nThis is a friendly reminder that an invoice on your account is now overdue. " +
      "Please reply to this email if you need a copy of the invoice or have already paid.\n\nThank you.",
    maxPerMinute: 30,
    maxRecipients: 100,
    skipIfReplied: true,
    includeUnsubscribe: true,
  });
  const out = makeNode("output", 920, 160, "Keep send report in run history", { channel: "log" });
  return { nodes: [trigger, overdue, remind, out], edges: chainEdges(trigger, overdue, remind, out) };
}

// Workflow templates: People & finance.
export const peopleAndFinanceTemplates: WorkflowTemplate[] = [
  {
    key: "resume-screening-workflow",
    category: "People & finance",
    icon: UserSearch,
    description:
      "Reads a resume (PDF, DOCX or text link in the payload), extracts name, skills and experience, classifies it " +
      "shortlist or decline, and holds every shortlist for a recruiter's approval. No agent needed.",
    triggerChannel: "WEBHOOK",
    buildGraph: resumeScreeningGraph,
  },
  {
    key: "overdue-invoice-reminders-workflow",
    category: "People & finance",
    icon: ReceiptText,
    description:
      'Takes a webhook payload with an "invoices" array, keeps only the overdue ones and emails each customer a ' +
      'reminder (one email per invoice, rate-limited, with an unsubscribe link). Each invoice needs an "email" field.',
    triggerChannel: "WEBHOOK",
    buildGraph: overdueInvoicesGraph,
  },
];
