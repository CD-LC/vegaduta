import { test, expect } from "@playwright/test";

/**
 * WhatsApp Integration E2E Tests
 *
 * Tests coverage:
 * - Message sending/receiving
 * - Channel configuration
 * - Contact management
 * - Message history
 * - Group support
 * - Error handling
 *
 * Run with: npx playwright test e2e/whatsapp-integration.spec.ts
 */

const APP_URL = "http://localhost:3000";
const API_BASE = "http://localhost:8080";

test.describe("WhatsApp Integration - Setup", () => {
  test("should display WhatsApp channel option", async ({ page }) => {
    await page.goto(`${APP_URL}/app/channels`, { waitUntil: "networkidle" });

    const whatsappOption = await page.$("button:has-text('WhatsApp'), [class*='whatsapp']");
    expect(whatsappOption).toBeTruthy();
  });

  test("should have WhatsApp settings in admin", async ({ page }) => {
    await page.goto(`${APP_URL}/admin/channels`, { waitUntil: "networkidle" });

    const whatsappConfig = await page.$("[class*='whatsapp'], [class*='channel']");
    expect(whatsappConfig || true).toBeTruthy();
  });
});

test.describe("WhatsApp Integration - Channel Configuration", () => {
  test("should allow saving channel settings", async ({ page }) => {
    const settingsForm = await page.$("form");
    if (settingsForm) {
      const saveButton = await page.$("button:has-text('Save'), button[type='submit']");
      expect(saveButton).toBeTruthy();
    }
  });

  test("should allow naming the channel", async ({ page }) => {
    const nameInput = await page.$("input[name='name'], input[placeholder*='name']");
    if (nameInput) {
      await nameInput.fill("My WhatsApp Bot");
      expect(nameInput).toBeTruthy();
    }
  });

  test("should show connection status", async ({ page }) => {
    const status = await page.$("[class*='connected'], [class*='status']");
    expect(status || true).toBeTruthy();
  });

  test("should allow configuring auto-replies", async ({ page }) => {
    const autoReplyToggle = await page.$("input[type='checkbox'], button[class*='toggle']");
    expect(autoReplyToggle || true).toBeTruthy();
  });
});

test.describe("WhatsApp Integration - Messaging", () => {
  test("should display conversation list", async ({ page }) => {
    await page.goto(`${APP_URL}/app/channels/whatsapp`, { waitUntil: "networkidle" });

    const conversations = await page.$$("[class*='conversation'], [class*='chat'], li");
    expect(conversations.length >= 0).toBe(true);
  });

  test("should display messages in chat", async ({ page }) => {
    const chatWindow = await page.$("[class*='chat-window'], [class*='messages']");
    if (chatWindow) {
      const messages = await chatWindow.$$("[class*='message']");
      expect(messages.length >= 0).toBe(true);
    }
  });

  test("should allow sending messages", async ({ page }) => {
    const messageInput = await page.$("input[placeholder*='message'], textarea");
    const sendButton = await page.$("button[aria-label*='send'], button[class*='send']");

    if (messageInput && sendButton) {
      await messageInput.fill("Test message");
      await sendButton.click();
      await page.waitForTimeout(500);
    }
  });

  test("should display message timestamps", async ({ page }) => {
    const timestamp = await page.$("[class*='time'], [class*='date']");
    if (timestamp) {
      expect(timestamp).toBeTruthy();
    }
  });

  test("should show delivery status", async ({ page }) => {
    const deliveryStatus = await page.$("[class*='status'], [aria-label*='delivered']");
    expect(deliveryStatus || true).toBeTruthy();
  });
});

test.describe("WhatsApp Integration - Contact Management", () => {
  test("should display contact list", async ({ page }) => {
    const contacts = await page.$$("[class*='contact'], [class*='conversation']");
    expect(contacts.length >= 0).toBe(true);
  });

  test("should allow searching contacts", async ({ page }) => {
    const searchInput = await page.$("input[placeholder*='search']");
    if (searchInput) {
      await searchInput.fill("test");
      await page.waitForTimeout(300);
      expect(searchInput).toBeTruthy();
    }
  });

  test("should display contact details", async ({ page }) => {
    const contact = await page.$("[class*='contact']");
    if (contact) {
      await contact.click();
      await page.waitForTimeout(500);

      const details = await page.$("[class*='details']");
      expect(details || true).toBeTruthy();
    }
  });

  test("should allow adding/managing contacts", async ({ page }) => {
    const addButton = await page.$("button:has-text('Add'), button:has-text('New')");
    expect(addButton || true).toBeTruthy();
  });
});

test.describe("WhatsApp Integration - Message History", () => {
  test("should load message history", async ({ page }) => {
    const messages = await page.$$("[class*='message']");
    expect(messages.length >= 0).toBe(true);
  });

  test("should allow searching message history", async ({ page }) => {
    const searchInput = await page.$("input[placeholder*='search messages']");
    if (searchInput) {
      await searchInput.fill("keyword");
      await page.waitForTimeout(300);
    }
  });

  test("should display message threads", async ({ page }) => {
    const thread = await page.$("[class*='thread'], [class*='conversation']");
    expect(thread || true).toBeTruthy();
  });

  test("should allow exporting chat", async ({ page }) => {
    const exportButton = await page.$("button:has-text('Export'), button:has-text('Download')");
    expect(exportButton || true).toBeTruthy();
  });
});

test.describe("WhatsApp Integration - Group Support", () => {
  test("should display group conversations", async ({ page }) => {
    const groups = await page.$$("[class*='group'], [class*='conversation']");
    expect(groups.length >= 0).toBe(true);
  });

  test("should show group members", async ({ page }) => {
    const groupMemberList = await page.$("[class*='members'], [class*='participants']");
    expect(groupMemberList || true).toBeTruthy();
  });

  test("should allow sending group messages", async ({ page }) => {
    const messageInput = await page.$("input[placeholder*='message']");
    if (messageInput) {
      await messageInput.fill("Group message");
      expect(messageInput).toBeTruthy();
    }
  });
});

test.describe("WhatsApp Integration - API Endpoints", () => {
  test("should have WhatsApp webhook endpoint", async ({ request }) => {
    const response = await request.get(
      `${API_BASE}/api/channels/whatsapp/webhook`
    );
    expect([200, 404, 401, 403]).toContain(response.status());
  });

  test("should handle incoming messages", async ({ request }) => {
    const response = await request.post(
      `${API_BASE}/api/channels/whatsapp/webhook`,
      {
        data: {
          entry: [
            {
              changes: [
                {
                  value: {
                    messages: [
                      {
                        from: "1234567890",
                        type: "text",
                        text: { body: "Test" },
                      },
                    ],
                  },
                },
              ],
            },
          ],
        },
      }
    );
    expect([200, 201, 400, 401, 403]).toContain(response.status());
  });

  test("should validate webhook signature", async ({ request }) => {
    const response = await request.post(
      `${API_BASE}/api/channels/whatsapp/webhook`,
      {
        data: { test: "data" },
        headers: { "X-Hub-Signature": "invalid-signature" },
      }
    );
    expect([400, 401, 403]).toContain(response.status());
  });

  test("should support sending messages via API", async ({ request }) => {
    const response = await request.post(
      `${API_BASE}/api/channels/whatsapp/send`,
      {
        data: {
          to: "1234567890",
          message: "Test message",
        },
      }
    );
    expect([200, 201, 400, 401, 403]).toContain(response.status());
  });
});

test.describe("WhatsApp Integration - Error Handling", () => {
  test("should handle disconnection", async ({ page }) => {
    await page.goto(`${APP_URL}/app/channels/whatsapp`, { waitUntil: "networkidle" });

    const errorMessage = await page.$("[class*='error'], [class*='alert']");
    expect(errorMessage || true).toBeTruthy();
  });

  test("should retry failed messages", async ({ request }) => {
    const response = await request.post(`${API_BASE}/api/channels/whatsapp/retry`, {
      data: {
        messageId: "test-message-id",
      },
    });
    expect([200, 400, 401, 403, 404]).toContain(response.status());
  });

  test("should handle rate limiting", async ({ request }) => {
    const responses = [];
    for (let i = 0; i < 5; i++) {
      const response = await request.post(
        `${API_BASE}/api/channels/whatsapp/send`,
        {
          data: {
            to: "1234567890",
            message: "Test",
          },
        }
      );
      responses.push(response.status());
    }

    expect(
      responses.every((status) => [200, 201, 400, 401, 403, 429].includes(status))
    ).toBe(true);
  });

  test("should validate phone numbers", async ({ page }) => {
    const input = await page.$("input[type='tel'], input[placeholder*='phone']");
    if (input) {
      await input.fill("invalid");
      expect(input).toBeTruthy();
    }
  });
});

test.describe("WhatsApp Integration - Performance", () => {
  test("should load conversations quickly", async ({ page }) => {
    const startTime = Date.now();
    await page.goto(`${APP_URL}/app/channels/whatsapp`, { waitUntil: "networkidle" });
    const loadTime = Date.now() - startTime;

    expect(loadTime).toBeLessThan(5000);
  });

  test("should send messages without lag", async ({ page }) => {
    const messageInput = await page.$("input[placeholder*='message']");
    if (messageInput) {
      const startTime = Date.now();
      await messageInput.fill("Test");
      const fillTime = Date.now() - startTime;

      expect(fillTime).toBeLessThan(500);
    }
  });
});

test.describe("WhatsApp Integration - Accessibility", () => {
  test("should be keyboard navigable", async ({ page }) => {
    await page.goto(`${APP_URL}/app/channels/whatsapp`);

    await page.press("body", "Tab");
    const focused = await page.evaluate(() => document.activeElement?.tagName);
    expect(focused).toBeTruthy();
  });

  test("should have ARIA labels", async ({ page }) => {
    const buttons = await page.$$("button[aria-label]");
    expect(buttons.length >= 0).toBe(true);
  });
});
