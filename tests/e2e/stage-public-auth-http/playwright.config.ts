// Scratch copy of the prod specs retargeted to http:// (the kind stage's configured public URLs are http://, so https access = mixed content).
import base from "../playwright.prod.config";
export default { ...base, testDir: "./tests-prod", outputDir: process.env.E2E_OUT || "test-results-stage", reporter: [["list"], ["json", { outputFile: process.env.E2E_JSON || "stage-results.json" }]], use: { ...base.use, baseURL: "http://app.127.0.0.1.nip.io", ignoreHTTPSErrors: true } };
