# VegaDūta Comprehensive E2E Test Guide

Complete end-to-end testing suite covering all services and functionality across the VegaDūta platform.

## 📋 Test Coverage

### Services Tested
- ✅ **Landing Page** (AXON Redesign) - http://localhost:3000
- ✅ **Admin Panel** - http://localhost:3001
- ✅ **Core API** - http://localhost:8080
- ✅ **Academy Platform** - http://localhost:4100
- ✅ **WhatsApp Integration** - Multiple endpoints
- ✅ **Dark/Light Theme Switching**
- ✅ **Responsive Design** (mobile, tablet, desktop)
- ✅ **Accessibility** (WCAG AA compliance)
- ✅ **Performance Metrics**

### Test Suites

#### 1. **axon-redesign.spec.ts** (Landing Page)
Tests the AXON design system implementation on the landing page.

**Coverage:**
- Navigation and header
- Hero section with canvas animation
- Theme switching (dark/light)
- Reveal animations on scroll
- Card styling and glossy effects
- Button interactions
- Accessibility features
- Performance metrics
- Visual regression

**Tests:** 70+ test cases

#### 2. **admin-panel.spec.ts** (Admin UI)
Tests the admin dashboard and management interfaces.

**Coverage:**
- Authentication and login
- Dashboard overview
- Tenant management (CRUD)
- User management
- Billing configuration
- Plan limits editor
- Channel management
- Settings and configuration
- Navigation and theme support

**Tests:** 45+ test cases

#### 3. **core-api.spec.ts** (Backend API)
Tests all backend API endpoints.

**Coverage:**
- Health and status endpoints
- Authentication and token validation
- Tenant API endpoints
- Plan limits API
- Billing API
- Channel API
- Agent/Workflow API
- Error handling
- Rate limiting
- Data validation
- CORS handling

**Tests:** 60+ test cases

#### 4. **academy-platform.spec.ts** (Learning Platform)
Tests the Academy learning platform.

**Coverage:**
- Homepage and hero section
- Course listing and filtering
- Lessons and content
- Progress tracking
- Quiz functionality
- Labs and exercises
- Learner achievements/certificates
- Responsive design
- Accessibility
- Performance

**Tests:** 45+ test cases

#### 5. **whatsapp-integration.spec.ts** (WhatsApp Integration)
Tests WhatsApp channel integration.

**Coverage:**
- Channel setup and configuration
- QR code pairing
- Message sending/receiving
- Contact management
- Message history and search
- Group support
- Webhook API integration
- Error handling and retry
- Rate limiting
- Performance

**Tests:** 50+ test cases

## 🚀 Getting Started

### Prerequisites
```bash
# Install dependencies
npm install

# Install Playwright browsers
npx playwright install
```

### Environment Setup
Ensure all services are running:

```bash
# Start Docker Compose stack
docker-compose -f deploy/docker-compose.yml up -d

# Verify services are running
docker-compose ps
```

Services should be available at:
- 🌐 Web: http://localhost:3000
- 🛠️ Admin: http://localhost:3001
- 🔌 API: http://localhost:8080
- 📚 Academy: http://localhost:4100

## 📊 Running Tests

### Run All Tests
```bash
# Execute all E2E tests
npm run e2e

# Run with UI (interactive)
npm run e2e:ui

# Run with debug mode
npx playwright test --debug
```

### Run Specific Test Suite
```bash
# Landing page tests only
npx playwright test e2e/axon-redesign.spec.ts

# Admin panel tests
npx playwright test e2e/admin-panel.spec.ts

# API tests
npx playwright test e2e/core-api.spec.ts

# Academy tests
npx playwright test e2e/academy-platform.spec.ts

# WhatsApp tests
npx playwright test e2e/whatsapp-integration.spec.ts
```

### Run Specific Test Category
```bash
# Run only header tests
npx playwright test axon-redesign.spec.ts -g "Header"

# Run theme switching tests
npx playwright test axon-redesign.spec.ts -g "Theme"

# Run API authentication tests
npx playwright test core-api.spec.ts -g "Authentication"
```

### Run in Different Browsers
```bash
# Chromium only
npx playwright test --project=chromium

# Firefox only
npx playwright test --project=firefox

# Safari only
npx playwright test --project=webkit

# All browsers (default)
npx playwright test
```

### Mobile Testing
```bash
# Run on mobile Chrome
npx playwright test --project=mobile-chrome

# Run on mobile Safari
npx playwright test --project=mobile-safari

# All mobile devices
npx playwright test --project=mobile-chrome --project=mobile-safari
```

## 📈 Viewing Results

### HTML Report
```bash
# Generate HTML report
npm run test:report

# Open report in browser
open test-results/report.html
```

### Console Output
Tests display results in the terminal with:
- ✅ Passing tests
- ❌ Failing tests
- ⏭️ Skipped tests
- ⏱️ Timing information

### Trace & Video
- **Videos:** Saved in `test-results/` for failed tests
- **Traces:** Capture full debugging information
- **Screenshots:** Taken on failure

## 🔧 Configuration

### playwright.config.ts
```typescript
// Key settings:
- baseURL: http://localhost:3000
- timeout: 10000ms per action
- retries: 2 (CI) / 0 (local)
- workers: Parallel execution
- reporters: HTML + JSON + JUnit
```

### Custom Configuration
```bash
# Run with custom base URL
BASE_URL=http://localhost:3000 npx playwright test

# Run with custom timeout
npx playwright test --timeout=20000

# Run serially (no parallelization)
npx playwright test --workers=1
```

## 📝 Test Structure

Each test file follows this pattern:

```typescript
test.describe("Feature Category", () => {
  test.beforeEach(async ({ page }) => {
    // Setup (navigate to page, login, etc.)
  });

  test("should do something", async ({ page }) => {
    // Arrange
    const element = await page.$("selector");
    
    // Act
    await element.click();
    await page.waitForTimeout(300);
    
    // Assert
    await expect(element).toHaveProperty("attribute");
  });
});
```

## 🐛 Debugging

### Debug Mode
```bash
# Step through tests with inspector
npx playwright test --debug

# Pause on failure
npx playwright test --headed
```

### Inspect DOM
```typescript
// Within a test
const element = await page.$("selector");
await element.screenshot();  // Take screenshot
console.log(await element.textContent());  // Log text
```

### Check Network Requests
```typescript
// Monitor API calls
page.on("response", (response) => {
  console.log(`${response.url()} - ${response.status()}`);
});
```

## ✅ Test Checklist

Before running full test suite:

- [ ] All services running (`docker-compose ps`)
- [ ] Docker containers healthy
- [ ] Network connectivity OK
- [ ] Base URLs configured correctly
- [ ] Test data seeded (if needed)
- [ ] No stale browser instances

## 📊 Expected Results

### Landing Page
- **Canvas Animation:** 60fps performance
- **Theme Switching:** <100ms toggle
- **Scroll Animations:** Smooth reveal effects
- **Load Time:** <3s initial load
- **Accessibility:** WCAG AA pass

### Admin Panel
- **Dashboard Load:** <2s
- **Tenant CRUD:** <1s each operation
- **Plan Limits Edit:** <500ms save
- **Data Grid:** <3s for 100+ rows

### Core API
- **Health Check:** <100ms response
- **Tenant Endpoints:** <200ms response
- **Auth Validation:** <50ms
- **Error Handling:** Proper HTTP status codes

### Academy
- **Course Load:** <2s
- **Quiz Submit:** <500ms
- **Progress Update:** Real-time
- **Mobile:** Fully responsive

### WhatsApp
- **Message Send:** <1s delivery
- **Webhook Process:** <100ms
- **Contact Sync:** Background job
- **QR Pairing:** <30s timeout

## 🚨 Troubleshooting

### Tests Fail on Startup
```bash
# Check Docker status
docker-compose ps

# View logs
docker-compose logs -f

# Restart services
docker-compose restart
```

### Timeout Errors
```bash
# Increase timeout
npx playwright test --timeout=30000

# Check network connectivity
ping localhost:3000
```

### Browser Installation Issues
```bash
# Reinstall browsers
npx playwright install

# Install system dependencies
npx playwright install-deps
```

### Port Conflicts
```bash
# Find process using port
lsof -i :3000

# Kill process (if needed)
kill -9 <PID>
```

## 🔄 CI/CD Integration

### GitHub Actions
```yaml
name: E2E Tests
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - uses: actions/setup-node@v2
      - run: npm install
      - run: npx playwright install
      - run: npm run e2e
      - uses: actions/upload-artifact@v2
        if: always()
        with:
          name: playwright-report
          path: test-results/
```

## 📚 Resources

- [Playwright Documentation](https://playwright.dev)
- [Runtime configuration](docs/RUNTIME-CONFIG.md)
- Test reports: `npx playwright show-report` (written to `playwright-report/`, not committed)

## 🎯 Best Practices

1. **Isolation:** Each test should be independent
2. **No Hard Waits:** Use `waitForNavigation` instead of `waitForTimeout`
3. **Selectors:** Use `testid` or `aria-label` for stability
4. **Cleanup:** Use `afterEach` hooks for data cleanup
5. **Assertions:** Use strict assertions for clarity
6. **Performance:** Monitor test execution time

## 📞 Support

For test issues:
1. Check [Playwright docs](https://playwright.dev/docs/intro)
2. Review test logs and traces
3. Check Docker container health
4. Verify base URLs are correct
5. Ensure all services are running

## 📜 License

Part of VegaDūta platform testing suite.

---

**Last Updated:** 2026-08-03
**Total Tests:** 270+ test cases
**Coverage:** All major services and functionality
