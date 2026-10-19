import { Banknote, Binary, GitPullRequest, Rocket, Terminal } from "lucide-react";
import { makeNode, makeEdge, chainEdges, extractor, classifier } from "./graphHelpers";
import type { TemplateGraph } from "./graphHelpers";
import type { WorkflowTemplate } from "./types";

// ----------------------------------------------- Engineering & modernization

// Scheduler alert -> extract job/step/abend -> route by abend family -> one
// analyst per family -> one Teams post. Three analysts converge on a single
// output node (same shape as appointmentRouterGraph); the classifier's
// default is "resource" because an unrecognised code is more often a JCL or
// space problem than a program bug.
function mainframeAbendTriageGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const extract = extractor(
    360,
    160,
    "Extract job, step and abend code",
    [
      { name: "jobName", type: "string", description: "The failed job's name, e.g. PAYBAT01", required: true },
      { name: "stepName", type: "string", description: "The failing step name from the JCL", required: false },
      {
        name: "abendCode",
        type: "string",
        description: "The abend or return code, e.g. S0C7, SB37, U4038, SQLCODE -911, ASRA",
        required: true,
      },
      {
        name: "dataset",
        type: "string",
        description: "The dataset named in the failure message, if any",
        required: false,
      },
      {
        name: "messageText",
        type: "string",
        description: "The key message line (IEF, IEC, DSN or DFH prefix) verbatim",
        required: false,
      },
    ],
    "The payload is a batch job failure from a mainframe job scheduler. Pull the identifiers exactly as written; do not normalise codes."
  );
  const family = classifier(
    640,
    160,
    "Which kind of failure?",
    [
      {
        handle: "data",
        label: "Data or program abend",
        description: "S0C7, S0C4, S0C1, S0CB, U-codes: bad data, a bad subscript or a program logic fault.",
      },
      {
        handle: "resource",
        label: "Resource or JCL problem",
        description:
          "SB37, SD37, SE37, S222, S322, S806, S013, S213, IEF or IEC messages: space, time, missing dataset or module, JCL error.",
      },
      {
        handle: "database",
        label: "DB2 or CICS failure",
        description: "A negative SQLCODE (-911, -805, -904), a DSN message, or a CICS abend such as ASRA, AICA, AEY9.",
      },
    ],
    "resource",
    "Classify by the abend or message family, not by the job's name."
  );
  const dataAnalyst = makeNode("agent", 920, 40, "Data abend analyst");
  const resourceAnalyst = makeNode("agent", 920, 160, "JCL & resource analyst");
  const dbAnalyst = makeNode("agent", 920, 280, "DB2 & CICS analyst");
  const out = makeNode("output", 1200, 160, "Post triage to on-call Teams", { channel: "teams" });
  return {
    nodes: [trigger, extract, family, dataAnalyst, resourceAnalyst, dbAnalyst, out],
    edges: [
      makeEdge(trigger.id, extract.id),
      makeEdge(extract.id, family.id),
      makeEdge(family.id, dataAnalyst.id, "data"),
      makeEdge(family.id, resourceAnalyst.id, "resource"),
      makeEdge(family.id, dbAnalyst.id, "database"),
      makeEdge(dataAnalyst.id, out.id),
      makeEdge(resourceAnalyst.id, out.id),
      makeEdge(dbAnalyst.id, out.id),
    ],
  };
}

// Copybook file -> text -> translator -> developer review -> downloadable file.
function copybookToSchemaGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const read = makeNode("document_extractor", 360, 160, "Read the copybook", {
    source: "",
    format: "auto",
    outputMode: "text",
  });
  const translate = makeNode("agent", 640, 160, "Copybook translator");
  const review = makeNode("approval", 920, 160, "Developer review", {
    message:
      "Check the field mapping - especially COMP-3 scale, REDEFINES and OCCURS DEPENDING ON - before this is saved.",
  });
  const out = makeNode("output", 1200, 160, "Save schema & Java record", {
    channel: "file",
    fileType: "text",
    fileName: "copybook-schema.txt",
  });
  return { nodes: [trigger, read, translate, review, out], edges: chainEdges(trigger, read, translate, review, out) };
}

// Reconciliation result -> keep the breaks -> count & total -> analyst -> ops email.
function reconciliationBreaksGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const breaks = makeNode("filter", 360, 160, "Keep unmatched entries", {
    itemsField: "entries",
    conditions: [{ field: "status", operator: "eq", value: "unmatched" }],
    combinator: "and",
    ignoreCase: true,
  });
  const totals = makeNode("aggregate", 640, 160, "Count & total the breaks", {
    itemsField: "",
    groupBy: "",
    aggregations: [
      { operation: "count", field: "", as: "breaks" },
      { operation: "sum", field: "amount", as: "exposure" },
    ],
  });
  const analyst = makeNode("agent", 920, 160, "Reconciliation analyst");
  const out = makeNode("output", 1200, 160, "Email the breaks report to payments ops", {
    channel: "email",
    emailTo: "",
  });
  return { nodes: [trigger, breaks, totals, analyst, out], edges: chainEdges(trigger, breaks, totals, analyst, out) };
}

// Jenkinsfile -> text -> translator -> platform engineer review -> downloadable file.
function pipelineTranslationGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const read = makeNode("document_extractor", 360, 160, "Read the Jenkinsfile", {
    source: "",
    format: "auto",
    outputMode: "text",
  });
  const translate = makeNode("agent", 640, 160, "Pipeline translator");
  const review = makeNode("approval", 920, 160, "Platform engineer review", {
    message:
      "Confirm every secret is a reference, every manual gate survived, and the stage mapping is complete before this is saved.",
  });
  const out = makeNode("output", 1200, 160, "Save the migrated pipeline", {
    channel: "file",
    fileType: "text",
    fileName: "pipeline-migration.txt",
  });
  return { nodes: [trigger, read, translate, review, out], edges: chainEdges(trigger, read, translate, review, out) };
}

// Workflow templates: Engineering & modernization.
export const engineeringAndModernizationTemplates: WorkflowTemplate[] = [

  // ------------------------------------------- Engineering & modernization
  {
    key: "mainframe-abend-triage-workflow",
    category: "Engineering & modernization",
    icon: Terminal,
    description:
      "Your job scheduler posts a batch failure (payload with the job, step and SYSOUT excerpt). The workflow pulls " +
      "out the job name, step and abend code, sorts it into a data abend (S0C7, S0C4), a resource or JCL problem " +
      "(SB37, S222, S806) or a DB2/CICS failure (SQLCODE, ASRA), hands each to its own analyst and posts the cause, " +
      "fix and safe rerun to the on-call Teams channel. Assign the three analysts (the Mainframe Abend Triage agent " +
      "template fits all three).",
    triggerChannel: "WEBHOOK",
    buildGraph: mainframeAbendTriageGraph,
  },
  {
    key: "cobol-copybook-to-schema-workflow",
    category: "Engineering & modernization",
    icon: Binary,
    description:
      'Reads a COBOL copybook (payload with a "url" to the file), has a translator agent produce the JSON schema, ' +
      "the Java record with BigDecimal for every COMP-3 field, and a table of the business rules it found, then " +
      "holds the result for a developer's review before saving it as a downloadable file. Assign the translator " +
      "agent (COBOL to Java Modernizer).",
    triggerChannel: "WEBHOOK",
    buildGraph: copybookToSchemaGraph,
  },
  {
    key: "payment-reconciliation-breaks-workflow",
    category: "Engineering & modernization",
    icon: Banknote,
    description:
      'Your reconciliation engine posts the morning\'s ledger-versus-switch comparison (payload with an "entries" ' +
      "array, each with a status and an amount). The workflow keeps only the unmatched entries, counts and totals " +
      "them, has an analyst classify the breaks (timing, duplicate, missing leg, amount mismatch) with a next action " +
      "for each, and emails the report to payments operations. Assign the analyst agent (ISO 20022 Payments Analyst " +
      "or Core Banking Batch Ops Copilot).",
    triggerChannel: "WEBHOOK",
    buildGraph: reconciliationBreaksGraph,
  },
  {
    key: "legacy-pipeline-translation-workflow",
    category: "Engineering & modernization",
    icon: GitPullRequest,
    description:
      'Reads a Jenkinsfile or other legacy pipeline definition (payload with a "url"), has an agent write the ' +
      "equivalent GitHub Actions or GitLab CI workflow with a stage-by-stage mapping, the secrets to migrate and " +
      "the DORA metric each change should move, then waits for a platform engineer's review before saving the " +
      "result. Assign the translator agent (Legacy Pipeline Modernizer).",
    triggerChannel: "WEBHOOK",
    buildGraph: pipelineTranslationGraph,
  },
  {
    key: "release-readiness-gate-workflow",
    category: "Engineering & modernization",
    icon: Rocket,
    description:
      "A release candidate is tagged; your CI calls the webhook. The workflow fetches the build and test status from " +
      "your CI API, has a reviewer agent write the go/no-go brief (failing checks, open blockers, migration and " +
      "rollback notes), holds it for the release owner's approval and announces the decision in Teams. Point the " +
      "HTTP node at your CI and assign the reviewer agent.",
    triggerChannel: "WEBHOOK",
    steps: [
      { type: "http", label: "Fetch CI status", config: { method: "GET", url: "" } },
      { type: "agent", label: "Release readiness reviewer" },
      {
        type: "approval",
        label: "Release owner: go or no-go?",
        config: {
          message: "Read the readiness brief. Approve to announce go; reject to announce no-go.",
          adminOnly: true,
        },
      },
      { type: "output", label: "Announce the decision", config: { channel: "teams" } },
    ],
  },
];
