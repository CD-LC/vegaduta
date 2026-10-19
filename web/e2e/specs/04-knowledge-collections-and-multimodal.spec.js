// Business case: hierarchical knowledge collections (Stage F1) and
// multi-modal ingestion (F3a-F3d). The .md path is fully self-contained (an
// in-memory sample file, no fixture asset needed) since it's the oldest,
// most load-bearing format. PDF/DOCX/image/audio/video tests use a real
// sample file from disk and skip themselves if the corresponding
// E2E_SAMPLE_*_PATH env var isn't set - see e2e/README.md for where to get
// small real sample files instead of trying to synthesize valid PDFs/DOCX/
// media inline.
const fs = require("fs");
const { test, expect, signupTenant, createConsoleUser, loginAndSetPermanentPassword, pickCustomSelect } = require("../fixtures");

function sampleFile(envVar) {
  const p = process.env[envVar];
  if (!p || !fs.existsSync(p)) return null;
  return p;
}

// Manual equivalent of the `consolePage` fixture, used only by the gated
// tests below. Call this AFTER a test.skip() check, never before - the
// whole point is to avoid signing up a tenant (and burning one of
// SignupRateLimiter's 5-per-hour attempts) for a test that's about to skip
// itself anyway. See e2e/README.md's rate-limit note. Also lands on
// /knowledge directly (both panels below now live there, not on "/").
async function setupConsoleSession(page, request) {
  const tenant = await signupTenant(request);
  const consoleUser = await createConsoleUser(request, tenant);
  await loginAndSetPermanentPassword(page, {
    username: consoleUser.username,
    temporaryPassword: consoleUser.temporaryPassword,
    newPassword: consoleUser.permanentPassword,
  });
  await page.goto("/knowledge");
}

test.describe("Knowledge collections", () => {
  test("create a nested collection and see it in the parent dropdown", async ({ consolePage: page }) => {
    await page.goto("/knowledge");
    await page.fill('section:has-text("Knowledge Collections") input[type="text"] >> nth=0', "Product Docs");
    await page.fill('section:has-text("Knowledge Collections") input[type="text"] >> nth=1', "Top-level product documentation");
    await page.click('button:has-text("Create collection")');
    await expect(page.locator(".success-box")).toContainText('"Product Docs" created');

    await page.fill('section:has-text("Knowledge Collections") input[type="text"] >> nth=0', "API Reference");
    await pickCustomSelect(page, page.getByTestId("collection-parent-select"), "Product Docs");
    await page.click('button:has-text("Create collection")');
    await expect(page.locator(".success-box")).toContainText('"API Reference" created');

    const childRow = page.locator(".list-item", { hasText: "API Reference" });
    await expect(childRow).toContainText("under Product Docs");
  });

  test("the seeded Default collection cannot be deleted", async ({ consolePage: page }) => {
    await page.goto("/knowledge");
    const defaultRow = page.locator(".list-item", { hasText: "default" });
    await expect(defaultRow.getByRole("button", { name: "Delete" })).toBeDisabled();
  });

  test("deleting a non-empty collection is rejected with a clear error", async ({ consolePage: page }) => {
    await page.goto("/knowledge");
    await page.fill('section:has-text("Knowledge Collections") input[type="text"] >> nth=0', "Has Chunks");
    await page.click('button:has-text("Create collection")');
    await expect(page.locator(".success-box")).toContainText('"Has Chunks" created');

    await pickCustomSelect(page, page.getByTestId("knowledge-upload-collection-select"), "Has Chunks");
    await page.setInputFiles('input[type="file"]', {
      name: "note.md",
      mimeType: "text/markdown",
      buffer: Buffer.from("# Note\n\nSomething worth remembering about pineapple pizza.\n"),
    });
    await page.click('button:has-text("Upload")');
    await expect(page.locator(".success-box")).toContainText("Ingested");

    const row = page.locator(".list-item", { hasText: "Has Chunks" });
    await row.getByRole("button", { name: "Delete" }).click();
    await expect(page.locator(".error-box")).toBeVisible();
  });
});

test.describe("Multi-modal ingestion", () => {
  test("@mvp-acceptance .md upload lands in the Default collection and is searchable", async ({ consolePage: page }) => {
    await page.goto("/knowledge");
    await page.setInputFiles('input[type="file"]', {
      name: "pineapple-pizza-facts.md",
      mimeType: "text/markdown",
      buffer: Buffer.from("# Pineapple Pizza\n\nHawaiian pizza was invented in Canada, not Hawaii.\n"),
    });
    await page.click('button:has-text("Upload")');
    await expect(page.locator(".success-box")).toContainText("Ingested");
    await expect(page.locator(".success-box")).toContainText("pineapple-pizza-facts.md");

    await page.fill('input[placeholder="Search the knowledge base…"]', "Hawaiian pizza invented");
    await page.click('button:has-text("Search")');
    await expect(page.locator(".list-item").filter({ hasText: "pineapple-pizza-facts.md" })).toBeVisible({
      timeout: 15_000,
    });
  });

  test("uploading an unsupported extension is rejected client-side", async ({ consolePage: page }) => {
    await page.goto("/knowledge");
    await page.setInputFiles('input[type="file"]', {
      name: "notes.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("plain text, not in the supported list"),
    });
    await page.click('button:has-text("Upload")');
    await expect(page.locator(".error-box")).toContainText("Unsupported file type");
  });

  test("PDF upload extracts text via the sidecar (F3a)", async ({ page, request }) => {
    const file = sampleFile("E2E_SAMPLE_PDF_PATH");
    test.skip(!file, "Set E2E_SAMPLE_PDF_PATH to a small real .pdf to run this test.");
    await setupConsoleSession(page, request);
    await page.setInputFiles('input[type="file"]', file);
    await page.click('button:has-text("Upload")');
    await expect(page.locator(".success-box")).toContainText("Ingested", { timeout: 30_000 });
  });

  test("DOCX upload extracts text via the sidecar (F3a)", async ({ page, request }) => {
    const file = sampleFile("E2E_SAMPLE_DOCX_PATH");
    test.skip(!file, "Set E2E_SAMPLE_DOCX_PATH to a small real .docx to run this test.");
    await setupConsoleSession(page, request);
    await page.setInputFiles('input[type="file"]', file);
    await page.click('button:has-text("Upload")');
    await expect(page.locator(".success-box")).toContainText("Ingested", { timeout: 30_000 });
  });

  test("image upload runs OCR via the sidecar (F3b)", async ({ page, request }) => {
    const file = sampleFile("E2E_SAMPLE_IMAGE_PATH");
    test.skip(!file, "Set E2E_SAMPLE_IMAGE_PATH to a small real image with legible text to run this test.");
    await setupConsoleSession(page, request);
    await page.setInputFiles('input[type="file"]', file);
    await page.click('button:has-text("Upload")');
    await expect(page.locator(".success-box")).toContainText("Ingested", { timeout: 30_000 });
  });

  test("audio upload is async and pollable to completion (F3c)", async ({ page, request }) => {
    const file = sampleFile("E2E_SAMPLE_AUDIO_PATH");
    test.skip(!file, "Set E2E_SAMPLE_AUDIO_PATH to a short real audio clip with speech to run this test.");
    await setupConsoleSession(page, request);
    await page.setInputFiles('input[type="file"]', file);
    await page.click('button:has-text("Upload")');
    await expect(page.getByText(/job queued|job processing/i)).toBeVisible({ timeout: 15_000 });
    // Transcription on a CPU-only sidecar can genuinely take minutes -
    // matches STAGE-F3C-VERIFICATION.md's own generous polling window.
    await expect(page.getByText(/job complete/i)).toBeVisible({ timeout: 300_000 });
  });

  test("video upload reuses the same transcription job path (F3d)", async ({ page, request }) => {
    const file = sampleFile("E2E_SAMPLE_VIDEO_PATH");
    test.skip(!file, "Set E2E_SAMPLE_VIDEO_PATH to a short real video clip with speech to run this test.");
    await setupConsoleSession(page, request);
    await page.setInputFiles('input[type="file"]', file);
    await page.click('button:has-text("Upload")');
    await expect(page.getByText(/job queued|job processing/i)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/job complete/i)).toBeVisible({ timeout: 300_000 });
  });
});
