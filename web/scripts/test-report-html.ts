// HTML renderer for generate-test-report.ts.

import type { ServiceReport } from "./test-report-data";

export const generateHTML = (reports: ServiceReport[]): string => {
  const timestamp = new Date().toLocaleString();
  const totalTests = reports.reduce((sum, r) => sum + r.totalTests, 0);
  const totalPassed = reports.reduce((sum, r) => sum + r.passedTests, 0);
  const totalFailed = reports.reduce((sum, r) => sum + r.failedTests, 0);
  const overallPassRate = totalTests > 0 ? Math.round((totalPassed / totalTests) * 100) : 0;

  let html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>VegaDūta E2E Test Report</title>
  <style>
    * {
      margin: 0;
      padding: 0;
      box-sizing: border-box;
    }

    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
      background: linear-gradient(135deg, #0f1419 0%, #1a1f2e 100%);
      color: #e4e4e7;
      line-height: 1.6;
      padding: 20px;
    }

    .container {
      max-width: 1400px;
      margin: 0 auto;
    }

    header {
      background: rgba(255, 255, 255, 0.05);
      backdrop-filter: blur(10px);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 12px;
      padding: 40px;
      margin-bottom: 30px;
      text-align: center;
    }

    h1 {
      font-size: 2.5em;
      margin-bottom: 10px;
      background: linear-gradient(135deg, #3fe6cf, #8a7bff);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      background-clip: text;
    }

    .subtitle {
      color: #a1a1a6;
      font-size: 1.1em;
      margin-bottom: 30px;
    }

    .stats-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 20px;
      margin-top: 30px;
    }

    .stat-card {
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 8px;
      padding: 20px;
      text-align: center;
    }

    .stat-value {
      font-size: 2.5em;
      font-weight: bold;
      margin: 10px 0;
    }

    .stat-label {
      color: #a1a1a6;
      font-size: 0.9em;
    }

    .passed { color: #45dda0; }
    .failed { color: #ff6b6b; }
    .pending { color: #ffb24d; }

    .service-section {
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 12px;
      padding: 30px;
      margin-bottom: 30px;
      overflow: hidden;
    }

    .service-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 20px;
      padding-bottom: 15px;
      border-bottom: 1px solid rgba(255, 255, 255, 0.1);
    }

    .service-title {
      font-size: 1.5em;
      font-weight: 600;
    }

    .service-url {
      color: #3fe6cf;
      font-size: 0.9em;
      font-family: monospace;
    }

    .service-stats {
      display: flex;
      gap: 30px;
      font-size: 0.9em;
    }

    .test-suite {
      background: rgba(0, 0, 0, 0.3);
      border-radius: 8px;
      padding: 15px;
      margin-bottom: 15px;
    }

    .suite-name {
      font-weight: 600;
      margin-bottom: 10px;
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .suite-icon {
      font-size: 1.2em;
    }

    .test-list {
      list-style: none;
      padding-left: 20px;
    }

    .test-item {
      padding: 8px 0;
      display: flex;
      align-items: center;
      gap: 10px;
      font-size: 0.9em;
    }

    .test-status {
      width: 12px;
      height: 12px;
      border-radius: 50%;
      flex-shrink: 0;
    }

    .test-status.passed {
      background: #45dda0;
    }

    .test-status.failed {
      background: #ff6b6b;
    }

    .test-status.skipped {
      background: #8a7bff;
    }

    .test-duration {
      margin-left: auto;
      color: #a1a1a6;
      font-size: 0.85em;
    }

    .progress-bar {
      width: 100%;
      height: 6px;
      background: rgba(255, 255, 255, 0.1);
      border-radius: 3px;
      overflow: hidden;
      margin-top: 10px;
    }

    .progress-fill {
      height: 100%;
      background: linear-gradient(90deg, #45dda0, #3fe6cf);
      transition: width 0.3s ease;
    }

    footer {
      text-align: center;
      color: #a1a1a6;
      font-size: 0.9em;
      margin-top: 40px;
      padding: 20px;
      border-top: 1px solid rgba(255, 255, 255, 0.1);
    }

    .timestamp {
      color: #8a7bff;
      font-family: monospace;
    }

    @media print {
      body {
        background: white;
      }

      .service-section,
      .stat-card {
        background: white;
        border: 1px solid #e4e4e7;
      }
    }

    .badge {
      display: inline-block;
      padding: 4px 12px;
      border-radius: 20px;
      font-size: 0.85em;
      font-weight: 600;
    }

    .badge.pass {
      background: rgba(69, 221, 160, 0.2);
      color: #45dda0;
    }

    .badge.fail {
      background: rgba(255, 107, 107, 0.2);
      color: #ff6b6b;
    }

    .summary-table {
      width: 100%;
      border-collapse: collapse;
      margin-top: 20px;
      font-size: 0.95em;
    }

    .summary-table th {
      background: rgba(255, 255, 255, 0.05);
      padding: 12px;
      text-align: left;
      font-weight: 600;
      border-bottom: 1px solid rgba(255, 255, 255, 0.1);
    }

    .summary-table td {
      padding: 12px;
      border-bottom: 1px solid rgba(255, 255, 255, 0.05);
    }

    .summary-table tr:hover {
      background: rgba(255, 255, 255, 0.02);
    }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <h1>🚀 VegaDūta E2E Test Report</h1>
      <p class="subtitle">Comprehensive Test Coverage Across All Services</p>

      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-label">Total Tests</div>
          <div class="stat-value">${totalTests}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Passed</div>
          <div class="stat-value passed">${totalPassed}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Failed</div>
          <div class="stat-value failed">${totalFailed}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Pass Rate</div>
          <div class="stat-value passed">${overallPassRate}%</div>
        </div>
      </div>
    </header>

    <section class="service-section" style="background: rgba(69, 221, 160, 0.05); border-color: rgba(69, 221, 160, 0.2);">
      <h2>📊 Summary by Service</h2>
      <table class="summary-table">
        <thead>
          <tr>
            <th>Service</th>
            <th>Tests</th>
            <th>Passed</th>
            <th>Failed</th>
            <th>Pass Rate</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
`;

  reports.forEach((report) => {
    const passRatePercent = Math.round((report.passedTests / report.totalTests) * 100);
    const status = report.failedTests === 0 ? "✅ Pass" : "❌ Fail";
    html += `
          <tr>
            <td><strong>${report.name}</strong></td>
            <td>${report.totalTests}</td>
            <td><span class="passed">${report.passedTests}</span></td>
            <td><span class="failed">${report.failedTests}</span></td>
            <td><span class="passed">${passRatePercent}%</span></td>
            <td><span class="badge ${report.failedTests === 0 ? "pass" : "fail"}">${status}</span></td>
          </tr>
`;
  });

  html += `
        </tbody>
      </table>
    </section>
`;

  reports.forEach((report) => {
    const passRatePercent = Math.round((report.passedTests / report.totalTests) * 100);
    html += `
    <section class="service-section">
      <div class="service-header">
        <div>
          <h2 class="service-title">${report.name}</h2>
          <p class="service-url">${report.url}</p>
        </div>
        <div class="service-stats">
          <div><span class="passed">${report.passedTests}</span> passed</div>
          <div><span class="failed">${report.failedTests}</span> failed</div>
          <div><span class="passed">${passRatePercent}%</span> pass rate</div>
        </div>
      </div>

      <div class="progress-bar">
        <div class="progress-fill" style="width: ${passRatePercent}%"></div>
      </div>
`;

    report.results.forEach((result) => {
      const suitePassRate = Math.round((result.passedCount / result.testCount) * 100);
      html += `
      <div class="test-suite">
        <div class="suite-name">
          <span class="suite-icon">${result.status === "passed" ? "✅" : "❌"}</span>
          <span>${result.name}</span>
          <span style="margin-left: auto; color: #a1a1a6; font-size: 0.85em;">${result.testCount} tests, ${suitePassRate}% pass</span>
        </div>
`;

      if (result.tests) {
        html += `<ul class="test-list">`;
        result.tests.forEach((test) => {
          const statusClass = test.status === "passed" ? "passed" : test.status === "skipped" ? "skipped" : "failed";
          html += `
          <li class="test-item">
            <div class="test-status ${statusClass}"></div>
            <span>${test.name}</span>
            <span class="test-duration">${test.duration}ms</span>
          </li>
`;
        });
        html += `</ul>`;
      }

      html += `</div>`;
    });

    html += `</section>`;
  });

  html += `
    <footer>
      <p>Generated: <span class="timestamp">${timestamp}</span></p>
      <p>VegaDūta E2E Testing Suite | All Services Tested</p>
    </footer>
  </div>
</body>
</html>
`;

  return html;
};
