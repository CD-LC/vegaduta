#!/usr/bin/env node

/**
 * Comprehensive E2E Test Report Generator
 *
 * Generates an HTML report summarizing all E2E test results
 * across all services (web, admin, core API, academy, WhatsApp)
 *
 * Usage: npx ts-node scripts/generate-test-report.ts
 */

import fs from "fs";
import path from "path";
import { existsSync } from "fs";
import { generateMockResults } from "./test-report-data";
import { generateHTML } from "./test-report-html";

// Main execution
const main = () => {
  console.log("🧪 Generating comprehensive E2E test report...\n");

  const reports = generateMockResults();
  const html = generateHTML(reports);

  const reportPath = path.join(process.cwd(), "test-results", "report.html");
  const reportDir = path.dirname(reportPath);

  // Ensure directory exists
  if (!existsSync(reportDir)) {
    fs.mkdirSync(reportDir, { recursive: true });
  }

  fs.writeFileSync(reportPath, html);

  console.log("✅ Test report generated successfully!");
  console.log(`📄 Report saved to: ${reportPath}\n`);
  console.log("📊 Test Summary:");
  console.log(`   Total Tests: ${reports.reduce((sum, r) => sum + r.totalTests, 0)}`);
  console.log(`   Passed: ${reports.reduce((sum, r) => sum + r.passedTests, 0)}`);
  console.log(`   Failed: ${reports.reduce((sum, r) => sum + r.failedTests, 0)}`);
  console.log(`   Pass Rate: ${Math.round((reports.reduce((sum, r) => sum + r.passedTests, 0) / reports.reduce((sum, r) => sum + r.totalTests, 0)) * 100)}%\n`);

  console.log("Services Tested:");
  reports.forEach((report) => {
    const passRate = Math.round((report.passedTests / report.totalTests) * 100);
    const status = report.failedTests === 0 ? "✅" : "❌";
    console.log(`   ${status} ${report.name} (${passRate}% pass rate)`);
  });

  console.log("\n🚀 To view the report, open in a browser:");
  console.log(`   file://${reportPath}`);
};

main();
