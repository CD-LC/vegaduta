#!/usr/bin/env bash
# Runs INSIDE the keycloak container (stdin of `docker compose exec -T keycloak
# bash -s`). Invoked by reconcile-keycloak.sh and scripts/vegaduta(.ps1).
# Inputs (env): KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD, KEYCLOAK_CLIENT_SECRET
# (already in the container), REALM, SH/SP/SF/SD/SU/SW/ST (SMTP), GGID/GGSEC,
# GHID/GHSEC, MSID/MSSEC (social IdPs). See reconcile-keycloak.sh for why.
set -e
K=/opt/keycloak/bin/kcadm.sh
$K config credentials --server http://localhost:8080 --realm master \
  --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null
$K update users/profile -r "$REALM" -s "unmanagedAttributePolicy=ADMIN_EDIT"
# Public self-sign-up follows KC_REGISTRATION_ALLOWED (realm JSON default off).
# Enforced here, not only at first import: installs predating the gate had the
# realm flag on, and anyone who could reach Keycloak could self-register.
$K update "realms/$REALM" -s "registrationAllowed=${KC_REGISTRATION_ALLOWED:-false}"
echo "  [1/7] unmanagedAttributePolicy = ADMIN_EDIT, registrationAllowed=${KC_REGISTRATION_ALLOWED:-false}"
CID=$($K get clients -r "$REALM" -q clientId=agentic-ai-core --fields id --format csv --noquotes)
if [ -n "${KEYCLOAK_CLIENT_SECRET:-}" ]; then
  $K update "clients/$CID" -r "$REALM" -s "secret=$KEYCLOAK_CLIENT_SECRET"
  echo "  [2/7] agentic-ai-core secret synced"
fi
# Least privilege: exactly the realm-management roles core's Keycloak Admin API
# client uses (users, realm-role mappings, identity-provider toggles), never
# realm-admin (which also manages clients, realm settings and impersonation).
# Keep in step with deploy/keycloak/agentic-ai-realm.json and the Helm
# keycloak reconcile Job.
for role in view-users query-users manage-users query-groups view-realm view-identity-providers manage-identity-providers; do
  $K add-roles -r "$REALM" --uusername service-account-agentic-ai-core \
    --cclientid realm-management --rolename "$role" >/dev/null 2>&1 || true
done
# Installs older than this script granted realm-admin; take it back.
$K remove-roles -r "$REALM" --uusername service-account-agentic-ai-core \
  --cclientid realm-management --rolename realm-admin >/dev/null 2>&1 || true
echo "  [3/7] least-privilege realm-management roles on service-account-agentic-ai-core (realm-admin removed)"
# The browser clients' redirect URIs / web origins are templated from PUBLIC_APP_URL and
# PUBLIC_ADMIN_URL only when the realm is first imported. Re-assert them here (idempotent) so
# a domain or port change after the first start does not end in "Invalid parameter:
# redirect_uri" at sign-in. MERGE, never replace: entries an operator added by hand (a second
# hostname, a localhost dev URI) are kept and the derived one is added when missing. Stale
# entries from a previous domain are therefore kept too; remove them in the admin console.
# Both variables are already in the Keycloak container's environment.
# merge_list FIELD VALUE CLIENT_UUID -> the JSON array of FIELD with VALUE appended if absent.
merge_list() {
  local cur
  cur=$($K get "clients/$3" -r "$REALM" --fields "$1" 2>/dev/null | tr -d '\n\r' \
    | sed -e 's/^[^[]*\[//' -e 's/\][^]]*$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
  case ",$cur," in
    *"\"$2\""*) printf '[%s]' "$cur" ;;
    *) printf '[%s]' "${cur:+$cur, }\"$2\"" ;;
  esac
}
for pair in "agentic-ai-web:${PUBLIC_APP_URL:-}" "agentic-ai-platform-admin:${PUBLIC_ADMIN_URL:-}"; do
  client="${pair%%:*}"; url="${pair#*:}"; url="${url%/}"
  [ -n "$url" ] || continue
  WID=$($K get clients -r "$REALM" -q "clientId=$client" --fields id --format csv --noquotes)
  [ -n "$WID" ] || continue
  $K update "clients/$WID" -r "$REALM" -s "rootUrl=$url" -s "baseUrl=$url" \
    -s "redirectUris=$(merge_list redirectUris "$url/*" "$WID")" \
    -s "webOrigins=$(merge_list webOrigins "$url" "$WID")"
  echo "  [web] $client redirect URIs/web origins include $url (existing entries kept)"
done
if [ -n "$SH" ]; then
  AUTH=false; [ -n "$SU" ] && AUTH=true
  printf "%s" "{\"smtpServer\":{\"host\":\"$SH\",\"port\":\"$SP\",\"from\":\"$SF\",\"fromDisplayName\":\"$SD\",\"replyTo\":\"$SF\",\"auth\":\"$AUTH\",\"user\":\"$SU\",\"password\":\"$SW\",\"starttls\":\"$ST\",\"ssl\":\"false\"}}" > /tmp/smtp.json
  $K update "realms/$REALM" -f /tmp/smtp.json
  rm -f /tmp/smtp.json
  echo "  [4/7] SMTP configured ($SH:$SP)"
else
  echo "  [4/7] SMTP not configured (set KEYCLOAK_SMTP_HOST to enable e-mail)"
fi
for pair in "google:$GGID:$GGSEC:openid profile email" "github:$GHID:$GHSEC:read:user user:email" "microsoft:$MSID:$MSSEC:openid profile email"; do
  prov=${pair%%:*}; rest=${pair#*:}; cid=${rest%%:*}; rest2=${rest#*:}; csec=${rest2%%:*}; scope=${rest2#*:}
  if [ -n "$cid" ]; then
    $K update "identity-provider/instances/$prov" -r "$REALM" -s enabled=true \
      -s "config.clientId=$cid" -s "config.clientSecret=$csec" -s "config.defaultScope=$scope"
    echo "  [5/7] $prov login enabled"
  fi
done
RP=$($K get "authentication/flows/first%20broker%20login/executions" -r "$REALM" --fields id,providerId --format csv --noquotes 2>/dev/null | grep idp-review-profile | cut -d, -f1 || true)
if [ -n "$RP" ]; then
  $K update "authentication/flows/first%20broker%20login/executions" -r "$REALM" \
    -b "{\"id\":\"$RP\",\"requirement\":\"DISABLED\",\"displayName\":\"Review Profile\",\"alias\":\"review profile config\",\"providerId\":\"idp-review-profile\",\"configurable\":true,\"level\":0,\"index\":0}" >/dev/null 2>&1 || true
fi
$K update authentication/required-actions/VERIFY_PROFILE -r "$REALM" -s enabled=false >/dev/null 2>&1 || true
echo "  [6/7] social first-login profile review disabled"
