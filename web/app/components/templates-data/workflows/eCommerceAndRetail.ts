import { ShoppingBag, ShoppingCart, Undo2 } from "lucide-react";
import { makeNode, makeEdge, chainEdges, extractor } from "./graphHelpers";
import type { TemplateGraph } from "./graphHelpers";
import type { WorkflowTemplate } from "./types";

function refundThresholdGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WHATSAPP" });
  const extract = extractor(360, 160, "Extract refund request", [
    { name: "orderId", type: "string", description: "The order number", required: true },
    { name: "reason", type: "string", description: "Why the customer wants a refund", required: false },
    { name: "amount", type: "number", description: "The amount to refund, as a number", required: true },
  ]);
  const big = makeNode("condition", 640, 160, "Refund over 2,000?", {
    mode: "expression",
    expression: "$json.amount > 2000",
  });
  const approve = makeNode("approval", 920, 60, "Admin approval for large refund", {
    message: "This refund is above the auto-reply threshold. Approve before the customer is told.",
    adminOnly: true,
  });
  const bigReply = makeNode("agent", 1200, 60, "Large-refund reply drafter");
  const smallReply = makeNode("agent", 920, 280, "Standard refund reply drafter");
  const outBig = makeNode("output", 1480, 60, "Reply to customer", { channel: "whatsapp" });
  const outSmall = makeNode("output", 1200, 280, "Reply to customer", { channel: "whatsapp" });
  return {
    nodes: [trigger, extract, big, approve, bigReply, smallReply, outBig, outSmall],
    edges: [
      ...chainEdges(trigger, extract),
      makeEdge(extract.id, big.id),
      makeEdge(big.id, approve.id, "true"),
      makeEdge(big.id, smallReply.id, "false"),
      ...chainEdges(approve, bigReply, outBig),
      makeEdge(smallReply.id, outSmall.id),
    ],
  };
}

// Workflow templates: E-commerce & retail.
export const eCommerceAndRetailTemplates: WorkflowTemplate[] = [
  {
    key: "ecommerce-order-status-workflow",
    category: "E-commerce & retail",
    icon: ShoppingCart,
    description: "Answers an inbound order-status question and replies on the same channel.",
    triggerChannel: "WHATSAPP",
    steps: [
      { type: "agent", label: "Order status & returns agent" },
      { type: "output", label: "Reply to customer", config: { channel: "whatsapp" } },
    ],
  },
  {
    key: "ecommerce-abandoned-cart-workflow",
    category: "E-commerce & retail",
    icon: ShoppingBag,
    description: "Drafts a re-engagement message when a cart-abandoned event comes in.",
    triggerChannel: "WEBHOOK",
    steps: [
      { type: "agent", label: "Cart re-engagement message drafter" },
      {
        type: "approval",
        label: "Review before sending",
        config: { message: "Approve this cart-recovery message before it goes out." },
      },
      { type: "output", label: "Send reminder", config: { channel: "whatsapp" } },
    ],
  },
  {
    key: "refund-approval-threshold-workflow",
    category: "E-commerce & retail",
    icon: Undo2,
    description:
      "Extracts the order, reason and amount from a WhatsApp refund request. Refunds above 2,000 wait for an " +
      "admin to approve; smaller ones get an immediate reply. Change the threshold in the condition expression. " +
      "Assign the two reply agents on the canvas.",
    triggerChannel: "WHATSAPP",
    buildGraph: refundThresholdGraph,
  },
];
