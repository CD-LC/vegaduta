import type { Page } from "@playwright/test";

// Shared helpers for the AXON redesign E2E suite (axon-redesign*.spec.ts).

export const BASE_URL = "http://localhost:3000";

// Helper function to wait for animations
export async function waitForAnimations(page: Page) {
  await page.waitForTimeout(500);
}

// Helper to check if element is in viewport
export async function isInViewport(page: Page, selector: string) {
  const element = await page.$(selector);
  if (!element) return false;
  const boundingBox = await element.boundingBox();
  if (!boundingBox) return false;
  const viewport = page.viewportSize();
  if (!viewport) return false;
  return (
    boundingBox.y < viewport.height &&
    boundingBox.y + boundingBox.height > 0
  );
}

