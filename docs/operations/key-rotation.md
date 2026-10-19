# Rotating `APP_ENCRYPTION_KEY`

`APP_ENCRYPTION_KEY` (base64 of exactly 32 bytes) encrypts every stored
provider key, channel secret and vault entry. It must be identical for every
core shape (api, jobs, worker, migrate) and every process sharing the database.
Losing it makes those rows unreadable; leaking it means rotating it.

## What the platform gives you

- **Two keys at once.** With `APP_ENCRYPTION_KEY_PREVIOUS` set, decryption
  falls back to the previous key when the current one fails
  (`EncryptionService`). New writes always use the current key.
- **Re-encryption of the vault.** `SecretRotationService.rotateAll()` rewrites
  every `tenant_secret` row of **one tenant schema** under the current key. It
  is idempotent.

## What it does not give you (yet)

- Nothing calls `rotateAll()`: there is no endpoint, CLI or job that iterates
  the tenants. Until one exists, step 3 below is a manual operation per tenant,
  or a scripted one you write against that service.
- Only `tenant_secret` rows are covered. Provider credentials, channel
  secrets and other encrypted columns are re-encrypted only when they are saved
  again through their normal update endpoints.

Treat the previous key as live for as long as any row may still depend on it.

## Procedure

1. **Generate** a new key: `openssl rand -base64 32`.
2. **Dual-key restart.** Set `APP_ENCRYPTION_KEY` to the new value and
   `APP_ENCRYPTION_KEY_PREVIOUS` to the old one on every core shape (Compose:
   `deploy/compose/.env`; Helm: the `<release>-core-app` Secret or your
   `global.core.appSecret.existingSecret`). Restart. Everything keeps decrypting
   through the fallback; new writes use the new key.
3. **Re-encrypt.** For each tenant: run `SecretRotationService.rotateAll()` in
   that tenant's context, and re-save every provider credential and channel
   secret through the console (or their `PUT` endpoints). Keep a list of what
   was re-saved.
4. **Verify.** Temporarily start a non-production copy with only the new key
   (no `_PREVIOUS`) against a database backup taken after step 3, and open each
   provider and channel page: a decryption failure shows as an unusable
   credential and is logged by `EncryptionService`.
5. **Drop the old key.** Remove `APP_ENCRYPTION_KEY_PREVIOUS` on every shape
   and restart. Store the old key offline for the retention period of your
   backups: a backup from before step 3 still needs it.

## Backups

The key is not in the database. Keep it in a separate secret store from the
database dumps, and never in the same folder (`scripts/vegaduta backup` writes
a copy of `.env` next to the dump; move it).
