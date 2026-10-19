import type { Metadata } from "next";
import Link from "next/link";
import { ChevronRight } from "lucide-react";
import PublicWorkflowNav from "../workflow-recipes/PublicWorkflowNav";
import { FUNCTION_DOCS, functionsByCategory } from "./functionReference";

// ---------------------------------------------------------------------------
// Help page for the workflow expression language and data pinning (PR6).
//
// Server component in the same standalone public chrome as
// /help/workflow-recipes/[industry] (PublicWorkflowNav, no AppShell: public
// routes render with no AuthContext, and AppShell's useAuth() would throw for a
// logged-out visitor - see PUBLIC_ROUTES in lib/publicRoutes.ts). Copy is inline
// English like the recipe pages (namespace list [] - no en.json keys).
//
// Facts are copied from the engine, not written from memory:
//   - the function table is functionReference.ts, generated from the Java
//     BuiltIns registry and guarded by functionReference.test.ts;
//   - every worked example below was evaluated against the real
//     ExpressionEngine with the sample data shown, and the results printed here
//     are what it returned;
//   - limits and pin caps are the values in Limits.java / workflowPins.ts.
//
// RELEASED gates two things: the "not released yet" callout and search-engine
// indexing. Expressions are built in the working tree but not deployed, and the
// pin server side (storage, draft-run handling) is not built at all, so the
// page is noindex and out of the sitemap until this flips to true (and the
// sitemap entry is added). Do not flip it before the core + web stages ship.
// ---------------------------------------------------------------------------

const RELEASED = false;

const BASE = "https://example.com";
const PATH = "/help/workflow-expressions";
const TITLE = "Workflow Expressions and Data Pinning | VegaDūta";
const DESCRIPTION =
  "Reference for the workflow expression language: syntax, data roots, operators, every built-in function with an example, how to pin node output while you build, and worked examples.";

export const metadata: Metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: PATH },
  robots: RELEASED ? undefined : { index: false, follow: true },
  openGraph: { title: TITLE, description: DESCRIPTION, url: PATH, type: "article", images: ["/og-image.png"] },
};

const ROOTS: { root: string; meaning: string }[] = [
  { root: "$json", meaning: "This node's input, parsed when it is JSON, otherwise the raw text." },
  { root: "$input", meaning: "This node's input as raw text." },
  {
    root: '$node["Label"]',
    meaning:
      'The output of an earlier node, addressed by its label (parsed when it is JSON). $node["Label"].output is the raw text, .status its status, .summary its summary. $node.id("n7") addresses a node by id. The node must be upstream of the one using it.',
  },
  {
    root: "$trigger",
    meaning: "The payload that started the run: webhook body, channel message, schedule tick or event.",
  },
  { root: "$run", meaning: "id, startedAt, mode (production, draft or test) and requestedBy for the current run." },
  { root: "$workflow", meaning: "id and name of the workflow. version exists but is empty for now." },
  {
    root: "$vars",
    meaning: "Workflow variables, read-only. There is no editor for them yet, so this is usually empty.",
  },
  {
    root: "$item, $index, $items",
    meaning: "Inside a loop or fan-out body: the current element, its position, and the full list.",
  },
  { root: "$now", meaning: "The instant the expression is evaluated, as an ISO-8601 UTC string." },
];

const OPERATORS: { op: string; meaning: string }[] = [
  { op: "a ? b : c", meaning: "Ternary: b when a is truthy, otherwise c. Lowest precedence." },
  { op: "a ?? b", meaning: "b when a is null or missing." },
  { op: "a || b", meaning: "a when it is truthy, otherwise b." },
  { op: "a && b", meaning: "Both must be truthy." },
  { op: "== !=", meaning: "Equality. Strict on type, except that a number equals numeric text (1 == '1' is true)." },
  { op: "< > <= >=", meaning: "Comparison." },
  { op: "+ -", meaning: "Add and subtract. + joins text when either side is text ('a' + 1 is a1)." },
  { op: "* / %", meaning: "Multiply, divide, remainder." },
  { op: "! -", meaning: "Not, and negate. Highest precedence." },
  {
    op: 'a.b  a?.b  a["k"]  a[0]  a[-1]',
    meaning: "Property access, safe access, key lookup, index, and last item (-1).",
  },
  {
    op: "x.fn(args)",
    meaning:
      "Method form: sugar for fn(x, args), so items.filter(i => i.qty > 0) equals filter(items, i => i.qty > 0).",
  },
  { op: "i => expr", meaning: "One-parameter arrow function. Only accepted as an argument to a built-in." },
];

const FIELDS: string[] = [
  "Agent: the query in inputMapping",
  "Code: the template operation's template",
  "HTTP: url, body, header values and query values",
  "Tool: the arguments JSON",
  "Output: emailTo, telegramChatId, teamsConversationId, whatsappTo, driveFolderId, driveFileName, fileName",
  "Bulk email: subject and body (rendered once per recipient) and replyTo",
  "CRM lead: action, ids and contact fields, stage, score, value, tags, owner, notes and activity fields",
  "Device control: the payload",
  "Condition: the expression mode (a bare expression, written without braces)",
];

const LIMITS: { limit: string; value: string }[] = [
  { limit: "Expression length", value: "2,000 characters" },
  { limit: "Syntax tree size", value: "500 nodes" },
  { limit: "Evaluation steps", value: "20,000 (every node visit and every array-callback iteration)" },
  { limit: "Time per field", value: "50 ms" },
  { limit: "Result size", value: "256 KB" },
  { limit: "Regular expressions", value: "Pattern length capped at 200 characters and time-limited" },
];

interface Example {
  title: string;
  setup: string;
  expression: string;
  result: string;
  note: string;
}

const ORDER_JSON =
  '{"customer":{"name":"Asha","tier":"gold"},"items":[{"name":"Pen","price":120,"qty":2},{"name":"Book","price":450,"qty":1}]}';

const EXAMPLES: Example[] = [
  {
    title: "1. Reach back to an earlier step",
    setup: `A node labelled Fetch Order returned ${ORDER_JSON}`,
    expression: '{{ round($node["Fetch Order"].items[0].price * 1.18, 2) }}',
    result: "141.6",
    note: "Works from any node downstream of Fetch Order, not only the one right after it. Price of the first item plus 18 percent, rounded to two decimals.",
  },
  {
    title: "2. Total an array",
    setup: "Same Fetch Order output.",
    expression: '{{ sum($node["Fetch Order"].items, i => i.price * i.qty) }}',
    result: "690",
    note: "sum takes an optional function that picks the number from each item: 120 x 2 + 450 x 1.",
  },
  {
    title: "3. Fall back to a default",
    setup: `This node's input is ${ORDER_JSON}`,
    expression: "Hello {{ $json.customer.nickname ?? $json.customer.name }}",
    result: "Hello Asha",
    note: "nickname is absent, so ?? picks the name. A missing path is empty, not an error. Text outside the braces stays as written.",
  },
  {
    title: "4. Route on a condition",
    setup: "A Condition node in expression mode, same input as example 3.",
    expression: '$json.customer.tier == "gold" && sum($json.items, i => i.price * i.qty) > 500',
    result: "true",
    note: "Condition expressions are bare, with no {{ }}. true sends the run down the true edge.",
  },
  {
    title: "5. Filter, pick and join",
    setup: "Same input as example 3.",
    expression: '{{ $json.items.filter(i => i.qty > 1).pluck("name").join(", ") }}',
    result: "Pen",
    note: "Method chaining reads left to right. With more matching items you would get a comma-separated list.",
  },
  {
    title: "6. Use the trigger payload and dates",
    setup: 'A webhook delivered {"orderedAt":"2026-09-30T10:00:00Z"}.',
    expression: '{{ formatDate(addDays($trigger.orderedAt, 7), "yyyy-MM-dd") }}',
    result: "2026-10-07",
    note: "Seven days after the order, formatted as a date. Dates are ISO-8601 in UTC unless you pass an IANA zone such as Asia/Kolkata.",
  },
];

function SectionLabel({ children }: { children: React.ReactNode }) {
  return <h3 className="text-[11px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">{children}</h3>;
}

function Code({ children }: { children: React.ReactNode }) {
  return <code className="rounded bg-muted/60 px-1 py-0.5 font-mono text-[0.85em] text-foreground">{children}</code>;
}

function Pre({ children }: { children: React.ReactNode }) {
  return (
    <pre className="overflow-auto whitespace-pre-wrap rounded-md border border-border bg-muted/30 p-3 font-mono text-xs leading-relaxed text-foreground">
      {children}
    </pre>
  );
}

function H2({ id, children }: { id: string; children: React.ReactNode }) {
  return (
    <h2 id={id} className="scroll-mt-24 text-xl font-semibold tracking-tight text-foreground sm:text-2xl">
      {children}
    </h2>
  );
}

const TOC: { id: string; label: string }[] = [
  { id: "syntax", label: "Syntax" },
  { id: "roots", label: "Data roots" },
  { id: "operators", label: "Operators" },
  { id: "functions", label: "Functions" },
  { id: "fields", label: "Where expressions work" },
  { id: "pinning", label: "Data pinning" },
  { id: "examples", label: "Worked examples" },
  { id: "errors", label: "Errors and limits" },
];

const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "Help", item: `${BASE}/help` },
        { "@type": "ListItem", position: 2, name: "Workflow expressions", item: `${BASE}${PATH}` },
      ],
    },
  ],
};

export default function WorkflowExpressionsHelpPage() {
  const groups = functionsByCategory();

  return (
    <div className="vd-pub relative min-h-screen w-full overflow-x-clip bg-background text-foreground antialiased">
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }} />

      <PublicWorkflowNav />

      <main className="mx-auto w-full max-w-4xl px-5 pb-20 sm:px-8">
        <nav aria-label="Breadcrumb" className="mb-6">
          <ol className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
            <li>
              <Link href="/help" className="hover:text-foreground hover:underline">
                Help
              </Link>
            </li>
            <li aria-hidden="true">
              <ChevronRight className="size-3.5" aria-hidden="true" />
            </li>
            <li aria-current="page" className="text-foreground">
              Workflow expressions
            </li>
          </ol>
        </nav>

        <header className="space-y-4">
          <p className="text-xs font-semibold uppercase tracking-[0.14em] text-primary">Workflow designer</p>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground sm:text-3xl">
            Workflow expressions and data pinning
          </h1>
          <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base">
            Expressions let any node read the output of any earlier node, index into lists and compute values, without
            chaining Code nodes. Data pinning freezes a node&apos;s output so you can build and test the steps after it
            without running the steps before it again.
          </p>
          {!RELEASED ? (
            <div className="rounded-md border border-border bg-muted/30 px-4 py-3 text-sm leading-relaxed text-foreground">
              <span className="font-medium">Not released yet.</span> Expressions are built but have not been deployed,
              so the Expression toggle does not appear in the designer today. For data pinning, the designer controls
              are built but the server side (saving pins and skipping pinned nodes in draft runs) is not, so pinning
              does not work yet. This page describes the intended behavior once both are released.
            </div>
          ) : null}
        </header>

        <nav aria-label="On this page" className="mt-8 flex flex-wrap gap-2">
          {TOC.map((t) => (
            <a
              key={t.id}
              href={`#${t.id}`}
              className="rounded-full border border-border bg-muted/40 px-3 py-1 text-xs text-foreground hover:bg-muted"
            >
              {t.label}
            </a>
          ))}
        </nav>

        <div className="mt-10 space-y-14">
          {/* ------------------------------------------------------------ syntax */}
          <section className="space-y-4">
            <H2 id="syntax">Syntax</H2>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              An expression sits inside double curly braces in a node field. Everything outside the braces is literal
              text, and one field can hold several expressions.
            </p>
            <Pre>{"Order for {{ $json.customer.name }}: total {{ sum($json.items, i => i.price * i.qty) }}"}</Pre>
            <ul className="list-disc space-y-2 pl-5 text-sm leading-relaxed text-muted-foreground sm:text-base">
              <li>
                <span className="font-medium text-foreground">The old placeholders still work.</span>{" "}
                <Code>{"{{input}}"}</Code> is the node&apos;s raw input and <Code>{"{{fieldName}}"}</Code> is a
                top-level field of the input when it is a JSON object. Use them for simple cases and reach for the
                richer syntax only when you need it. A bare name that matches nothing stays in the text as written.
              </li>
              <li>
                <span className="font-medium text-foreground">Results become text.</span> A number or boolean is written
                as itself, an object or list as compact JSON, and null or a missing value as empty text. Inside a field
                that is exactly one expression and expects structured data, the value keeps its type.
              </li>
              <li>
                <span className="font-medium text-foreground">Missing paths are empty, not errors.</span>{" "}
                <Code>$json.a.b.c</Code> is empty when <Code>a</Code> has no <Code>b</Code>. Use <Code>?.</Code> and{" "}
                <Code>??</Code> to say so explicitly.
              </li>
              <li>
                <span className="font-medium text-foreground">Values are never evaluated twice.</span> If incoming data,
                such as a webhook body, itself contains <Code>{"{{ ... }}"}</Code>, that text is inserted as-is and
                never run.
              </li>
              <li>
                <span className="font-medium text-foreground">Secrets are not expressions.</span> Keep API keys in the
                vault reference form, <Code>{"{{secret.NAME}}"}</Code>; <Code>$secrets</Code> is not available inside
                expressions.
              </li>
            </ul>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              Literals are numbers, <Code>&quot;text&quot;</Code> or <Code>&apos;text&apos;</Code>, <Code>true</Code>,{" "}
              <Code>false</Code>, <Code>null</Code>, lists <Code>[1, 2]</Code> and objects <Code>{"{key: value}"}</Code>
              .
            </p>
          </section>

          {/* ------------------------------------------------------------- roots */}
          <section className="space-y-4">
            <H2 id="roots">Data roots</H2>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              Names starting with <Code>{"$"}</Code> are the places an expression reads from.
            </p>
            <div className="overflow-x-auto rounded-lg border border-border">
              <table className="w-full min-w-[32rem] text-left text-sm">
                <thead className="bg-muted/40 text-xs uppercase tracking-wide text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2 font-semibold">Root</th>
                    <th className="px-3 py-2 font-semibold">What it is</th>
                  </tr>
                </thead>
                <tbody>
                  {ROOTS.map((r) => (
                    <tr key={r.root} className="border-t border-border align-top">
                      <td className="px-3 py-2 font-mono text-xs text-foreground">{r.root}</td>
                      <td className="px-3 py-2 leading-relaxed text-muted-foreground">{r.meaning}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              Renaming a node updates the <Code>$node[&quot;Label&quot;]</Code> references to it in the same edit. If
              two nodes share a label, address the one you mean by id. Referencing a node that is not upstream is
              flagged in the lint panel, because it could never have run.
            </p>
          </section>

          {/* --------------------------------------------------------- operators */}
          <section className="space-y-4">
            <H2 id="operators">Operators</H2>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              Listed from lowest to highest precedence.
            </p>
            <div className="overflow-x-auto rounded-lg border border-border">
              <table className="w-full min-w-[32rem] text-left text-sm">
                <thead className="bg-muted/40 text-xs uppercase tracking-wide text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2 font-semibold">Operator</th>
                    <th className="px-3 py-2 font-semibold">Meaning</th>
                  </tr>
                </thead>
                <tbody>
                  {OPERATORS.map((o) => (
                    <tr key={o.op} className="border-t border-border align-top">
                      <td className="px-3 py-2 font-mono text-xs text-foreground">{o.op}</td>
                      <td className="px-3 py-2 leading-relaxed text-muted-foreground">{o.meaning}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          {/* --------------------------------------------------------- functions */}
          <section className="space-y-6">
            <div className="space-y-2">
              <H2 id="functions">Functions</H2>
              <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
                {FUNCTION_DOCS.length} built-in functions. Call them as <Code>fn(x, args)</Code> or{" "}
                <Code>x.fn(args)</Code>. The list below is generated from the engine&apos;s own function registry, and
                every example was evaluated by the engine to produce the result shown. Functions that take a function
                accept an arrow function such as <Code>i =&gt; i.price</Code>.
              </p>
            </div>
            {groups.map((g) => (
              <div key={g.category} className="space-y-3">
                <SectionLabel>
                  {g.category} ({g.functions.length})
                </SectionLabel>
                <div className="overflow-x-auto rounded-lg border border-border">
                  <table className="w-full min-w-[40rem] text-left text-sm">
                    <thead className="bg-muted/40 text-xs uppercase tracking-wide text-muted-foreground">
                      <tr>
                        <th className="px-3 py-2 font-semibold">Function</th>
                        <th className="px-3 py-2 font-semibold">What it does</th>
                        <th className="px-3 py-2 font-semibold">Example</th>
                        <th className="px-3 py-2 font-semibold">Result</th>
                      </tr>
                    </thead>
                    <tbody>
                      {g.functions.map((f) => (
                        <tr key={f.name} id={`fn-${f.name}`} className="scroll-mt-24 border-t border-border align-top">
                          <td className="px-3 py-2 font-mono text-xs text-foreground">{f.signature}</td>
                          <td className="px-3 py-2 leading-relaxed text-muted-foreground">{f.doc}</td>
                          <td className="px-3 py-2 font-mono text-xs text-foreground">{f.example}</td>
                          <td className="px-3 py-2 font-mono text-xs text-foreground">
                            {f.name === "now" ? (
                              <>
                                {f.result}
                                <span className="block font-sans text-[11px] text-muted-foreground">
                                  shown for a fixed test instant
                                </span>
                              </>
                            ) : (
                              f.result
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ))}
          </section>

          {/* ------------------------------------------------------------ fields */}
          <section className="space-y-4">
            <H2 id="fields">Where expressions work</H2>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              Only these node fields are evaluated. Any other field is passed through untouched, so braces typed into it
              stay as text.
            </p>
            <ul className="list-disc space-y-1.5 pl-5 text-sm leading-relaxed text-muted-foreground sm:text-base">
              {FIELDS.map((f) => (
                <li key={f}>{f}</li>
              ))}
            </ul>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              In the designer, each of these fields has a Fixed / Expression toggle, autocomplete for the roots,
              upstream node labels and function names, and a live preview of the result when sample data is available. A
              loop or fan-out body can use <Code>$item</Code>, <Code>$index</Code> and <Code>$items</Code>. A
              sub-workflow receives its input as <Code>$trigger</Code> and cannot read the parent&apos;s node outputs.
            </p>
          </section>

          {/* ----------------------------------------------------------- pinning */}
          <section className="space-y-4">
            <H2 id="pinning">Data pinning</H2>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              A pin is a saved output attached to a node on the draft workflow. When you run the draft or test a step, a
              pinned node does not run: it returns its pinned output, the trace marks it <Code>PINNED</Code>, and the
              run carries on to the next node. You keep real data in front of the nodes you are editing without paying
              again for the LLM call, the API request or the email upstream.
            </p>
            <div className="space-y-2">
              <SectionLabel>How to use it</SectionLabel>
              <ol className="list-decimal space-y-2 pl-5 text-sm leading-relaxed text-muted-foreground sm:text-base">
                <li>Run the step once with Test step, or open a past run. Real data comes back.</li>
                <li>
                  Choose <span className="font-medium text-foreground">Pin output</span> on that result, or use the pin
                  icon on the node and paste the JSON you want it to return. Invalid JSON is rejected before it is
                  saved; text that is not JSON is saved as plain text.
                </li>
                <li>
                  The node shows a pinned badge and a banner in its settings with the pin&apos;s source and age, and
                  options to view, edit or unpin.
                </li>
                <li>
                  Build the nodes after it. Their expression previews use the pinned data, and draft runs and step tests
                  skip the pinned node. The run summary states how many nodes were pinned, so a green draft run is never
                  mistaken for a full end-to-end run.
                </li>
                <li>Unpin when you are done, or leave the pin in place; it never affects live runs.</li>
              </ol>
            </div>
            <div className="space-y-2">
              <SectionLabel>Rules worth knowing</SectionLabel>
              <ul className="list-disc space-y-2 pl-5 text-sm leading-relaxed text-muted-foreground sm:text-base">
                <li>
                  <span className="font-medium text-foreground">Live runs ignore pins.</span> Webhooks, schedules,
                  channel messages, events, API runs and share links always run every node for real. The engine decides
                  this from the run mode, not from anything the browser sends.
                </li>
                <li>
                  <span className="font-medium text-foreground">Side-effect nodes can be pinned.</span> Pinning the
                  email, CRM or HTTP node lets you test what comes after it without sending anything again.
                </li>
                <li>
                  <span className="font-medium text-foreground">Trigger samples are the best pin.</span> A webhook or
                  channel trigger cannot be tested any other way, so pin a received event and build against it.
                </li>
                <li>
                  <span className="font-medium text-foreground">Redacted or cut-off data.</span> Run records replace
                  secret-looking values with [redacted] and cut long outputs. Pinning from such a record asks you to
                  confirm, because the pin will not behave like the real data.
                </li>
                <li>
                  <span className="font-medium text-foreground">Size caps.</span> 256,000 characters per pin, 30 pins
                  and 2,000,000 characters in total per workflow.
                </li>
                <li>
                  <span className="font-medium text-foreground">Access.</span> Only people who can edit the workflow can
                  view or change pinned data, since it can contain real customer data. Someone who can only run the
                  workflow can run a draft that uses the pins but cannot read them.
                </li>
                <li>
                  <span className="font-medium text-foreground">Pins stay with the draft.</span> They are not part of
                  saved versions, shared links or marketplace templates, and duplicating a workflow does not copy them.
                </li>
              </ul>
            </div>
          </section>

          {/* ---------------------------------------------------------- examples */}
          <section className="space-y-6">
            <div className="space-y-2">
              <H2 id="examples">Worked examples</H2>
              <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
                Each result below is what the engine returned for the sample data shown.
              </p>
            </div>
            {EXAMPLES.map((ex) => (
              <article key={ex.title} className="space-y-3 rounded-lg border border-border bg-card p-4 sm:p-5">
                <h3 className="text-base font-semibold text-foreground">{ex.title}</h3>
                <p className="break-words text-sm leading-relaxed text-muted-foreground">{ex.setup}</p>
                <div className="space-y-1">
                  <SectionLabel>Expression</SectionLabel>
                  <Pre>{ex.expression}</Pre>
                </div>
                <div className="space-y-1">
                  <SectionLabel>Result</SectionLabel>
                  <Pre>{ex.result}</Pre>
                </div>
                <p className="text-sm leading-relaxed text-muted-foreground">{ex.note}</p>
              </article>
            ))}
          </section>

          {/* ------------------------------------------------------------ errors */}
          <section className="space-y-4">
            <H2 id="errors">Errors and limits</H2>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              Syntax mistakes are caught as you type and in the lint panel, with the position and, for a misspelled
              function, a suggestion. A runtime error marks that node as failed with a message naming the expression and
              the field, and follows the node&apos;s normal retry and skip handling. Expressions only transform data:
              they cannot call code, read files or reach the network.
            </p>
            <div className="overflow-x-auto rounded-lg border border-border">
              <table className="w-full min-w-[24rem] text-left text-sm">
                <thead className="bg-muted/40 text-xs uppercase tracking-wide text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2 font-semibold">Limit</th>
                    <th className="px-3 py-2 font-semibold">Value</th>
                  </tr>
                </thead>
                <tbody>
                  {LIMITS.map((l) => (
                    <tr key={l.limit} className="border-t border-border align-top">
                      <td className="px-3 py-2 text-foreground">{l.limit}</td>
                      <td className="px-3 py-2 text-muted-foreground">{l.value}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              Going over a limit is a normal expression error on that node, never a hang. If an expression reads an
              upstream output that was too large to keep in full, it fails with a message asking you to shrink it with a
              Transform node rather than silently reading part of it.
            </p>
          </section>

          <section className="border-t border-border pt-8">
            <SectionLabel>Related</SectionLabel>
            <ul className="mt-3 space-y-2 text-sm">
              <li>
                <Link href="/help/workflow-recipes" className="text-primary hover:underline">
                  Industry workflow recipes
                </Link>
              </li>
              <li>
                <Link href="/docs" className="text-primary hover:underline">
                  Platform docs
                </Link>
              </li>
            </ul>
          </section>
        </div>
      </main>
    </div>
  );
}
