# browser-runner

A Playwright server in its own container: the browser behind the personal install's **browser add-on**
(`scripts/vegaduta personal --browser`, or the checkbox in the Setup app). Core connects to it with
`app.browseruse.remote-url` (`APP_BROWSERUSE_REMOTE_URL`) and drives it through the same `browser_*` tools as
an in-core Chromium, so domain policy, confirmation gates, saved logins and the sign-in takeover are unchanged.

- Version: the base image tag, `package.json` (and its lock) pin Playwright **1.63.0**, the client pinned in
  `core/agents-browser/pom.xml`. Bump all of them together; a mismatch fails at connect with a clear error
  (`428 Precondition Required, Playwright version mismatch`), while the container still reports healthy, so
  `bash scripts/ci/check-browser-runner-version.sh` (a static gate) fails the build when they drift.
- The image runs the server with `node` and carries no npm: the npm bundled in the base image brings its own
  copies of `tar`, `undici`, `ip-address` and `brace-expansion`, which the release image scan flags.
- Network: `deploy/compose/browser.yml` puts it on `browser-net`, shared with core only. It publishes no port.
  It can reach the internet (that is its job) but not Postgres, Valkey or the other services.
- Resources: 2 GB memory limit (`BROWSER_RUNNER_MEM_LIMIT`), 1 GB shared memory, no capabilities.
