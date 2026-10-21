# Hosting behind a Cloudflare Tunnel (no open ports)

Run the Full tier on one machine and publish `app.`, `api.` and `auth.` on your domain through a
[Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/),
without opening any inbound port. Cloudflare terminates TLS; Caddy keeps routing by hostname and keeps
hiding the paths that must not be public (`/actuator`, the Keycloak admin console and master realm).

Use the Full tier here. The Lite tier runs Keycloak in dev mode on an embedded store and is for
localhost only.

## What you need

- A domain whose DNS is on Cloudflare (nameservers delegated), and `cloudflared` on the host.
- Docker with about 8 GiB free for the Full tier's default services (core, web, sidecar,
  Postgres, Keycloak and its database, PgBouncer, Valkey, SeaweedFS, Caddy).

## 1. Configure the stack

```bash
scripts/vegaduta up --full --domain example.com   # published release images; add --build to build from source
```

With `VEGADUTA_VERSION` empty (a git checkout), the CLI looks up the latest published release and
records it in `.env`. Then in `deploy/compose/.env` (the CLI writes `BASE_DOMAIN` and the `PUBLIC_*` URLs for you):

```
CADDY_AUTO_HTTPS=off          # Cloudflare terminates TLS
CADDY_SITE_SCHEME=http://     # Caddy serves plain HTTP on :80 for the tunnel to forward to
PUBLIC_BIND_ADDR=127.0.0.1    # Caddy listens on loopback only; the tunnel is the only way in
```

Both Caddy settings are needed: `CADDY_AUTO_HTTPS=off` alone leaves the sites expecting TLS on :443 and
nothing listens on :80. `ACME_EMAIL` may stay empty in this mode. Re-run `scripts/vegaduta up` after
changing them; the Keycloak reconcile step adds the web clients' redirect URIs derived from
`PUBLIC_APP_URL` and `PUBLIC_ADMIN_URL` when missing (existing entries, including ones you added by hand, are kept), so a domain change after first start does not end in
`Invalid parameter: redirect_uri`.

## 2. Create the tunnel and DNS routes

```bash
cloudflared tunnel login                       # opens a browser: authorise the zone
cloudflared tunnel create my-stage             # writes ~/.cloudflared/<id>.json (a secret; never commit it)
for h in app api auth www; do cloudflared tunnel route dns my-stage $h.example.com; done
```

The bare domain (`example.com`) needs the same route, but `route dns` cannot create it when an A/AAAA/CNAME record already exists there: delete or edit that record in the Cloudflare dashboard (DNS → Records) so it is a CNAME to `<tunnel id>.cfargotunnel.com`, proxied. `route dns` refuses to replace an existing record; add `--overwrite-dns` only when you mean to repoint a
hostname (for example from a retired tunnel).

`~/.cloudflared/config.yml`:

```yaml
tunnel: <tunnel id>
credentials-file: /home/you/.cloudflared/<tunnel id>.json
ingress:
  - { hostname: app.example.com,  service: http://localhost:80 }
  - { hostname: api.example.com,  service: http://localhost:80 }
  - { hostname: auth.example.com, service: http://localhost:80 }
  - { hostname: example.com,      service: http://localhost:80 }   # redirects to app.
  - { hostname: www.example.com,  service: http://localhost:80 }
  - service: http_status:404
```

All hostnames go to Caddy on port 80; it picks the upstream from the `Host` header. Add `admin.` (and
`codeintel.`, `builder.`, `livekit.` when those profiles run) the same way. Start it with
`cloudflared tunnel run my-stage`, or as a service so it survives a reboot.

## 3. Verify

| Check | Expect |
|---|---|
| `https://app.example.com` | 200, the console |
| `https://api.example.com/api/system/status/live` | 200 |
| `https://auth.example.com/realms/agentic-ai/.well-known/openid-configuration` | 200, `issuer` is `https://auth.example.com/realms/agentic-ai` |
| `https://api.example.com/actuator/health` | 404 (hidden by Caddy) |
| `https://auth.example.com/admin/` and `/realms/master/` | 404 (reach the admin console on the loopback port, or over an SSH tunnel) |
| Sign in at the console | redirected to `auth.example.com`, no `redirect_uri` error |

## Troubleshooting

| Symptom | Cause |
|---|---|
| 530 on every hostname | `cloudflared` is not running |
| Error 1033 on one hostname | That hostname's DNS still points at another (old/stopped) tunnel; re-route it. 1033 on all of them: the host slept or `cloudflared` stopped; keep the machine awake (`caffeinate -is`) or run `cloudflared` as a service |
| 502 on every hostname | Tunnel is up but nothing answers on `localhost:80`: Caddy is down or still serving TLS only (`CADDY_SITE_SCHEME` unset) |
| Caddy restarts every minute | An old Caddyfile with an empty `ACME_EMAIL`; update to a release that has the optional email directive |
| `Invalid parameter: redirect_uri` | The realm was imported with other `PUBLIC_*` values; re-run `scripts/vegaduta up` (reconcile) |
| 401 from the API after sign-in | Core must accept the public issuer: `KEYCLOAK_ISSUER_URI` is derived from `BASE_DOMAIN`; list any older issuer in `KEYCLOAK_ADDITIONAL_ISSUERS` |

Email (verification, resets) needs `KEYCLOAK_SMTP_*`; without them keep `KC_VERIFY_EMAIL=false`.
