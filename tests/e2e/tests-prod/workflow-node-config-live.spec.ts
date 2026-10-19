import { test, expect, type Page, type Locator } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole } from "../helpers/prod-session";
import {
  createWorkflowViaUi,
  putGraph,
  openWorkflow,
  selectNode,
  runWorkflow,
  deleteWorkflow,
  expectRunOutput,
  expectNoRunOutput,
  node,
  edge,
} from "../helpers/workflow-graph";

/**
 * REAL-TENANT workflow NODE CONFIG validation: for each node type, edit its
 * fields through the real config drawer, Save Draft, run the workflow, and
 * check the real output. Not just "the field accepts text" - the assertion
 * in every test is that the configured value changed what the run DID.
 *
 * Deliberately deterministic: Code / Condition / Loop are pure server-side
 * transforms and the HTTP node calls https://example.com, so no LLM variance
 * and no third-party credentials are involved. Nothing here sends an email,
 * Telegram or WhatsApp message - the Output node's non-log channels are
 * checked only for the fields they reveal, never executed.
 *
 * Each test builds its own tiny workflow (shell through the real "New
 * workflow" UI, graph over the same PUT the canvas's Save Draft uses - see
 * helpers/workflow-graph.ts for why not canvas drag-and-drop) and deletes it
 * afterwards, so repeated runs don't pile up workflows in the tenant.
 *
 * Semantics asserted here were read from the engine, not guessed:
 * WorkflowNodeExecutor (Code ops are fixed string transforms, Condition
 * routes on sourceHandle "true"/"false" with a case-insensitive contains,
 * HTTP non-2xx is SKIPPED with the input passed through unless the
 * "treat non-2xx as failures" box is unticked) and WorkflowExecutionEngine
 * (Loop reports "Repeated '<body>' N of M item(s) (capped at K)").
 */

const drawerField = (page: Page, labelText: string | RegExp): Locator =>
  page.locator("div.field").filter({ has: page.getByText(labelText, { exact: typeof labelText === "string" }) });

async function saveDraft(page: Page): Promise<void> {
  const [res] = await Promise.all([
    page.waitForResponse(
      (r) => r.request().method() === "PUT" && /\/api\/workflows\/[0-9a-f-]{36}$/.test(new URL(r.url()).pathname),
      { timeout: 20_000 }
    ),
    page.getByRole("button", { name: /^save draft$/i }).click(),
  ]);
  expect(res.status(), "Save Draft's PUT failed").toBeLessThan(300);
}

async function pickOption(page: Page, field: Locator, optionName: string): Promise<void> {
  await field.locator("button.select-trigger").click();
  await page.getByRole("option", { name: optionName, exact: true }).click();
}

/** A workflow-scoped harness: creates the shell, PUTs the graph, opens the canvas, and always cleans up. */
async function withWorkflow(
  page: Page,
  testInfo: Parameters<typeof requireUserCredentials>[0],
  label: string,
  nodes: ReturnType<typeof node>[],
  edges: ReturnType<typeof edge>[],
  body: (id: string, name: string) => Promise<void>
): Promise<void> {
  requireUserCredentials(testInfo);
  requireMutationOptIn(testInfo);
  testInfo.skip(testInfo.project.name !== "chromium", "writes real data - one project only");
  await signInToConsole(page);
  const name = `e2e-nodecfg-${label}-${Date.now()}`;
  const id = await createWorkflowViaUi(page, name);
  try {
    await putGraph(page, id, name, nodes, edges);
    await openWorkflow(page, id);
    await body(id, name);
  } finally {
    await deleteWorkflow(page, id);
  }
}

test.describe("Workflow node config fields - drawer edits change real run behavior (live production scenario)", () => {
  test.setTimeout(6 * 60 * 1000);

  test("Code node: every operation shows its own fields and produces the right output", async ({ page }, testInfo) => {
    await withWorkflow(
      page,
      testInfo,
      "Code",
      [node("t1", "trigger", { channel: null }, "Trigger"), node("c1", "code", { operation: "uppercase" }, "Code"), node("o1", "output", { channel: "log" }, "Output")],
      [edge("t1", "c1"), edge("c1", "o1")],
      async (id) => {
        const cases: Array<{
          op: string;
          prompt: string;
          fill?: () => Promise<void>;
          expected: RegExp | string;
          visible: string[];
          hidden: string[];
        }> = [
          { op: "Uppercase", prompt: "Hello World", expected: "HELLO WORLD", visible: [], hidden: ["e.g. user.name", "e.g. \\s+", "{{input}}"] },
          { op: "Lowercase", prompt: "Hello World", expected: "hello world", visible: [], hidden: ["e.g. user.name", "e.g. \\s+"] },
          {
            op: "Regex replace",
            prompt: "Hello World",
            fill: async () => {
              await page.getByPlaceholder("e.g. \\s+", { exact: true }).fill("o");
              await page.getByPlaceholder("e.g. a single space", { exact: true }).fill("0");
            },
            expected: "Hell0 W0rld",
            visible: ["e.g. \\s+", "e.g. a single space"],
            hidden: ["e.g. user.name"],
          },
          {
            op: "Template",
            prompt: '{"name":"Ada"}',
            fill: async () => {
              await page.getByPlaceholder("{{input}}", { exact: true }).fill("Hi {{name}}!");
            },
            expected: "Hi Ada!",
            visible: ["{{input}}"],
            hidden: ["e.g. user.name", "e.g. \\s+"],
          },
          {
            op: "Extract JSON field",
            prompt: '{"user":{"name":"Zed"},"n":2}',
            fill: async () => {
              await page.getByPlaceholder("e.g. user.name", { exact: true }).fill("user.name");
            },
            expected: "Zed",
            visible: ["e.g. user.name"],
            hidden: ["e.g. \\s+"],
          },
          { op: "Stringify to JSON", prompt: "Hello World", expected: '"Hello World"', visible: [], hidden: ["e.g. user.name"] },
          { op: "Pretty-print JSON", prompt: '{"name":"Ada"}', expected: /"name"\s+:\s+"Ada"/, visible: [], hidden: ["e.g. user.name"] },
        ];

        for (const c of cases) {
          await selectNode(page, "c1");
          await pickOption(page, drawerField(page, "Operation"), c.op);
          // Fields must appear/disappear with the chosen operation.
          for (const ph of c.visible) {
            await expect(page.getByPlaceholder(ph, { exact: true }), `${c.op}: expected field '${ph}' to be shown`).toBeVisible();
          }
          for (const ph of c.hidden) {
            await expect(page.getByPlaceholder(ph, { exact: true }), `${c.op}: field '${ph}' should not be shown`).toHaveCount(0);
          }
          if (c.fill) await c.fill();
          await saveDraft(page);

          const status = await runWorkflow(page, c.prompt);
          expect(status, `${c.op}: run should complete`).toBe("COMPLETED");
          await expectRunOutput(page, id, c.expected, `${c.op}: expected output ${c.expected} never appeared in the run`);
        }
      }
    );
  });

  test("Condition node: the 'contains' field decides which branch runs, case-insensitively, and edits re-route", async ({
    page,
  }, testInfo) => {
    await withWorkflow(
      page,
      testInfo,
      "Condition",
      [
        node("t1", "trigger", { channel: null }, "Trigger"),
        node("k1", "condition", { contains: "urgent" }, "Condition"),
        node("ct", "code", { operation: "template", template: "TRUE-BRANCH:{{input}}" }, "IfTrue"),
        node("cf", "code", { operation: "template", template: "FALSE-BRANCH:{{input}}" }, "IfFalse"),
        node("ot", "output", { channel: "log" }, "OutTrue"),
        node("of", "output", { channel: "log" }, "OutFalse"),
      ],
      [edge("t1", "k1"), edge("k1", "ct", "true"), edge("k1", "cf", "false"), edge("ct", "ot"), edge("cf", "of")],
      async (id) => {
        const expectBranch = async (prompt: string, taken: "TRUE" | "FALSE") => {
          const other = taken === "TRUE" ? "FALSE" : "TRUE";
          const status = await runWorkflow(page, prompt);
          expect(status).toBe("COMPLETED");
          await expectRunOutput(page, id, `${taken}-BRANCH:${prompt}`, `"${prompt}" should have taken the ${taken} branch`);
          await expectNoRunOutput(page, id, `${other}-BRANCH:`, `"${prompt}" must NOT have run the ${other} branch`);
        };

        // Configured via API to "urgent": case-insensitive substring match.
        await expectBranch("This is URGENT", "TRUE");
        await expectBranch("all calm here", "FALSE");

        // Now change the field through the REAL drawer and prove routing follows it.
        await selectNode(page, "k1");
        const contains = page.getByPlaceholder("e.g. refund", { exact: true });
        await expect(contains, "condition drawer's contains field never rendered").toBeVisible();
        await expect(contains, "drawer did not load the saved value").toHaveValue("urgent");
        await contains.fill("calm");
        await saveDraft(page);

        await expectBranch("all calm here", "TRUE");
        await expectBranch("urgent stuff", "FALSE");
      }
    );
  });

  test("HTTP node: URL / save-as / non-2xx handling / headers persist and change real request behavior", async ({
    page,
  }, testInfo) => {
    await withWorkflow(
      page,
      testInfo,
      "HTTP",
      [node("t1", "trigger", { channel: null }, "Trigger"), node("h1", "http", { method: "GET", url: "" }, "HTTP"), node("o1", "output", { channel: "log" }, "Output")],
      [edge("t1", "h1"), edge("h1", "o1")],
      async (id) => {
        const url = page.getByPlaceholder("https://api.example.com/v1/items?q={{input}}", { exact: true });

        // 1) A real GET, response body becomes the output.
        await selectNode(page, "h1");
        await url.fill("https://example.com");
        await saveDraft(page);
        expect(await runWorkflow(page, "go")).toBe("COMPLETED");
        await expectRunOutput(page, id, /Example Domain/, "GET https://example.com output never appeared");

        // 2) A real 404 with the default 'treat non-2xx as failures': the node
        //    is skipped and its INPUT passes through untouched.
        await selectNode(page, "h1");
        await url.fill("https://example.com/e2e-this-path-does-not-exist");
        await saveDraft(page);
        expect(await runWorkflow(page, "ping-404-skip")).toBe("COMPLETED");
        await expectRunOutput(page, id, "ping-404-skip", "skipped HTTP node should have passed its input through as the output");

        // 3) Untick that box and set Save-as: the same 404 is now reported,
        //    with the input's own fields preserved next to the saved response.
        await selectNode(page, "h1");
        await page.locator("label").filter({ hasText: /treat non-2xx responses as failures/i }).locator('input[type="checkbox"]').uncheck();
        await page.getByPlaceholder("e.g. apiResponse (optional)", { exact: true }).fill("resp");
        await saveDraft(page);
        expect(await runWorkflow(page, "ping-404-report")).toBe("COMPLETED");
        await expectRunOutput(page, id, /"status"\s*:\s*404/, "with the box unticked the 404 response should be saved into the output");
        await expectRunOutput(page, id, /"resp"\s*:/, "Save-as field name missing from the output");

        // 4) Headers and query rows survive a full reload (real round trip
        //    through the API, not just local component state).
        await selectNode(page, "h1");
        await page.getByRole("button", { name: /^add header$/i }).click();
        await page.getByPlaceholder("Authorization", { exact: true }).fill("X-E2E-Test");
        await page.getByPlaceholder("Bearer {{secret.MY_API_KEY}}", { exact: true }).fill("round-trip");
        await page.getByRole("button", { name: /^add parameter$/i }).click();
        await page.getByPlaceholder("page", { exact: true }).fill("q");
        await page.getByPlaceholder("1", { exact: true }).fill("42");
        await saveDraft(page);

        await openWorkflow(page, id);
        await selectNode(page, "h1");
        await expect(page.getByPlaceholder("Authorization", { exact: true })).toHaveValue("X-E2E-Test");
        await expect(page.getByPlaceholder("Bearer {{secret.MY_API_KEY}}", { exact: true })).toHaveValue("round-trip");
        await expect(page.getByPlaceholder("page", { exact: true })).toHaveValue("q");
        await expect(page.getByPlaceholder("1", { exact: true })).toHaveValue("42");
        await expect(page.getByPlaceholder("e.g. apiResponse (optional)", { exact: true })).toHaveValue("resp");

        // Remove works too.
        await page.getByRole("button", { name: /^remove$/i }).first().click();
        await expect(page.getByPlaceholder("Authorization", { exact: true })).toHaveCount(0);
      }
    );
  });

  test("Output node: each channel reveals its own fields, values persist, and Log-only runs for real", async ({
    page,
  }, testInfo) => {
    await withWorkflow(
      page,
      testInfo,
      "Output",
      [node("t1", "trigger", { channel: null }, "Trigger"), node("o1", "output", { channel: "log" }, "Output")],
      [edge("t1", "o1")],
      async (id) => {
        const channel = page.getByTestId("output-channel-select");
        const choose = async (name: string) => {
          await selectNode(page, "o1");
          await channel.click();
          await page.getByRole("option", { name, exact: true }).click();
        };

        await choose("Email");
        await expect(page.getByPlaceholder("you@example.com", { exact: true }), "Email channel: address field").toBeVisible();
        await choose("Telegram");
        await expect(page.getByPlaceholder("e.g. 123456789", { exact: true }), "Telegram channel: chat id field").toBeVisible();
        await expect(page.getByPlaceholder("you@example.com", { exact: true })).toHaveCount(0);
        await choose("WhatsApp");
        await expect(page.getByPlaceholder("+15551234567", { exact: true }), "WhatsApp channel: number field").toBeVisible();
        await choose("Google Drive");
        await expect(page.getByPlaceholder("Leave blank to upload to Drive root", { exact: true }), "Drive channel: folder field").toBeVisible();
        await expect(page.getByPlaceholder("workflow-output.txt", { exact: true })).toBeVisible();
        await choose("Local file (downloadable from this run)");
        await expect(page.getByTestId("output-filetype-select"), "File channel: type select").toBeVisible();
        await expect(page.getByPlaceholder("workflow-output.txt", { exact: true })).toBeVisible();

        // Persist one channel's value across a reload (real API round trip).
        await choose("Email");
        await page.getByPlaceholder("you@example.com", { exact: true }).fill("qa-nodecfg@example.com");
        await saveDraft(page);
        await openWorkflow(page, id);
        await selectNode(page, "o1");
        await expect(page.getByPlaceholder("you@example.com", { exact: true })).toHaveValue("qa-nodecfg@example.com");

        // Back to Log only and run for real - this is the one channel that
        // is safe to execute (no external message is sent).
        await choose("Log only");
        await saveDraft(page);
        expect(await runWorkflow(page, "output-log-run")).toBe("COMPLETED");
        await expectRunOutput(page, id, "output-log-run", "the Log-only run's output never appeared");
      }
    );
  });

  test("Loop node: Max iterations really caps how many items the body runs on", async ({ page }, testInfo) => {
    await withWorkflow(
      page,
      testInfo,
      "Loop",
      [
        node("t1", "trigger", { channel: null }, "Trigger"),
        node("l1", "loop", { itemsField: "", maxIterations: 25 }, "Loop"),
        node("c1", "code", { operation: "uppercase" }, "Shout"),
        node("o1", "output", { channel: "log" }, "Output"),
      ],
      [edge("t1", "l1"), edge("l1", "c1"), edge("c1", "o1")],
      async (id) => {
        const items = '["a","b","c","d","e"]';

        // Uncapped (25 >= 5): every item is processed and collected.
        expect(await runWorkflow(page, items)).toBe("COMPLETED");
        await expectRunOutput(page, id, /Repeated 'Shout' 5 of 5 item\(s\)\./, "loop should have run all 5 items");
        await expectRunOutput(page, id, '["A","B","C","D","E"]', "loop output should hold all 5 shouted items");

        // Cap it through the REAL drawer and prove the run obeys.
        await selectNode(page, "l1");
        const max = page.locator('input[type="number"]').first();
        await expect(max, "drawer did not load the saved cap").toHaveValue("25");
        await max.fill("2");
        await saveDraft(page);

        expect(await runWorkflow(page, items)).toBe("COMPLETED");
        await expectRunOutput(page, id, /Repeated 'Shout' 2 of 5 item\(s\) \(capped at 2\)\./, "loop ignored the Max iterations cap");
        await expectRunOutput(page, id, '["A","B"]', "capped loop output should hold only the first 2 shouted items");
      }
    );
  });
});
