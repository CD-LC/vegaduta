#!/bin/bash

# Comprehensive E2E Test Runner Script
# Runs all tests across all services and generates a complete report

set -e

echo "🧪 VegaDūta Comprehensive E2E Test Suite"
echo "========================================"
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
TIMESTAMP=$(date +"%Y-%m-%d %H:%M:%S")
REPORT_DIR="test-results"
FAILED_TESTS=0
PASSED_TESTS=0

# Ensure report directory exists
mkdir -p "$REPORT_DIR"

# Function to run tests and collect results
run_test_suite() {
  local suite_name=$1
  local test_file=$2

  echo -e "${BLUE}Testing: ${suite_name}${NC}"

  if npx playwright test "e2e/${test_file}.spec.ts" --reporter=json > "$REPORT_DIR/${test_file}-results.json" 2>&1; then
    echo -e "${GREEN}✅ ${suite_name} passed${NC}"
    ((PASSED_TESTS++))
  else
    echo -e "${RED}❌ ${suite_name} failed${NC}"
    ((FAILED_TESTS++))
  fi
  echo ""
}

# Check if services are running
check_services() {
  echo -e "${YELLOW}Checking service availability...${NC}"

  local services=(
    "http://localhost:3000:Web"
    "http://localhost:3001:Admin"
    "http://localhost:8080:API"
    "http://localhost:4100:Academy"
  )

  for service in "${services[@]}"; do
    IFS=':' read -r url name <<< "$service"

    if curl -s "$url" > /dev/null 2>&1; then
      echo -e "${GREEN}✅ $name available at $url${NC}"
    else
      echo -e "${YELLOW}⚠️  $name may not be available at $url${NC}"
    fi
  done
  echo ""
}

# Run all test suites
run_all_tests() {
  echo -e "${BLUE}Running E2E Test Suites...${NC}"
  echo ""

  run_test_suite "Landing Page (AXON Redesign)" "axon-redesign"
  run_test_suite "Admin Panel" "admin-panel"
  run_test_suite "Core API" "core-api"
  run_test_suite "Academy Platform" "academy-platform"
  run_test_suite "WhatsApp Integration" "whatsapp-integration"
}

# Generate comprehensive report
generate_report() {
  echo -e "${BLUE}Generating comprehensive test report...${NC}"

  if [ -f "scripts/generate-test-report.ts" ]; then
    npx ts-node scripts/generate-test-report.ts
  else
    echo -e "${YELLOW}Report generation script not found${NC}"
  fi
}

# Print summary
print_summary() {
  echo ""
  echo "========================================"
  echo -e "${BLUE}📊 Test Summary${NC}"
  echo "========================================"
  echo "Timestamp: $TIMESTAMP"
  echo ""

  total_suites=$((PASSED_TESTS + FAILED_TESTS))

  if [ $total_suites -gt 0 ]; then
    echo -e "Total Test Suites: $total_suites"
    echo -e "${GREEN}Passed: $PASSED_TESTS${NC}"
    echo -e "${RED}Failed: $FAILED_TESTS${NC}"

    pass_rate=$((PASSED_TESTS * 100 / total_suites))
    echo -e "Pass Rate: ${pass_rate}%"
  fi

  echo ""
  echo "📈 Results saved to: $REPORT_DIR/"
  echo ""

  if [ -f "$REPORT_DIR/report.html" ]; then
    echo "🌐 View HTML report:"
    echo "   file://$(pwd)/$REPORT_DIR/report.html"
  fi

  echo ""
}

# Main execution
main() {
  echo -e "${YELLOW}Starting test execution...${NC}"
  echo ""

  # Check services
  check_services

  # Run tests
  run_all_tests

  # Generate report
  generate_report

  # Print summary
  print_summary

  # Exit with appropriate code
  if [ $FAILED_TESTS -eq 0 ]; then
    echo -e "${GREEN}✅ All tests passed!${NC}"
    exit 0
  else
    echo -e "${RED}❌ Some tests failed. Check report for details.${NC}"
    exit 1
  fi
}

# Run main function
main
