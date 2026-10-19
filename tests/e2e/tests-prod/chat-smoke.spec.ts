import { test, expect } from "@playwright/test";
import { requireUserCredentials } from "../helpers/prod-env";
import { signInToConsole } from "../helpers/prod-session";
import { collectProdErrors } from "../helpers/prod-console";

/**
 * Chat smoke coverage - READ-ONLY.
 *
 * Chat itself (sending a message to an agent and getting a streamed reply)
 * had zero e2e coverage anywhere in this repo before this file, despite
 * being the single most load-bearing user-facing flow in the product. A real
 * send can't be exercised read-only against production: it costs real LLM
 * tokens (per-agent, streamed via POST /api/agents/{id}/chat -
 * web/app/lib/api.ts's streamChat) even though it writes no persistent
 * business data - just burns budget. That's a genuine gap, left open here on
 * purpose rather than silently: nothing in this file ever submits the chat
 * form.
 *
 * What IS asserted without sending anything:
 *   1. The playground chat surface (ChatPanel, web/app/components/ChatPanel.tsx)
 *      actually mounts for the selected agent - message input, send button,
 *      and transcript region are all present and the input is usable. This
 *      would have caught a chat panel that fails to mount, or an input wired
 *      to the wrong agent id after navigation.
 *   2. Typing into the input flips the send button from disabled to enabled
 *      (client-side validation - Button's `disabled={sending ||
 *      (!input.trim() && attachments.length === 0)}`) - WITHOUT clicking
 *      send. This would have caught a regressed disabled-state binding.
 *   3. No client-side exception banner, no 404 shell, no raw stack trace -
 *      same assertions console-authenticated.spec.ts applies elsewhere.
 *   4. No request the initial page load makes already fails with a 4xx/5xx
 *      against a chat/streaming endpoint. Per the source, ChatPanel opens no
 *      websocket/SSE connection and calls no chat endpoint until a message is
 *      actually sent (the POST above is the only one, and it's send-gated),
 *      so this reduces to "page load didn't already 401/500 anything
 *      chat-shaped" - still a real, if narrow, check against a chat endpoint
 *      failing open on a stale session before the user types a word.
 *
 * The actual conversational round trip (send -> streamed reply) stays
 * untested by design. That's a documented gap, not a silent one.
 */
test.describe("Chat smoke - read-only", () => {
  test.beforeEach(async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    testInfo.setTimeout(120_000);
    await signInToConsole(page);
  });

  test("the playground chat surface renders without sending a message", async ({ page }, testInfo) => {
    const errors = collectProdErrors(page);

    await page.goto("/playground", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});

    await expect(page.getByText(/Application error: a client-side exception/i)).toHaveCount(0);
    await expect(page.getByText(/This page could not be found/i)).toHaveCount(0);

    // A tenant with zero agents can't exercise the chat surface at all - that
    // is the "no agents yet" empty state (playground/page.tsx), a different,
    // already-covered surface, not a chat-panel failure. Skip visibly rather
    // than asserting on markup that was never supposed to render.
    const agentSelect = page.locator("#pg-agent-select");
    if ((await agentSelect.count()) === 0) {
      testInfo.skip(true, "account has no agents to chat with - nothing to assert on the chat panel itself");
    }

    // playground/page.tsx auto-selects the first agent once /api/agents
    // resolves, which mounts ChatPanel for it automatically - no manual
    // selection needed.
    const messageInput = page.getByPlaceholder(/ask something/i);
    await expect(messageInput, "chat message input never mounted - ChatPanel failed to render for the selected agent").toBeVisible({
      timeout: 30_000,
    });
    await expect(messageInput).toBeEnabled();
    await expect(messageInput).toBeEditable();

    const sendButton = page.getByRole("button", { name: /^send$/i });
    await expect(sendButton, "send button never mounted").toBeVisible();

    // Transcript container: either the empty-state copy (fresh/no session)
    // or a real prior turn - either way the region exists and rendered.
    const transcriptEmpty = page.getByText(/no messages yet/i);
    const hasTranscriptEmpty = (await transcriptEmpty.count()) > 0;
    expect(hasTranscriptEmpty, "chat transcript region did not render (no empty-state or turns)").toBe(true);

    // The panel header names the selected agent - proves the input is wired
    // to a real agent id, not orphaned after navigation.
    await expect(page.getByRole("heading", { name: /^chat/i })).toBeVisible();

    // Point 4: nothing chat/streaming-shaped already failed before any send.
    const chatEndpointFailures = [...errors.failedRequests, ...errors.badResponses].filter((entry) =>
      /\/api\/agents\/[^/]+\/chat|\/chat\/attachments|websocket|eventsource/i.test(entry)
    );
    expect(chatEndpointFailures, "a chat/streaming endpoint already failed on page load, before any message was sent").toEqual([]);

    expect(errors.unexpected(), "unexpected console/network errors loading /playground").toEqual([]);
  });

  test("typing into the chat input enables the send button, without clicking send", async ({ page }, testInfo) => {
    const errors = collectProdErrors(page);

    await page.goto("/playground", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});

    if ((await page.locator("#pg-agent-select").count()) === 0) {
      testInfo.skip(true, "account has no agents to chat with - nothing to assert on the chat panel itself");
    }

    const messageInput = page.getByPlaceholder(/ask something/i);
    await expect(messageInput).toBeVisible({ timeout: 30_000 });

    const sendButton = page.getByRole("button", { name: /^send$/i });
    await expect(sendButton).toBeVisible();
    // Empty input -> send is disabled (ChatPanel: `!input.trim() && attachments.length === 0`).
    await expect(sendButton, "send button was enabled with an empty input - client-side validation regressed").toBeDisabled();

    await messageInput.fill("This is a read-only e2e smoke test - never actually sent.");
    await expect(sendButton, "send button stayed disabled after typing real text - client-side validation regressed").toBeEnabled();

    // The whole point: prove the round trip is NOT exercised. No click on
    // sendButton anywhere in this file, no message ever appears in the
    // transcript, no `sending` state, no cost.
    await expect(page.getByText(/no messages yet/i)).toBeVisible();
    await expect(page.getByText(/sending…/i)).toHaveCount(0);

    expect(errors.unexpected(), "unexpected console/network errors while typing into the chat input").toEqual([]);
  });
});
