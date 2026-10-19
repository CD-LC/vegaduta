import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";
import {
  BULK_EMAIL_MAX_PER_MINUTE,
  BULK_EMAIL_MAX_RECIPIENTS,
  BULK_EMAIL_SOURCES,
  NODE_DEFS,
  bulkEmailMissing,
  nodeDef,
  nodeDescriptionKey,
  nodeLabelKey,
} from "./nodeDefs";

describe("bulk_email palette entry", () => {
  it("is registered, supported, and grouped under Output", () => {
    const def = NODE_DEFS.find((d) => d.type === "bulk_email");
    expect(def).toBeDefined();
    expect(def!.supported).toBe(true);
    expect(def!.category).toBe("Output");
    expect(nodeDef("bulk_email")).toBe(def);
  });

  it("has palette label/description strings in en.json (no raw-key rendering)", () => {
    const nodeDefsNs = (en as any).nodeDefs;
    expect(nodeDefsNs[nodeLabelKey("bulk_email")]).toBeTruthy();
    expect(nodeDefsNs[nodeDescriptionKey("bulk_email")]).toBeTruthy();
  });

  it("default config matches the server contract and stays inside its clamps", () => {
    const cfg = nodeDef("bulk_email").defaultConfig;
    expect(BULK_EMAIL_SOURCES).toContain(cfg.recipientsSource);
    expect(cfg.maxPerMinute).toBeGreaterThanOrEqual(1);
    expect(cfg.maxPerMinute).toBeLessThanOrEqual(BULK_EMAIL_MAX_PER_MINUTE);
    expect(cfg.maxRecipients).toBeGreaterThanOrEqual(1);
    expect(cfg.maxRecipients).toBeLessThanOrEqual(BULK_EMAIL_MAX_RECIPIENTS);
    // Compliance defaults: unsubscribe on, skip-if-replied is an explicit opt-in.
    expect(cfg.includeUnsubscribe).toBe(true);
    expect(cfg.skipIfReplied).toBe(false);
    expect(cfg.emailField).toBe("email");
  });

  it("every en.json key the node's UI reads exists", () => {
    const drawer = (en as any).nodeConfigDrawer;
    for (const key of [
      "bulkEmailHint",
      "bulkEmailSourceLabel",
      "bulkEmailSourceItems",
      "bulkEmailSourceGroup",
      "bulkEmailSourceStatic",
      "bulkEmailItemsFieldLabel",
      "bulkEmailItemsFieldPlaceholder",
      "bulkEmailEmailFieldLabel",
      "bulkEmailItemsHint",
      "bulkEmailGroupLabel",
      "bulkEmailGroupPlaceholder",
      "bulkEmailGroupHint",
      "bulkEmailStaticLabel",
      "bulkEmailStaticPlaceholder",
      "bulkEmailSubjectLabel",
      "bulkEmailSubjectPlaceholder",
      "bulkEmailBodyLabel",
      "bulkEmailBodyPlaceholder",
      "bulkEmailBodyHint",
      "bulkEmailHtmlLabel",
      "bulkEmailReplyToLabel",
      "bulkEmailReplyToPlaceholder",
      "bulkEmailReplyToHint",
      "bulkEmailMaxPerMinuteLabel",
      "bulkEmailMaxRecipientsLabel",
      "bulkEmailThrottleHint",
      "bulkEmailSkipIfRepliedLabel",
      "bulkEmailIncludeUnsubscribeLabel",
    ]) {
      expect(drawer[key], `nodeConfigDrawer.${key}`).toBeTruthy();
    }
    const node = (en as any).workflowNode;
    const keys = ["bulkEmailNoSubject", "bulkEmailNoBody", "bulkEmailNoRecipients", ...BULK_EMAIL_SOURCES.map((s) => `bulkEmailSource_${s}`)];
    for (const key of keys) {
      expect(node[key], `workflowNode.${key}`).toBeTruthy();
    }
  });
});

describe("bulkEmailMissing", () => {
  const ok = { subject: "Hi {{name}}", body: "Hello", recipientsSource: "items" };

  it("returns null for a runnable node", () => {
    expect(bulkEmailMissing(ok)).toBeNull();
    expect(bulkEmailMissing({ ...ok, recipientsSource: "group", groupId: "g1" })).toBeNull();
    expect(bulkEmailMissing({ ...ok, recipientsSource: "static", staticRecipients: "a@x.test" })).toBeNull();
  });

  it("reports the first thing the server's validate() would reject, in order", () => {
    expect(bulkEmailMissing(undefined)).toBe("subject");
    expect(bulkEmailMissing({ ...ok, subject: "  " })).toBe("subject");
    expect(bulkEmailMissing({ ...ok, body: "" })).toBe("body");
    // http's `body` key can hold a sub-graph object; that is never a valid email body.
    expect(bulkEmailMissing({ ...ok, body: { nodes: [], edges: [] } })).toBe("body");
    expect(bulkEmailMissing({ ...ok, recipientsSource: "group" })).toBe("group");
    expect(bulkEmailMissing({ ...ok, recipientsSource: "static", staticRecipients: " " })).toBe("static");
  });
});
