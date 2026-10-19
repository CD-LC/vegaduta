import { Mail, Share2, Target } from "lucide-react";
import { makeNode, makeEdge, chainEdges, extractor, classifier } from "./graphHelpers";
import type { TemplateGraph } from "./graphHelpers";
import type { WorkflowTemplate } from "./types";

function leadCaptureCrmGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const extract = extractor(360, 160, "Extract lead details", [
    { name: "name", type: "string", description: "The lead's full name", required: false },
    { name: "email", type: "string", description: "The lead's email address", required: true },
    { name: "phone", type: "string", description: "Phone number, if given", required: false },
    { name: "company", type: "string", description: "Company or organisation", required: false },
    { name: "need", type: "string", description: "What the lead wants, in one sentence", required: false },
  ]);
  const score = classifier(
    640,
    160,
    "Hot or nurture?",
    [
      {
        handle: "hot",
        label: "Hot",
        description: "Ready to buy soon: has a budget or deadline, or asks for a quote, demo or call.",
      },
      {
        handle: "nurture",
        label: "Nurture",
        description: "Early stage or just researching: no budget, deadline or clear ask yet.",
      },
    ],
    "nurture"
  );
  const crmHot = makeNode("crm_lead", 920, 60, "Save as qualified lead", {
    action: "upsert",
    email: "{{ $json.email }}",
    name: "{{ $json.name }}",
    phone: "{{ $json.phone }}",
    company: "{{ $json.company }}",
    notes: "{{ $json.need }}",
    source: "workflow",
    stage: "Qualified",
  });
  const crmNurture = makeNode("crm_lead", 920, 260, "Save to nurture list", {
    action: "upsert",
    email: "{{ $json.email }}",
    name: "{{ $json.name }}",
    phone: "{{ $json.phone }}",
    company: "{{ $json.company }}",
    notes: "{{ $json.need }}",
    source: "workflow",
    stage: "Nurture",
  });
  const outHot = makeNode("output", 1200, 60, "Record hot lead", { channel: "log" });
  const outNurture = makeNode("output", 1200, 260, "Record nurture lead", { channel: "log" });
  return {
    nodes: [trigger, extract, score, crmHot, crmNurture, outHot, outNurture],
    edges: [
      ...chainEdges(trigger, extract, score),
      makeEdge(score.id, crmHot.id, "hot"),
      makeEdge(score.id, crmNurture.id, "nurture"),
      makeEdge(crmHot.id, outHot.id),
      makeEdge(crmNurture.id, outNurture.id),
    ],
  };
}

function emailCampaignGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const copy = makeNode("agent", 360, 160, "Campaign copywriter");
  const approve = makeNode("approval", 640, 160, "Approve campaign before it sends", {
    message: "Review the drafted campaign email. Nothing is sent to the contact group until you approve it.",
  });
  const send = makeNode("bulk_email", 920, 160, "Send to contact group", {
    recipientsSource: "group",
    groupId: "",
    subject: "A quick update from us",
    body: "{{input}}",
    maxPerMinute: 60,
    maxRecipients: 100,
    skipIfReplied: true,
    includeUnsubscribe: true,
  });
  const out = makeNode("output", 1200, 160, "Keep send report in run history", { channel: "log" });
  return { nodes: [trigger, copy, approve, send, out], edges: chainEdges(trigger, copy, approve, send, out) };
}

function contentRepurposeGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const brief = makeNode("agent", 360, 160, "Extract the key points");
  const fan = makeNode("parallel", 640, 160, "Write every format at once");
  const linkedin = makeNode("agent", 920, 40, "LinkedIn post writer");
  const thread = makeNode("agent", 920, 160, "X thread writer");
  const newsletter = makeNode("agent", 920, 280, "Newsletter blurb writer");
  const pack = makeNode("agent", 1200, 160, "Assemble the content pack");
  const approve = makeNode("approval", 1480, 160, "Review the pack before posting", {
    message: "Review the LinkedIn post, X thread and newsletter blurb before they are used.",
  });
  const out = makeNode("output", 1760, 160, "Keep content pack in run history", { channel: "log" });
  return {
    nodes: [trigger, brief, fan, linkedin, thread, newsletter, pack, approve, out],
    edges: [
      ...chainEdges(trigger, brief, fan),
      makeEdge(fan.id, linkedin.id),
      makeEdge(fan.id, thread.id),
      makeEdge(fan.id, newsletter.id),
      makeEdge(linkedin.id, pack.id),
      makeEdge(thread.id, pack.id),
      makeEdge(newsletter.id, pack.id),
      ...chainEdges(pack, approve, out),
    ],
  };
}

// Workflow templates: Sales & marketing.
export const salesAndMarketingTemplates: WorkflowTemplate[] = [
  // ---- Wave 4: no-agent-needed AI nodes, branching, data nodes, CRM + bulk email ----
  // Several of these use the model-backed nodes (question_classifier,
  // parameter_extractor, document_extractor) and the data nodes (switch, filter,
  // aggregate, limit), which need no agent assigned - only the steps labelled
  // "agent" do. Nothing here uses Wait or form triggers: both are off by default
  // on a deployment (app.workflow.wait-node / form-trigger), so a template built
  // on them would fail activation out of the box.
  {
    key: "lead-capture-crm-workflow",
    category: "Sales & marketing",
    icon: Target,
    description:
      "Pulls name, email, phone, company and need out of any inbound lead message, sorts it hot or nurture, and " +
      "saves it to the CRM at the matching stage. Works out of the box - no agent to assign.",
    triggerChannel: "WEBHOOK",
    buildGraph: leadCaptureCrmGraph,
  },
  {
    key: "email-campaign-approval-workflow",
    category: "Sales & marketing",
    icon: Mail,
    description:
      "Drafts a campaign email from your brief, holds it for your approval, then sends it to a contact group with an " +
      "unsubscribe link and a send-rate cap. Assign your copywriter agent and pick the contact group on the canvas.",
    triggerChannel: "WEBHOOK",
    buildGraph: emailCampaignGraph,
  },
  {
    key: "social-content-repurpose-workflow",
    category: "Sales & marketing",
    icon: Share2,
    description:
      "Turns one idea or article into a LinkedIn post, an X thread and a newsletter blurb at the same time, merges " +
      "them into one pack and holds it for review. Assign each writer its agent on the canvas.",
    triggerChannel: "WEBHOOK",
    buildGraph: contentRepurposeGraph,
  },
];
