# web

VegaDuta web console (Next.js standalone server, `web/Dockerfile`, port 3000), public on
`<global.hosts.app>.<global.domain>`. One prebuilt image works on any domain: the chart sets the public
config (`NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_KEYCLOAK_URL/REALM/CLIENT_ID`, `NEXT_PUBLIC_SITE_URL`, and
the code-intel / app-builder URLs when those components are on) as container env, which the app reads at
request time (`web/docs/RUNTIME-CONFIG.md`). Server-side calls go to the in-cluster core Service
(`API_BASE_URL`). Override or add keys with `env`.
