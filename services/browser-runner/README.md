# browser-runner

A Playwright server in its own container: the browser behind the personal install's **browser add-on**
(`scripts/vegaduta personal --browser`, or the checkbox in the Setup app). Core connects to it with
`app.browseruse.remote-url` (`APP_BROWSERUSE_REMOTE_URL`) and drives it through the same `browser_*` tools as
an in-core Chromium, so domain policy, confirmation gates, saved logins and the sign-in takeover are unchanged.

- Version: the base image and `package.json` pin Playwright **1.49.0**, the client pinned in
  `core/agents-browser/pom.xml`. Bump all three together; a mismatch fails at connect with a clear error.
- Network: `deploy/compose/browser.yml` puts it on `browser-net`, shared with core only. It publishes no port.
  It can reach the internet (that is its job) but not Postgres, Valkey or the other services.
- Resources: 2 GB memory limit (`BROWSER_RUNNER_MEM_LIMIT`), 1 GB shared memory, no capabilities.
