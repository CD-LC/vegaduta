import { defineConfig } from "vite";

export default defineConfig({
  // Relative base so the build works under any GitHub Pages project path
  // (https://<owner>.github.io/<repo>/), not just a domain root.
  base: "./",
});
