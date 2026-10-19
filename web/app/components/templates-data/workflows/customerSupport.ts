import { LifeBuoy, MessageCircleQuestion, ShieldCheck, Star } from "lucide-react";
import { makeNode, makeEdge, chainEdges, extractor, classifier } from "./graphHelpers";
import type { TemplateGraph } from "./graphHelpers";
import type { WorkflowTemplate } from "./types";

function reviewResponseGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const mood = classifier(
    360,
    160,
    "Positive or negative?",
    [
      {
        handle: "positive",
        label: "Positive",
        description: "The customer is happy and praises the product or service.",
      },
      {
        handle: "negative",
        label: "Negative",
        description: "The customer is unhappy, complains or reports a problem.",
      },
    ],
    "negative"
  );
  const thanks = makeNode("agent", 640, 60, "Thank-you reply drafter");
  const recover = makeNode("agent", 640, 260, "Service-recovery reply drafter");
  const approve = makeNode("approval", 920, 260, "Review before replying", {
    message: "Review this service-recovery reply before it is posted.",
  });
  const outThanks = makeNode("output", 920, 60, "Keep thank-you draft", { channel: "log" });
  const outRecover = makeNode("output", 1200, 260, "Keep recovery reply", { channel: "log" });
  return {
    nodes: [trigger, mood, thanks, recover, approve, outThanks, outRecover],
    edges: [
      makeEdge(trigger.id, mood.id),
      makeEdge(mood.id, thanks.id, "positive"),
      makeEdge(mood.id, recover.id, "negative"),
      makeEdge(thanks.id, outThanks.id),
      ...chainEdges(recover, approve, outRecover),
    ],
  };
}

function ticketTriageGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const extract = extractor(360, 160, "Extract ticket fields", [
    {
      name: "category",
      type: "enum",
      description: "What the ticket is about",
      required: true,
      options: ["billing", "technical", "account", "other"],
    },
    {
      name: "urgency",
      type: "enum",
      description: "high if the customer is blocked, losing money or mentions an outage; otherwise normal or low",
      required: true,
      options: ["low", "normal", "high"],
    },
    { name: "summary", type: "string", description: "One-sentence summary of the problem", required: true },
    { name: "customerEmail", type: "string", description: "The customer's email, if present", required: false },
  ]);
  const route = makeNode("switch", 640, 160, "Urgent?", {
    cases: [{ name: "urgent", combinator: "and", conditions: [{ field: "urgency", operator: "eq", value: "high" }] }],
    ignoreCase: true,
  });
  const page = makeNode("output", 920, 60, "Flag urgent ticket for on-call", { channel: "log" });
  const reply = makeNode("agent", 920, 260, "Support reply drafter");
  const outReply = makeNode("output", 1200, 260, "Keep drafted reply", { channel: "log" });
  return {
    nodes: [trigger, extract, route, page, reply, outReply],
    edges: [
      ...chainEdges(trigger, extract, route),
      makeEdge(route.id, page.id, "urgent"),
      makeEdge(route.id, reply.id, "default"),
      makeEdge(reply.id, outReply.id),
    ],
  };
}

function faqHandoffGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WHATSAPP" });
  const kind = classifier(
    360,
    160,
    "Routine question or needs a person?",
    [
      {
        handle: "faq",
        label: "Routine question",
        description: "A routine question answerable from documentation: hours, pricing, shipping, how-to, policies.",
      },
      {
        handle: "human",
        label: "Needs a person",
        description:
          "A complaint, refund, legal or medical matter, an angry customer, or anything that needs a human judgement.",
      },
    ],
    "human"
  );
  const docs = makeNode("knowledge", 640, 60, "Help-centre documents");
  const answer = makeNode("agent", 920, 60, "FAQ answerer");
  const reply = makeNode("output", 1200, 60, "Reply to customer", { channel: "whatsapp" });
  const holding = makeNode("output", 640, 280, "Send holding reply", {
    channel: "whatsapp",
    whatsappMessage:
      "Thanks for getting in touch - I've passed this to our team and a person will reply to you shortly.",
  });
  return {
    nodes: [trigger, kind, docs, answer, reply, holding],
    edges: [
      makeEdge(trigger.id, kind.id),
      makeEdge(kind.id, docs.id, "faq"),
      makeEdge(kind.id, holding.id, "human"),
      ...chainEdges(docs, answer, reply),
    ],
  };
}

function moderationGateGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const verdict = classifier(
    360,
    160,
    "Safe, spam or abusive?",
    [
      { handle: "safe", label: "Safe", description: "Ordinary, on-topic content from a real person." },
      { handle: "spam", label: "Spam", description: "Advertising, link-dumping, scams or repeated junk." },
      { handle: "abusive", label: "Abusive", description: "Harassment, hate, threats or other harmful content." },
    ],
    "abusive"
  );
  const reply = makeNode("agent", 640, 40, "Reply to the user");
  const outReply = makeNode("output", 920, 40, "Keep reply", { channel: "log" });
  const outSpam = makeNode("output", 640, 160, "Quarantine spam", { channel: "log" });
  const outAbuse = makeNode("output", 640, 280, "Hold abusive content for review", { channel: "log" });
  return {
    nodes: [trigger, verdict, reply, outReply, outSpam, outAbuse],
    edges: [
      makeEdge(trigger.id, verdict.id),
      makeEdge(verdict.id, reply.id, "safe"),
      makeEdge(verdict.id, outSpam.id, "spam"),
      makeEdge(verdict.id, outAbuse.id, "abusive"),
      makeEdge(reply.id, outReply.id),
    ],
  };
}

// Workflow templates: Customer support.
export const customerSupportTemplates: WorkflowTemplate[] = [
  {
    key: "review-response-workflow",
    category: "Customer support",
    icon: Star,
    description:
      "Reads a customer review, then drafts a thank-you for positive ones or a service-recovery reply for negative " +
      "ones - the negative reply is held for approval. Assign the two drafting agents on the canvas.",
    triggerChannel: "WEBHOOK",
    buildGraph: reviewResponseGraph,
  },
  {
    key: "support-ticket-triage-workflow",
    category: "Customer support",
    icon: LifeBuoy,
    description:
      "Extracts category, urgency, summary and customer from a raw ticket, routes urgent ones straight to the " +
      "on-call log and drafts a reply for the rest. Assign the reply-drafting agent on the canvas.",
    triggerChannel: "WEBHOOK",
    buildGraph: ticketTriageGraph,
  },
  {
    key: "faq-human-handoff-workflow",
    category: "Customer support",
    icon: MessageCircleQuestion,
    description:
      "Answers routine WhatsApp questions from your knowledge base; anything that needs a person - complaints, " +
      "refunds, legal - gets a holding reply and stays in the run history for follow-up instead of the bot guessing. " +
      "Pick the knowledge collection and assign the FAQ agent on the canvas.",
    triggerChannel: "WHATSAPP",
    buildGraph: faqHandoffGraph,
  },
  {
    key: "content-moderation-gate-workflow",
    category: "Customer support",
    icon: ShieldCheck,
    description:
      "Classifies incoming user content as safe, spam or abusive. Safe content gets a reply from your agent; the " +
      "rest is quarantined in the run history for review. Assign the reply agent on the canvas.",
    triggerChannel: "WEBHOOK",
    buildGraph: moderationGateGraph,
  },
];
