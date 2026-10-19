// Report types and the demonstration results used by generate-test-report.ts.

interface TestResult {
  name: string;
  url: string;
  status: "passed" | "failed" | "pending";
  duration: number;
  testCount: number;
  passedCount: number;
  failedCount: number;
  tests?: TestCase[];
}

interface TestCase {
  name: string;
  status: "passed" | "failed" | "skipped";
  duration: number;
  error?: string;
}

export interface ServiceReport {
  name: string;
  url: string;
  results: TestResult[];
  totalTests: number;
  passedTests: number;
  failedTests: number;
  passRate: number;
}

// Mock test results for demonstration
export const generateMockResults = (): ServiceReport[] => {
  return [
    {
      name: "Landing Page (AXON Redesign)",
      url: "http://localhost:3000",
      results: [
        {
          name: "Header & Navigation",
          url: "Landing Page",
          status: "passed",
          duration: 2340,
          testCount: 7,
          passedCount: 7,
          failedCount: 0,
          tests: [
            { name: "should load landing page successfully", status: "passed", duration: 180 },
            { name: "should display header with logo and navigation links", status: "passed", duration: 250 },
            { name: "should display Academy link", status: "passed", duration: 120 },
            { name: "should display theme customizer button", status: "passed", duration: 150 },
            { name: "should display language switcher", status: "passed", duration: 140 },
            { name: "should display Sign in button", status: "passed", duration: 130 },
            { name: "should display Get started CTA button", status: "passed", duration: 160 },
          ],
        },
        {
          name: "Hero Section",
          url: "Landing Page",
          status: "passed",
          duration: 1890,
          testCount: 5,
          passedCount: 5,
          failedCount: 0,
          tests: [
            { name: "should display hero headline", status: "passed", duration: 200 },
            { name: "should display hero subtitle", status: "passed", duration: 180 },
            { name: "should display hero CTA buttons", status: "passed", duration: 210 },
            { name: "should display country/language badge", status: "passed", duration: 150 },
            { name: "should display product preview card", status: "passed", duration: 240 },
          ],
        },
        {
          name: "Canvas Animation",
          url: "Landing Page",
          status: "passed",
          duration: 1240,
          testCount: 4,
          passedCount: 4,
          failedCount: 0,
          tests: [
            { name: "should render canvas element on desktop", status: "passed", duration: 180 },
            { name: "canvas should have proper dimensions", status: "passed", duration: 200 },
            { name: "canvas animation should be animating", status: "passed", duration: 220 },
            { name: "canvas should maintain aspect ratio on resize", status: "passed", duration: 280 },
          ],
        },
        {
          name: "Theme Switching",
          url: "Landing Page",
          status: "passed",
          duration: 2100,
          testCount: 4,
          passedCount: 4,
          failedCount: 0,
          tests: [
            { name: "should start in light theme by default", status: "passed", duration: 250 },
            { name: "should switch to dark theme", status: "passed", duration: 580 },
            { name: "should switch to light theme", status: "passed", duration: 550 },
            { name: "theme should persist after page reload", status: "passed", duration: 720 },
          ],
        },
      ],
      totalTests: 20,
      passedTests: 20,
      failedTests: 0,
      passRate: 100,
    },
    {
      name: "Admin Panel",
      url: "http://localhost:3001",
      results: [
        {
          name: "Authentication",
          url: "Admin Panel",
          status: "passed",
          duration: 1850,
          testCount: 4,
          passedCount: 4,
          failedCount: 0,
          tests: [
            { name: "should redirect to login if not authenticated", status: "passed", duration: 280 },
            { name: "should have admin login form", status: "passed", duration: 240 },
            { name: "should require valid credentials", status: "passed", duration: 450 },
            { name: "should display dashboard after login", status: "passed", duration: 880 },
          ],
        },
        {
          name: "Tenant Management",
          url: "Admin Panel",
          status: "passed",
          duration: 2340,
          testCount: 4,
          passedCount: 4,
          failedCount: 0,
          tests: [
            { name: "should display tenants list", status: "passed", duration: 320 },
            { name: "should have search/filter for tenants", status: "passed", duration: 410 },
            { name: "should allow creating new tenant", status: "passed", duration: 550 },
            { name: "should display tenant details", status: "passed", duration: 460 },
          ],
        },
        {
          name: "Plan Limits Editor",
          url: "Admin Panel",
          status: "passed",
          duration: 1680,
          testCount: 3,
          passedCount: 3,
          failedCount: 0,
          tests: [
            { name: "should have plan limits configuration", status: "passed", duration: 380 },
            { name: "should allow editing feature limits", status: "passed", duration: 520 },
            { name: "should save configuration successfully", status: "passed", duration: 780 },
          ],
        },
      ],
      totalTests: 11,
      passedTests: 11,
      failedTests: 0,
      passRate: 100,
    },
    {
      name: "Core API",
      url: "http://localhost:8080",
      results: [
        {
          name: "Health & Status",
          url: "Core API",
          status: "passed",
          duration: 890,
          testCount: 3,
          passedCount: 3,
          failedCount: 0,
          tests: [
            { name: "should have health endpoint", status: "passed", duration: 120 },
            { name: "should have metrics endpoint", status: "passed", duration: 150 },
            { name: "should have info endpoint", status: "passed", duration: 130 },
          ],
        },
        {
          name: "Authentication",
          url: "Core API",
          status: "passed",
          duration: 1240,
          testCount: 3,
          passedCount: 3,
          failedCount: 0,
          tests: [
            { name: "should require auth token", status: "passed", duration: 180 },
            { name: "should validate token format", status: "passed", duration: 210 },
            { name: "should accept valid token", status: "passed", duration: 230 },
          ],
        },
        {
          name: "Tenant Endpoints",
          url: "Core API",
          status: "passed",
          duration: 1680,
          testCount: 4,
          passedCount: 4,
          failedCount: 0,
          tests: [
            { name: "GET /api/tenants should return list", status: "passed", duration: 220 },
            { name: "GET /api/tenants/:id should return tenant", status: "passed", duration: 240 },
            { name: "POST /api/tenants should create tenant", status: "passed", duration: 350 },
            { name: "PUT /api/tenants/:id should update tenant", status: "passed", duration: 380 },
          ],
        },
        {
          name: "Plan Limits API",
          url: "Core API",
          status: "passed",
          duration: 1340,
          testCount: 3,
          passedCount: 3,
          failedCount: 0,
          tests: [
            { name: "GET /api/platform/plan-limits", status: "passed", duration: 210 },
            { name: "PUT /api/platform/plan-limits", status: "passed", duration: 290 },
            { name: "should validate plan limit constraints", status: "passed", duration: 280 },
          ],
        },
      ],
      totalTests: 13,
      passedTests: 13,
      failedTests: 0,
      passRate: 100,
    },
    {
      name: "Academy Platform",
      url: "http://localhost:4100",
      results: [
        {
          name: "Access & Navigation",
          url: "Academy",
          status: "passed",
          duration: 1680,
          testCount: 4,
          passedCount: 4,
          failedCount: 0,
          tests: [
            { name: "should load academy homepage", status: "passed", duration: 320 },
            { name: "should have public access", status: "passed", duration: 280 },
            { name: "should display hero section", status: "passed", duration: 240 },
            { name: "should have course listing", status: "passed", duration: 350 },
          ],
        },
        {
          name: "Courses & Lessons",
          url: "Academy",
          status: "passed",
          duration: 2240,
          testCount: 5,
          passedCount: 5,
          failedCount: 0,
          tests: [
            { name: "should list all courses", status: "passed", duration: 280 },
            { name: "should display course metadata", status: "passed", duration: 240 },
            { name: "should allow filtering by level", status: "passed", duration: 450 },
            { name: "should display lesson list", status: "passed", duration: 380 },
            { name: "should show lesson progress", status: "passed", duration: 320 },
          ],
        },
        {
          name: "Quizzes & Labs",
          url: "Academy",
          status: "passed",
          duration: 1890,
          testCount: 4,
          passedCount: 4,
          failedCount: 0,
          tests: [
            { name: "should display quiz questions", status: "passed", duration: 280 },
            { name: "should allow answering questions", status: "passed", duration: 310 },
            { name: "should show quiz score", status: "passed", duration: 350 },
            { name: "should display available labs", status: "passed", duration: 370 },
          ],
        },
      ],
      totalTests: 13,
      passedTests: 13,
      failedTests: 0,
      passRate: 100,
    },
    {
      name: "WhatsApp Integration",
      url: "http://localhost:3000",
      results: [
        {
          name: "Setup & Configuration",
          url: "WhatsApp",
          status: "passed",
          duration: 1560,
          testCount: 4,
          passedCount: 4,
          failedCount: 0,
          tests: [
            { name: "should display WhatsApp channel option", status: "passed", duration: 220 },
            { name: "should have WhatsApp settings in admin", status: "passed", duration: 240 },
            { name: "should display QR code for pairing", status: "passed", duration: 380 },
            { name: "should show pairing instructions", status: "passed", duration: 320 },
          ],
        },
        {
          name: "Messaging",
          url: "WhatsApp",
          status: "passed",
          duration: 2120,
          testCount: 5,
          passedCount: 5,
          failedCount: 0,
          tests: [
            { name: "should display conversation list", status: "passed", duration: 280 },
            { name: "should display messages in chat", status: "passed", duration: 310 },
            { name: "should allow sending messages", status: "passed", duration: 420 },
            { name: "should display message timestamps", status: "passed", duration: 290 },
            { name: "should show delivery status", status: "passed", duration: 320 },
          ],
        },
        {
          name: "API Integration",
          url: "WhatsApp",
          status: "passed",
          duration: 1340,
          testCount: 4,
          passedCount: 4,
          failedCount: 0,
          tests: [
            { name: "should have WhatsApp webhook endpoint", status: "passed", duration: 210 },
            { name: "should handle incoming messages", status: "passed", duration: 280 },
            { name: "should validate webhook signature", status: "passed", duration: 250 },
            { name: "should support sending messages via API", status: "passed", duration: 320 },
          ],
        },
      ],
      totalTests: 13,
      passedTests: 13,
      failedTests: 0,
      passRate: 100,
    },
  ];
};
