# Build Page E2E Test Execution Guide

## Quick Start

### 1. Install Dependencies
```bash
cd e2e
npm install
npx playwright install --with-deps chromium
```

### 2. Run All Tests
```bash
npm test
```

### 3. View Results
```bash
npm run report
```

## Detailed Test Execution

### Run Specific Test Suites

#### Core Layout & Navigation Tests
```bash
npx playwright test tests/build-page.spec.ts -g "Core Layout"
```

**What it tests:**
- Three main surface tabs render correctly
- Tab navigation works smoothly
- AppShell wrapper is properly rendered
- Default tab (Agent builder) is active

**Expected outcome:** All 5 tests pass ✅

---

#### Agent Builder Surface Tests
```bash
npx playwright test tests/build-page.spec.ts -g "Agent Builder"
```

**What it tests:**
- Project creation interface displays correctly
- Service unavailability is handled gracefully

**Expected outcome:** All 2 tests pass ✅

---

#### App Projects Surface Tests
```bash
npx playwright test tests/build-page.spec.ts -g "App Projects"
```

**What it tests:**
- Switching to App projects tab succeeds
- Empty projects list shows gracefully

**Expected outcome:** All 2 tests pass ✅

---

#### GitHub Integration Tests
```bash
npx playwright test tests/build-page.spec.ts -g "GitHub Integration"
```

**What it tests:**
- `/build/github` route is accessible
- GitHub UI elements are present and descriptive
- Service errors are handled without crashes

**Expected outcome:** All 3 tests pass ✅

---

#### Error Handling Tests
```bash
npx playwright test tests/build-page.spec.ts -g "Error Handling"
```

**What it tests:**
- No console errors on page load
- Back navigation works
- Rapid tab switching is handled
- Tab state persists across renders

**Expected outcome:** All 4 tests pass ✅

---

#### Accessibility Tests
```bash
npx playwright test tests/build-page.spec.ts -g "Accessibility"
```

**What it tests:**
- Tab controls are keyboard accessible
- Buttons have accessible labels
- Heading hierarchy is proper

**Expected outcome:** All 3 tests pass ✅

---

#### Route Protection Tests
```bash
npx playwright test tests/build-page.spec.ts -g "Route Protection"
```

**What it tests:**
- `/build` route is accessible or properly redirects
- `/build/github` route is accessible or properly redirects

**Expected outcome:** All 2 tests pass ✅

---

## Test Execution Modes

### 1. Headed Mode (Watch It Run)
```bash
npx playwright test tests/build-page.spec.ts --headed
```
- Opens browser window
- Shows test execution in real-time
- Useful for debugging
- Press Ctrl+C to stop

### 2. Debug Mode
```bash
npx playwright test tests/build-page.spec.ts --debug
```
- Opens Playwright Inspector
- Step through actions
- Inspect DOM and network
- Evaluate JavaScript

### 3. Single Test
```bash
npx playwright test tests/build-page.spec.ts -g "should render the build page"
```
- Runs only matching test(s)
- Useful for quick verification
- Faster feedback loop

### 4. Single Project (Browser)
```bash
npx playwright test tests/build-page.spec.ts --project=chromium
```
- Runs only on desktop browser
- Faster than running all projects
- Options: chromium, mobile-chrome

## Environment Variables

### Control Test Behavior
```bash
# Set base URL for tests
E2E_BASE_URL=https://staging.example.com npm test

# Use different domain
E2E_BASE_URL=https://app.example.com npm test

# Run on production
E2E_BASE_URL=https://app.staging.example.com npm test
```

### CI/CD Integration
```bash
# Disable headed mode in CI
CI=true npm test

# Generate reports
npm test -- --reporter=html

# Exit with error on failure
npm test -- --forbid-only
```

## Test Scenarios & Expected Outcomes

### Scenario 1: Fresh Landing on Build Page

**Steps:**
1. Navigate to `/build` (no authentication)
2. Wait for page to fully load
3. Observe rendered elements

**Expected Results:**
- Page loads without error (200 or redirect)
- Three tab buttons are visible
- "Agent builder" tab is active by default
- Main content area is rendered

**Verifying:**
```bash
npx playwright test tests/build-page.spec.ts -g "should render the build page"
```

---

### Scenario 2: Tab Navigation

**Steps:**
1. Start on `/build`
2. Click "AI app studio" tab
3. Verify content changes
4. Click "App projects" tab
5. Verify content changes
6. Return to "Agent builder" tab

**Expected Results:**
- Tabs switch without errors
- Content updates for each tab
- No console errors
- Navigation is smooth and responsive

**Verifying:**
```bash
npx playwright test tests/build-page.spec.ts -g "should navigate between"
```

---

### Scenario 3: GitHub Integration Access

**Steps:**
1. Navigate to `/build/github`
2. Wait for page load
3. Look for GitHub-related UI

**Expected Results:**
- Page loads (either in build page or redirected)
- GitHub-related text/elements are present
- No hard crashes or errors

**Verifying:**
```bash
npx playwright test tests/build-page.spec.ts -g "GitHub Integration"
```

---

### Scenario 4: Error Handling

**Steps:**
1. Navigate to `/build`
2. Monitor browser console
3. Perform rapid tab switching
4. Navigate back in browser history
5. Check for errors

**Expected Results:**
- No JavaScript errors logged
- Back navigation works smoothly
- Rapid interactions don't cause crashes
- Error messages are user-friendly

**Verifying:**
```bash
npx playwright test tests/build-page.spec.ts -g "Error Handling"
```

---

### Scenario 5: Accessibility Compliance

**Steps:**
1. Navigate to `/build`
2. Tab through interactive elements
3. Check button labels
4. Verify heading structure

**Expected Results:**
- All buttons have accessible names
- Tab controls are keyboard-navigable
- Heading hierarchy is proper (no skipped levels)
- Screen reader announcements make sense

**Verifying:**
```bash
npx playwright test tests/build-page.spec.ts -g "Accessibility"
```

---

## Reading Test Results

### HTML Report
```bash
npm run report
```
Opens interactive HTML report showing:
- Test names and durations
- Pass/fail status
- Screenshots of failures
- Videos of failed tests
- Error details and stack traces

### Console Output
```
✅ PASSED: should render the build page
❌ FAILED: should handle service unavailability
⏭️  SKIPPED: should require authentication (if auth not available)
```

### Test Statistics
```
40 passed (2m 15s)
0 failed
0 skipped

Pass rate: 100%
Avg duration: 3.4 seconds per test
Total run time: 2m 15s
```

## Troubleshooting

### Issue: Tests Timeout

**Symptom:**
```
Timeout: page.waitForLoadState('networkidle')
```

**Diagnosis:**
- Network is slow
- Service is slow to respond
- JavaScript is continuously making requests

**Solution:**
1. Increase timeout in `playwright.config.ts`:
   ```typescript
   navigationTimeout: 60_000,  // 60 seconds
   ```

2. Check app status:
   ```bash
   curl https://app.staging.example.com/api/health
   ```

3. Run on faster network

### Issue: Tab Selectors Not Found

**Symptom:**
```
Locator.click: Target closed
or
Locator did not resolve to any element
```

**Diagnosis:**
- Tab text changed
- Tab component refactored
- Page didn't fully load

**Solution:**
1. Update selector in test:
   ```typescript
   // Old
   page.getByRole("tab", { name: /Agent builder/i })
   
   // New (if text changed)
   page.getByRole("tab", { name: /AI Agent/i })
   ```

2. Increase wait time:
   ```typescript
   await page.getByRole("tab").click({ timeout: 10000 });
   ```

### Issue: Console Errors Detected

**Symptom:**
```
TypeError: Cannot read properties of undefined
```

**Diagnosis:**
- Genuine application error
- Third-party script error (known noise)
- Auth/session issue

**Solution:**
1. Check if error is expected:
   - Cloudflare RUM beacon errors (known)
   - Browser extension errors (known)
   - Application errors (fix required)

2. Update console error filter in `helpers/console-errors.ts`

3. Check auth state:
   ```bash
   ls -la e2e/.e2e-prod-auth-state.worker-*.json
   ```

### Issue: Mobile Tests Fail, Desktop Passes

**Symptom:**
```
[mobile-chrome] FAILED: should render the build page
[chromium] PASSED: should render the build page
```

**Diagnosis:**
- Responsive design issue
- Touch vs mouse interaction difference
- Mobile-specific CSS not applied

**Solution:**
1. Debug in mobile viewport:
   ```bash
   npx playwright test tests/build-page.spec.ts --headed --project=mobile-chrome
   ```

2. Check CSS media queries
3. Verify touch event handling

### Issue: Auth State Stale

**Symptom:**
```
Login failed: undefined
or
Cannot access console
```

**Diagnosis:**
- Auth token expired (> 12 hours old)
- Session was revoked
- New test environment

**Solution:**
1. Clear auth cache:
   ```bash
   rm e2e/.e2e-prod-auth-state.worker-*.json
   ```

2. Run tests again (will trigger fresh login)

3. Check if credentials are valid

## Performance Tips

### Faster Test Runs
```bash
# Run only on desktop (skip mobile)
npx playwright test tests/build-page.spec.ts --project=chromium

# Run without retries
npx playwright test tests/build-page.spec.ts --retries=0

# Run single test
npx playwright test tests/build-page.spec.ts -g "should render"
```

### Parallel Execution
Tests run in parallel by default (8 workers). Adjust:
```bash
# Single worker (slower, useful for debugging)
npx playwright test --workers=1

# More workers (faster, but more resource usage)
npx playwright test --workers=16
```

### Verbose Output
```bash
# Show all steps
npx playwright test --reporter=verbose

# Show trace files
npx playwright test --trace=on
```

## Continuous Integration

### GitHub Actions Example
```yaml
name: E2E Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - uses: actions/setup-node@v3
        with:
          node-version: 18
      
      - name: Install dependencies
        run: cd e2e && npm install
      
      - name: Install browsers
        run: cd e2e && npx playwright install --with-deps
      
      - name: Run tests
        run: cd e2e && npm test
      
      - name: Upload report
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: playwright-report
          path: e2e/playwright-report/
          retention-days: 30
```

## Test Maintenance Checklist

### After UI Changes
- [ ] Update selectors if elements changed
- [ ] Add tests for new features
- [ ] Update expected text in assertions
- [ ] Verify mobile responsiveness
- [ ] Check accessibility impact

### After Service Changes
- [ ] Update error handling expectations
- [ ] Verify timeout settings
- [ ] Check response structure
- [ ] Test retry behavior

### Before Release
- [ ] Run full test suite
- [ ] Check for flaky tests
- [ ] Review error messages
- [ ] Update documentation
- [ ] Run on staging environment

### Monthly Maintenance
- [ ] Review test coverage gaps
- [ ] Update dependencies
- [ ] Clean up test artifacts
- [ ] Review CI/CD logs
- [ ] Update baseline screenshots

## Additional Resources

- **Playwright Docs:** https://playwright.dev
- **Best Practices:** https://playwright.dev/docs/best-practices
- **Debugging:** https://playwright.dev/docs/debug
- **CI/CD Integration:** https://playwright.dev/docs/ci
- **Test Reports:** https://playwright.dev/docs/test-reports

## Support & Questions

For issues with tests:
1. Check this guide for common issues
2. Review test file comments
3. Check Playwright documentation
4. Review CI/CD logs for context
5. Run in debug mode for more details

---

**Last Updated:** 2026-09-28  
**Test Suite Version:** 1.0  
**Playwright Version:** 1.48.0+
