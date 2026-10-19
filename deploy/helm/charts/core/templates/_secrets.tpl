{{/* Secrets for core are resolved by vegaduta-lib (templates/_core.tpl):
  vegaduta-lib.coreSecretEnv  - secretKeyRef env (APP_ENCRYPTION_KEY, DB, Valkey, S3, OIDC, internal tokens)
  vegaduta-lib.coreEnvFrom    - the whole app Secret as env (optional core settings)
  This chart never creates Secrets; the umbrella (or you) does. */}}
