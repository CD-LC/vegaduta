import { expect, test as base, type APIRequestContext, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";

/**
 * Personal edition: turn quality, workflow create/edit/run, and knowledge upload + retrieval, against a running
 * personal stack. Same owner fixture and gating as personal-agent.spec.ts.
 *
 *   E2E_BASE_URL=http://localhost:3000 E2E_API_BASE_URL=http://localhost:8080 \
 *   E2E_PERSONAL_EMAIL=<owner e-mail> E2E_PERSONAL_PASSWORD=<owner password> \
 *   npx playwright test -c playwright.personal.config.ts
 *
 * Without the owner credentials every check skips. Every workflow and knowledge collection a test creates is
 * removed in a finally block. Assertions read returned values (node outputs, chunk counts, search hits), not just
 * status codes.
 */
const PERSONAL = process.env.E2E_EDITION === "personal";
const OWNER_EMAIL = process.env.E2E_PERSONAL_EMAIL || "";
const OWNER_PASSWORD = process.env.E2E_PERSONAL_PASSWORD || "";
const HAS_OWNER = !!(OWNER_EMAIL && OWNER_PASSWORD);

const SKIP_REASON =
  "personal turns/workflows/knowledge suite: run it with playwright.personal.config.ts against a personal stack";
const NEEDS_OWNER =
  "set E2E_PERSONAL_EMAIL and E2E_PERSONAL_PASSWORD (the owner after first-run setup)";

const FIXTURE = path.resolve(__dirname, "../fixtures/knowledge-mock-handbook.txt");

const API = process.env.E2E_API_BASE_URL || "http://localhost:8080";

type Owner = Page & { api: APIRequestContext };

/**
 * The owner fixture signs in through the web origin (cookie session) and also captures the bearer
 * access token from the sign-in body. Core API calls go through `owner.api`, a context that sends
 * `Authorization: Bearer <accessToken>`: the core API rejects cookie-only requests with 401.
 */
const test = base.extend<{ owner: Owner }>({
  owner: async ({ page, baseURL, playwright }, use) => {
    await page
      .context()
      .addCookies([
        {
          name: "NEXT_LOCALE",
          value: "en",
          url: baseURL || "http://localhost:3000",
        },
      ]);
    const res = await page.request.post("/api/local-auth/login", {
      data: { identifier: OWNER_EMAIL, password: OWNER_PASSWORD },
    });
    expect(res.status(), "owner sign-in (is first-run setup finished?)").toBe(
      200,
    );
    const body = (await res.json()) as { accessToken?: string };
    expect(body.accessToken, "sign-in response carried no accessToken").toBeTruthy();
    const api = await playwright.request.newContext({
      baseURL: API,
      extraHTTPHeaders: { Authorization: `Bearer ${body.accessToken}` },
    });
    const owner = Object.assign(page, { api }) as Owner;
    try {
      await use(owner);
    } finally {
      await api.dispose();
    }
  },
});

const gate = () => {
  test.skip(!PERSONAL, SKIP_REASON);
  test.skip(!HAS_OWNER, NEEDS_OWNER);
};

// ---------------------------------------------------------------- shared helpers

type Trace = {
  nodeId: string;
  nodeType: string;
  label: string;
  status: string;
  summary: string;
  logLines?: string[];
};
type Run = {
  id: string;
  status: string;
  errorMessage?: string | null;
  trace: Trace[];
  workflowVersion?: number;
};

const step = (r: Run, nodeId: string) => r.trace.find((t) => t.nodeId === nodeId);

type GraphNode = { id: string; type: string; label: string; config: Record<string, unknown> };
type GraphEdge = { source: string; target: string; sourceHandle?: string | null };

const node = (
  id: string,
  type: string,
  config: Record<string, unknown>,
  label: string,
): GraphNode => ({ id, type, label, config });
const edge = (source: string, target: string): GraphEdge => ({
  source,
  target,
  sourceHandle: null,
});

function graphBody(name: string, nodes: GraphNode[], edges: GraphEdge[]) {
  return {
    name,
    description: "",
    graph: {
      nodes: nodes.map((n, i) => ({
        id: n.id,
        type: n.type,
        position: { x: i * 260, y: 0 },
        data: { label: n.label, config: n.config },
      })),
      edges: edges.map((e) => ({
        id: `e-${e.source}-x-${e.target}`,
        source: e.source,
        target: e.target,
        sourceHandle: e.sourceHandle ?? null,
        targetHandle: null,
      })),
    },
    status: "DRAFT",
  };
}

async function createWorkflow(owner: Owner, name: string, nodes: GraphNode[], edges: GraphEdge[]) {
  const created = await owner.api.post(`${API}/api/workflows`, {
    data: { name, description: "", graph: { nodes: [], edges: [] } },
  });
  expect(created.status(), `create workflow: ${(await created.text()).slice(0, 200)}`).toBeLessThan(300);
  const id = (await created.json()).id as string;
  const put = await owner.api.put(`${API}/api/workflows/${id}`, {
    data: graphBody(name, nodes, edges),
  });
  expect(put.status(), `save graph: ${(await put.text()).slice(0, 200)}`).toBeLessThan(300);
  return id;
}

async function runWorkflow(owner: Owner, id: string, input: string): Promise<Run> {
  const res = await owner.api.post(`${API}/api/workflows/${id}/run`, { data: { input } });
  const text = await res.text();
  expect(res.status(), `run failed: ${text.slice(0, 300)}`).toBeLessThan(300);
  return JSON.parse(text) as Run;
}

async function deleteWorkflow(owner: Owner, id: string) {
  await owner.api.delete(`${API}/api/workflows/${id}`).catch(() => {});
}

// ---------------------------------------------------------------- turn quality

test.describe("personal turns: quality", () => {
  test.beforeEach(() => gate());

  test("a reply to a prompt with one correct answer carries that answer", async ({ owner }) => {
    test.setTimeout(180_000);
    await owner.goto("/assistant");
    const composer = owner.getByLabel(/ask or tell me what to do/i).first();
    await expect(composer).toBeEnabled({ timeout: 30_000 });
    await composer.fill("What is 7 times 6? Reply with only the number, no other words.");
    await composer.press("Enter");
    const reply = owner.locator('[data-role="assistant"]').last();
    await expect(reply).toBeVisible({ timeout: 150_000 });
    await expect(reply).toContainText("42", { timeout: 150_000 });
    await expect(reply, "reply should not be a refusal or an error").not.toContainText(
      /something went wrong|error/i,
    );
  });

  test("the turn inspector reports a non-zero model-call count and its prompt-block breakdown", async ({
    owner,
  }) => {
    test.setTimeout(180_000);
    await owner.goto("/assistant");
    const composer = owner.getByLabel(/ask or tell me what to do/i).first();
    await expect(composer).toBeEnabled({ timeout: 30_000 });
    await composer.fill("Say hi.");
    await composer.press("Enter");
    const reply = owner.locator('[data-role="assistant"]').last();
    await expect(reply).toBeVisible({ timeout: 150_000 });

    const inspector = owner.getByRole("button", { name: /model call|call.*token/i }).last();
    await expect(inspector, "no turn inspector: reply carried no request snapshots").toBeVisible({
      timeout: 150_000,
    });
    // The summary states how many model calls the turn made; a completed reply made at least one.
    const summary = (await inspector.innerText()).trim();
    const calls = summary.match(/(\d+)\s*(?:model\s*)?calls?/i);
    expect(calls, `inspector summary has no call count: "${summary}"`).not.toBeNull();
    expect(Number(calls![1]), "a completed turn made no model calls").toBeGreaterThanOrEqual(1);

    await inspector.click();
    // The prompt-block heading renders inside an expanded message row: open the system prompt first.
    await owner
      .locator("button[aria-expanded]")
      .filter({ hasText: /System prompt/i })
      .first()
      .click();
    await expect(owner.getByText(/Made of these prompt blocks|Request breakdown/i)).toBeVisible();
  });

  test("a completed turn offers feedback controls and a rating is accepted by the API", async ({ owner }) => {
    test.setTimeout(180_000);
    await owner.goto("/assistant");
    const composer = owner.getByLabel(/ask or tell me what to do/i).first();
    await expect(composer).toBeEnabled({ timeout: 30_000 });
    await composer.fill("Say hi.");
    await composer.press("Enter");
    const reply = owner.locator('[data-role="assistant"]').last();
    await expect(reply).toBeVisible({ timeout: 150_000 });

    // Feedback controls appear once the turn has finished; the reply box is visible while it still streams.
    await expect(reply.getByTestId("worked-for")).toBeVisible({ timeout: 150_000 });
    const thumbsUp = owner
      .getByRole("button", { name: /good|helpful|thumbs up/i })
      .first();
    await expect(thumbsUp, "no feedback control on the completed turn").toBeVisible({ timeout: 150_000 });
    const feedback = owner.waitForResponse(
      (r) => /\/api\/agents\/[^/]+\/turns\/feedback$/.test(r.url()) && r.request().method() === "POST",
      { timeout: 30_000 },
    );
    await thumbsUp.click();
    const res = await feedback;
    // The 201 body is not read: Playwright's body stream stalls on this response. The status and the
    // posted signal are what prove the rating was accepted.
    expect(res.status(), "feedback rejected").toBe(201);
    expect(res.request().postData() ?? "", "posted feedback does not carry the signal that was clicked").toContain(
      "THUMBS_UP",
    );
  });
});

// ---------------------------------------------------------------- workflows

test.describe("personal workflows: create, edit, run", () => {
  test.beforeEach(() => gate());

  test("a created workflow runs through extract -> template -> uppercase with the expected node outputs", async ({
    owner,
  }) => {
    const name = `E2E Personal Pipeline ${Date.now()}`;
    const nodes = [
      node("t1", "trigger", { channel: null }, "Trigger"),
      node("x1", "code", { operation: "extract", field: "customer.name" }, "GetName"),
      node("m1", "code", { operation: "template", template: "Dear {{input}}, your order is ready" }, "Compose"),
      node("u1", "code", { operation: "uppercase" }, "Shout"),
      node("o1", "output", { channel: "log" }, "Output"),
    ];
    const edges = [edge("t1", "x1"), edge("x1", "m1"), edge("m1", "u1"), edge("u1", "o1")];
    const id = await createWorkflow(owner, name, nodes, edges);
    try {
      const run = await runWorkflow(owner, id, '{"customer":{"name":"Ada Lovelace"},"total":42}');
      expect(run.status).toBe("COMPLETED");
      expect(run.trace.map((t) => t.nodeId), "steps must run in graph order").toEqual(["t1", "x1", "m1", "u1", "o1"]);
      expect(step(run, "x1")!.summary).toContain("Ada Lovelace");
      expect(step(run, "m1")!.summary).toContain("Dear Ada Lovelace, your order is ready");
      expect(step(run, "u1")!.summary).toContain("DEAR ADA LOVELACE, YOUR ORDER IS READY");

      // Run history shows this run with its own output.
      await owner.goto(`/workflows/${id}/runs`, { waitUntil: "domcontentloaded" });
      await expect(owner.getByText(`#${run.id.slice(0, 8)}`).first()).toBeVisible({ timeout: 30_000 });
    } finally {
      await deleteWorkflow(owner, id);
    }
  });

  test("editing a node's config changes the next run's output, and the run records the newer version", async ({
    owner,
  }) => {
    const name = `E2E Personal Edit ${Date.now()}`;
    const nodes = [
      node("t1", "trigger", { channel: null }, "Trigger"),
      node("x1", "code", { operation: "extract", field: "customer.name" }, "GetName"),
      node("m1", "code", { operation: "template", template: "Dear {{input}}, your order is ready" }, "Compose"),
      node("u1", "code", { operation: "uppercase" }, "Shout"),
      node("o1", "output", { channel: "log" }, "Output"),
    ];
    const id = await createWorkflow(owner, name, nodes, [
      edge("t1", "x1"),
      edge("x1", "m1"),
      edge("m1", "u1"),
      edge("u1", "o1"),
    ]);
    try {
      const before = await runWorkflow(owner, id, '{"customer":{"name":"Ada"},"total":42}');
      expect(step(before, "u1")!.summary).toContain("DEAR ADA, YOUR ORDER IS READY");

      // Edit: extract a different field, a different template, lowercase instead of uppercase.
      const edited = [
        nodes[0],
        node("x1", "code", { operation: "extract", field: "total" }, "GetTotal"),
        node("m1", "code", { operation: "template", template: "TOTAL DUE: {{input}} USD" }, "Compose"),
        node("u1", "code", { operation: "lowercase" }, "Shout"),
        nodes[4],
      ];
      const edges = [edge("t1", "x1"), edge("x1", "m1"), edge("m1", "u1"), edge("u1", "o1")];
      const put = await owner.api.put(`${API}/api/workflows/${id}`, { data: graphBody(name, edited, edges) });
      expect(put.status(), `edit failed: ${(await put.text()).slice(0, 200)}`).toBeLessThan(300);

      const after = await runWorkflow(owner, id, '{"customer":{"name":"Ada"},"total":42}');
      expect(after.status).toBe("COMPLETED");
      expect(step(after, "u1")!.summary, "edited config did not change the output").toContain("total due: 42 usd");
      expect(step(after, "u1")!.summary).not.toContain("DEAR");
      if (before.workflowVersion != null && after.workflowVersion != null) {
        expect(after.workflowVersion, "editing the graph should bump the run's version").toBeGreaterThan(
          before.workflowVersion,
        );
      }
    } finally {
      await deleteWorkflow(owner, id);
    }
  });
});

// ---------------------------------------------------------------- knowledge

/** Reads the fixture and stamps a unique token into it, so the retrieval check cannot match stale content. */
function generateMockDocument(): { token: string; filename: string; buffer: Buffer } {
  const token = `KB-TOKEN-${Date.now().toString(36).toUpperCase()}`;
  const text = readFileSync(FIXTURE, "utf8").replaceAll("{{TOKEN}}", token);
  return { token, filename: `e2e-handbook-${token.toLowerCase()}.md`, buffer: Buffer.from(text, "utf8") };
}

async function createCollection(owner: Owner, name: string): Promise<string> {
  const res = await owner.api.post(`${API}/api/knowledge/collections`, {
    data: { name, description: "created by the personal turns/workflows/knowledge spec" },
  });
  expect(res.status(), `create collection: ${(await res.text()).slice(0, 200)}`).toBeLessThan(300);
  return (await res.json()).id as string;
}

async function deleteCollection(owner: Owner, collectionId: string) {
  const docs = await owner.api.get(`${API}/api/knowledge/collections/${collectionId}/documents`);
  if (docs.ok()) {
    for (const d of (await docs.json()) as Array<{ id: string }>) {
      await owner.api.delete(`${API}/api/knowledge/documents/${d.id}`).catch(() => {});
    }
  }
  await owner.api.delete(`${API}/api/knowledge/collections/${collectionId}`).catch(() => {});
}

test.describe("personal knowledge: upload and retrieval", () => {
  test.beforeEach(() => gate());

  test("a generated text document uploads, becomes READY with chunks, and is retrieved by its unique token", async ({
    owner,
  }) => {
    test.setTimeout(180_000);
    const doc = generateMockDocument();
    const collectionId = await createCollection(owner, `E2E Personal KB ${Date.now()}`);
    try {
      const up = await owner.api.post(`${API}/api/knowledge/upload`, {
        multipart: {
          file: { name: doc.filename, mimeType: "text/markdown", buffer: doc.buffer },
          collectionId,
        },
      });
      const upText = await up.text();
      expect(up.status(), `upload failed: ${upText.slice(0, 300)}`).toBeLessThan(300);
      const ingested = JSON.parse(upText) as { chunkCount: number; documentId: string | null };
      expect(ingested.chunkCount, "upload produced no chunks").toBeGreaterThan(0);
      expect(ingested.documentId, "upload did not return a document id").toBeTruthy();

      // The document must reach READY (ingestion is asynchronous on some stacks).
      await expect
        .poll(
          async () => {
            const list = await owner.api.get(`${API}/api/knowledge/collections/${collectionId}/documents`);
            const found = ((await list.json()) as Array<{ id: string; status: string; errorMessage?: string }>).find(
              (d) => d.id === ingested.documentId,
            );
            if (found?.status === "FAILED") throw new Error(`ingestion failed: ${found.errorMessage ?? "no message"}`);
            return found?.status ?? "missing";
          },
          { timeout: 120_000, intervals: [2_000] },
        )
        .toBe("READY");

      // Retrieval: the unique token must come back from this document, scoped to this collection.
      const search = await owner.api.post(`${API}/api/knowledge/search`, {
        data: { query: `orchard irrigation controller codename ${doc.token}`, topK: 5, collectionIds: [collectionId] },
      });
      expect(search.status()).toBeLessThan(300);
      const hits = (await search.json()) as Array<{ content: string; documentId: string; collectionId: string }>;
      const hit = hits.find((h) => h.content.includes(doc.token));
      expect(hit, `search did not return the token ${doc.token}; got ${hits.length} hit(s)`).toBeTruthy();
      expect(hit!.documentId, "the hit must point back to the uploaded document").toBe(ingested.documentId);
      expect(hit!.collectionId).toBe(collectionId);
    } finally {
      await deleteCollection(owner, collectionId);
    }
  });

  test("retrieval is scoped: the token is not returned from a different collection, and an unsupported file is refused", async ({
    owner,
  }) => {
    test.setTimeout(180_000);
    const doc = generateMockDocument();
    const a = await createCollection(owner, `E2E Personal KB A ${Date.now()}`);
    const b = await createCollection(owner, `E2E Personal KB B ${Date.now()}`);
    try {
      const up = await owner.api.post(`${API}/api/knowledge/upload`, {
        multipart: {
          file: { name: doc.filename, mimeType: "text/markdown", buffer: doc.buffer },
          collectionId: a,
        },
      });
      expect(up.status()).toBeLessThan(300);
      // Wait for the chunks of collection A to be searchable before asserting that B does not see them.
      await expect
        .poll(
          async () => {
            const r = await owner.api.post(`${API}/api/knowledge/search`, {
              data: { query: doc.token, topK: 5, collectionIds: [a] },
            });
            const hits = (await r.json()) as Array<{ content: string }>;
            return hits.some((h) => h.content.includes(doc.token));
          },
          { timeout: 120_000, intervals: [3_000] },
        )
        .toBe(true);

      const other = await owner.api.post(`${API}/api/knowledge/search`, {
        data: { query: doc.token, topK: 5, collectionIds: [b] },
      });
      const otherHits = (await other.json()) as Array<{ content: string }>;
      expect(
        otherHits.some((h) => h.content.includes(doc.token)),
        "a search scoped to another collection returned this document's chunks",
      ).toBe(false);

      const bad = await owner.api.post(`${API}/api/knowledge/upload`, {
        multipart: {
          file: { name: "e2e-unsupported.exe", mimeType: "application/octet-stream", buffer: Buffer.from("MZ") },
          collectionId: a,
        },
      });
      const badText = await bad.text();
      expect(bad.status(), "unsupported file type must be refused").toBeGreaterThanOrEqual(400);
      expect(bad.status()).toBeLessThan(500);
      expect(badText, "refusal should say the type is unsupported").toMatch(/unsupported/i);
    } finally {
      await deleteCollection(owner, a);
      await deleteCollection(owner, b);
    }
  });
});
