# SOC 2 and ISO/IEC 27001 evidence map

Where the controls in this repository line up against the SOC 2 Trust Services Criteria (TSC)
and the ISO/IEC 27001:2022 Annex A control themes. Read the [pack index](README.md) first for the
status legend and limits.

> **No attestation exists.** The VegaDūta software is not SOC 2 attested and not ISO/IEC 27001
> certified, and this page does not say it is "SOC 2 ready" or "27001 aligned". A SOC 2 report is
> an auditor's opinion on an organisation's controls over a period; ISO/IEC 27001 certifies an
> organisation's information security management system. What a software repository can offer
> either one is **evidence**: configuration, automated checks and records. That is what is mapped
> here. It matches the [compliance posture page](../platform-documentation/security/compliance-posture.html),
> which lists the same controls at a coarser grain and keeps the "no certification" statement.

For CI workflows, the "test" is the gate itself (it fails the build) plus a self-test script where
one exists; "key" is `none (workflow)`. Paths are repo-relative; `core/<module>/.../Class.java`
elides the package directories.

## The gates that carry most of the change-management and testing evidence

These run on pull requests and `main`; the table is reused by the criteria below.

| Gate | Where | What it checks | Self-test |
|---|---|---|---|
| CI umbrella `CI OK` | `.github/workflows/ci.yml` (job `ci-ok`) | Aggregates core, sdk, web, python, node-services, sdk-clients, shellcheck, hadolint, compose, helm, static-gates, dco, dependency-review, docs-links | the jobs below |
| Core build and tests with Testcontainers integration suite | `ci.yml` job `core`: `mvn -B -ntp -Pcoverage,it verify` | Unit and integration tests against a real Postgres image | the test suite |
| Static gates | `.github/workflows/static-gates.yml` (job `gates`) | Flyway lint, entity/table/bean/path namespace lint, file-size and module-size ratchets, OpenAPI breaking-change gate, SDK and AsyncAPI freshness, docs links, no-telemetry check, Dependabot coverage, "make gates and this workflow run the same checks" | `scripts/ci/test-gates-parity.sh`, `scripts/ci/openapi-breaking.test.mjs` |
| Secret scan | `static-gates.yml` job `secret-scan` (gitleaks), `scripts/ci/secret-scan.sh` | New commits for committed secrets | none |
| DCO sign-off | `ci.yml` job `dco`, `scripts/ci/dco-check.sh` | Every non-merge PR commit carries `Signed-off-by` | `scripts/ci/test-dco-check.sh` |
| Dependency review | `ci.yml` job `dependency-review` | Blocks a PR adding a high or critical vulnerable dependency or an incompatible licence | none |
| SAST | `.github/workflows/codeql.yml` | CodeQL on PRs, pushes to `main`, weekly (Monday) | none |
| SCA and IaC scan | `.github/workflows/trivy.yml` | Filesystem scan: dependency CVEs, Dockerfile, Helm, Terraform, Compose misconfiguration; weekly (Thursday) | none |
| DAST | `.github/workflows/zap.yml` | ZAP baseline, passive, against the Lite stack built from the commit; weekly (Wednesday) | none |
| Fuzzing | `.github/workflows/fuzz.yml` (ClusterFuzzLite, `.clusterfuzzlite/`) | Redaction regex chains and skill-draft scrubbing; PR pass and nightly | the fuzz targets |
| Licence scan | `.github/workflows/license-scan.yml`, `scripts/ci/license-allowlist.txt`, `scripts/ci/license-drift-check.py` | Dependency licences against an allow-list; weekly (Friday) | none |
| OpenSSF Scorecard | `.github/workflows/scorecard.yml` | Repository-practice score; weekly (Tuesday) | none |
| E2E and load | `.github/workflows/e2e.yml`, `.github/workflows/load-nightly.yml` | Full-stack Playwright suite nightly; load gate nightly | the suites |
| Boot check | `ci.yml` step "Boot check (packaged jars ...)", `scripts/ci/boot-check.sh` | Boots the packaged api, migrate-only, jobs and worker jars | **advisory**: `continue-on-error: true` until green on `main` |
| Coverage | `ci.yml` job `core`, profile `-Pcoverage`, artifact `core-coverage` | JaCoCo reports | **report-only: no threshold is enforced** |

## SOC 2 Trust Services Criteria

Common Criteria CC1 to CC9, then the additional criteria. Criteria ids are the published
structure of the 2017 TSC (with revised points of focus); the criteria text was not reproduced.

### CC1 to CC5: control environment, communication, risk, monitoring, control activities

| Criteria | Status | Evidence (path, key, test) | Gap |
|---|---|---|---|
| CC1 Control environment (integrity, oversight, structure, competence, accountability) | Organisational | `.github/CODEOWNERS` routes all paths to the `maintainers` team; `CONTRIBUTING.md`, `GOVERNANCE.md`, `MAINTAINERS.md` | Board oversight, HR practices, competence and accountability are an organisation's. Branch-protection and required-review settings live in GitHub, not in the repository, and were not verified |
| CC2 Communication and information | Partly | `SECURITY.md` (private vulnerability reporting, response targets); docs site `.github/workflows/docs.yml`; `docs/maintainers/agent-coordination.md` (release and deploy log); `CHANGELOG.md` | No customer-facing status or incident history beyond `/api/public/status` (`core/security/.../SecurityConfig.java`). Key `none`; test: link check `scripts/ci/docs-link-check.sh` |
| CC3 Risk assessment | Partly | [OWASP mapping](../security/owasp-mapping.md), `docs/platform-documentation/security/threat-model.html`, `docs/architecture/STRUCTURAL-AUDIT-2026-10-14.md`, `docs/engineering/STANDARDS-AUDIT.md`. Key `none`; test: architecture baselines `StructureRulesTest`, `ModuleBoundaryRulesTest` (`core/app`) | Internal reviews only, not published and not third-party. No formal, periodic risk register or fraud-risk assessment |
| CC4 Monitoring activities | Partly | Gates table above; Scorecard weekly; observability: [observability handbook page](../platform-documentation/operations/observability.html); SLO targets under `app.slo` in `core/app-config/src/main/resources/application.yml` | Deficiency tracking and management reporting are processes |
| CC5 Control activities | Partly | `make gates` (the pre-push hook from `make hooks`) runs the same checks as `static-gates.yml`, enforced by the parity gate. Key `none`; test `scripts/ci/test-gates-parity.sh` | Policies and procedures (CC5.3) are the organisation's |

### CC6 Logical and physical access

| Criteria | Status | Evidence (path, key, test) | Gap |
|---|---|---|---|
| CC6.1 Logical access security, identification and authentication | Covered | Keycloak OIDC with issuer set and authorized-party allow-list; tenant claim copied to `TenantContext`; claim-less token rejected: `core/security/.../SecurityConfig.java`, `TenantContextFilter.java`; key `app.auth.mode` (default `keycloak`; unknown value stops boot). Tests `TenantContextFilterTest`, `GranularPermissionConverterTest`. Model: [authorization](../security/authorization.md) | MFA, password policy and session lifetime are Keycloak realm settings (operator). `personal` profile uses local auth for one owner |
| CC6.1 Authorisation (role and permission) | Covered | `PERM_*` matchers in `SecurityConfig.java`; stored grants `core/admin/.../permission/`; tests `DefaultPermissionBundlesTest`, `TenantAssignableRolesTest`, `GranularPermissionConverterTest`; denied access audited `core/security/.../AuditingAccessDeniedHandler.java` | Periodic access review is the operator's; the product lists roles and audit entries only |
| CC6.1 Tenant isolation | Covered | Schema-per-tenant selected by `SET search_path`: `core/tenancy/.../SchemaMultiTenantConnectionProvider.java`, `TenantSchemaGuard.java`; tenant propagation across threads `core/security/.../TenantScope.java`; tests `SchemaMultiTenantConnectionProviderTest`, `TenantSchemaGuardTest`, `TenantScopeTest`, `AsyncHopTenantScopeRulesTest` (`core/app`, fails the build on an unwrapped thread hop). Operational rule: PgBouncer must stay in session mode (`AGENTS.md` rule 8) | Isolation is logical (schemas in one database). No row-level-security layer. No cross-tenant penetration test published. Knowledge collections have no per-collection access control list inside a tenant; per-collection access control is in progress on a branch, not yet released |
| CC6.1 Encryption and key management | Partly | AES-256-GCM with dual-key rotation: `core/crypto/.../EncryptionService.java`, test `EncryptionServiceTest`; key `APP_ENCRYPTION_KEY` (identical across core shapes); [key rotation](../operations/key-rotation.md); `core/secrets/.../SecretRedactor.java` (test `SecretRedactorTest`) | Key custody and a KMS/HSM are the operator's. No automatic rotation |
| CC6.1 Service identities and delegated authority | Partly | Delegation tokens act as the delegating user: `core/security/.../DelegationTokenAuthFilter.java`, `core/agents/.../trust/DelegationTokenService.java`, test `DelegationTokenAuthFilterTest`; MCP grant keys hashed: `McpApiKeyHasherTest`; A2A card signing | No per-agent workload identity and no general mandate model: [ADR-0027](../architecture/ADR-0027-agent-mandates-and-delegated-authority.md) (proposal) |
| CC6.2-CC6.3 Provisioning, role changes, removal | Partly | SCIM provisioning `core/admin/.../sso/ScimProvisioningController.java`; Keycloak admin port `core/admin/.../keycloak/KeycloakAdminClient.java`; tenant account deletion `AccountSelfDeletionServiceTest` | Joiner-mover-leaver process is the operator's |
| CC6.4 Physical access | Not applicable to the software | none | Operator or cloud provider |
| CC6.6 Boundary protection | Partly | Outbound URL guard and egress allow-list `core/security/.../net/SsrfGuard.java`, key `app.security.outbound.egress-allow-list`, test `SsrfGuardEgressAllowListTest`; Helm network policy `deploy/helm/charts/vegaduta/templates/networkpolicy.yaml`; MCP stdio sidecar on an isolated network `services/mcp-stdio-runner`; rate limits with Redis and local fallback `core/security/.../ratelimit/` (tests `RedisFixedWindowCounterTest`, `LocalRateLimitFallbackTest`) | Egress allow-list is platform-wide, not per agent. Compose network design is a deployment choice |
| CC6.7 Transmission and removal of data | Partly | Webhook signature verification: tests `MetaWhatsAppSignatureValidatorTest`, `SlackSignatureValidatorTest`, `TwilioSignatureValidatorTest`, `RazorpayWebhookControllerSignatureTest`; log masking `LogMasking` (test `LogMaskingTest`) | TLS termination is the deployment's (Caddy/Ingress) |
| CC6.8 Malicious software | Partly | Skill scanning `core/agents-turn/.../skills/SkillScanner.java`; Trivy, CodeQL, grype in release | Endpoint anti-malware is the operator's |

### CC7 System operations

| Criteria | Status | Evidence (path, key, test) | Gap |
|---|---|---|---|
| CC7.1 Detect configuration changes and vulnerabilities | Partly | Gates table (CodeQL, Trivy, ZAP, fuzz, Scorecard, Dependabot `.github/dependabot.yml`, gate "Dependabot covers every manifest" in `static-gates.yml`, script `scripts/ci/dependabot-coverage.sh`) | Scanning the *running* deployment's images is the operator's. A critical CVE with no fix is risk-accepted in a baseline (see `docs/maintainers/agent-coordination.md`) |
| CC7.2 Monitor for anomalies | Partly | Audit log `core/audit/.../AuditLogService.java` (test `AuditLogServiceTest`); guardrail violation logging (test `GuardrailViolationLoggerTest`); metrics and Grafana/Loki under the `observability` Compose profile | No central SIEM. No behavioural anomaly detection on agents |
| CC7.2 Tamper-evident audit | Partly | Ed25519 seals over audit ranges: `core/audit/.../seal/AuditSealService.java`, `AuditSealVerifier.java`; sweep `core/platform/.../auditseal/AuditSealSweep.java`; key `app.audit.seal.enabled` (**default false**; needs a pinned key); tests `AuditSealServiceTest`, `AuditSealVerifierTest`, `AuditSealSweepTest`, `AuditSealA2aSignerTest`; offline verifier `scripts/verify-audit-export.py` | Off until enabled. The audit log is not purged by retention (comment under `app.retention.purge` in `application.yml`) |
| CC7.3-CC7.4 Evaluate and respond to incidents | Partly | [Breach runbook](../operations/breach-runbook.md); audit export for a time range `core/app/.../common/privacy/AuditExportService.java` (test `AuditExportCsvTest`); kill switches `core/tenancy/.../TenantEmergencyControlService.java` (test `TenantEmergencyControlServiceTest`) | Incident response plan, on-call and communication are the operator's |
| CC7.5 Recover from incidents | Partly | [Rollback and recovery](../platform-documentation/deployment/rollback-recovery.html); see A1 | |

### CC8 Change management

| Criteria | Status | Evidence (path, key, test) | Gap |
|---|---|---|---|
| CC8.1 Authorised, tested, approved changes | Partly | Pull-request flow with `CI OK` and `CodeQL OK` required before merge and before a release: `.github/workflows/release.yml` job `verify` ("Wait for CI OK and CodeQL OK"); DCO gate; CODEOWNERS; migration lint `scripts/ci/flyway-lint.sh` (edit guard on applied migrations, destructive-DDL scan, version collision); OpenAPI breaking-change gate `scripts/ci/openapi-breaking.mjs` (test `openapi-breaking.test.mjs`); architecture baselines only shrink (`StructureRulesTest`). Coordination: `docs/maintainers/agent-coordination.md` | **Coverage is report-only**: no threshold. The boot check is advisory. Required reviews and branch protection are repository settings not verified here |
| CC8.1 Release integrity | Covered | Release workflow: image built per architecture, merged manifest, grype scan failing on critical, keyless `cosign sign` of the image digest, SPDX SBOM (syft) with attestation, build-provenance attestation (`actions/attest-build-provenance`), cosign-signed tarballs and `SHA256SUMS`, signed Helm chart: `.github/workflows/release.yml` (jobs `publish`, `build-omnibus`, chart job); verify commands in the file header (`cosign verify`, `gh attestation verify`). Key `none (workflow)` | Provenance is GitHub's build-provenance attestation; **no SLSA level is claimed here** and the workflow was not independently assessed. A SBOM over 16 MiB is stored as an artifact but not attested (the workflow warns). Test: the release is itself the test; no unit test |
| CC8.1 Separation of environments, rollback | Partly | `scripts/vegaduta upgrade` takes a backup first; images pinned by version (`AGENTS.md` coordination rule 6); [rollback](../platform-documentation/deployment/rollback-recovery.html) | |

### CC9 Risk mitigation

| Criteria | Status | Evidence (path, key, test) | Gap |
|---|---|---|---|
| CC9.1 Business-disruption risk mitigation | Partly | See A1 | |
| CC9.2 Vendor and business-partner risk | Partly | Dependency and licence controls (gates table); provider list `core/providers/.../ProviderName.java`; MCP tool pinning `core/agents-tools/.../McpToolPinGate.java` (key `app.mcp.tool-pinning.mode`, default `warn`; tests `McpToolPinServiceTest`, `McpToolPinTest`) | No vendor register, subprocessor list, DPAs or model cards ship |

### Additional criteria

| Criteria | Status | Evidence (path, key, test) | Gap |
|---|---|---|---|
| A1.1-A1.2 Availability: capacity, environmental, backup | Partly | `scripts/vegaduta backup` and `restore` (database dumps, files, `.env`, MANIFEST; see `deploy/README.md#backup-and-restore`); [backup and restore handbook page](../platform-documentation/operations/backup-restore.html); Helm `postgres.instances` and `postgres.backup` / `postgres.scheduledBackup` (`deploy/helm/README.md`); spend and rate caps. Key `none`; test: `tests/install` installer tests | Scheduling and **a tested restore drill are the operator's**; no RTO/RPO targets ship; Compose tiers are single host; core runs one API replica until Helm readiness flags are set |
| A1.3 Recovery testing | **Not covered** | none | No restore test or DR drill in CI. `deploy/README.md` says to test a restore on a spare machine |
| C1.1-C1.2 Confidentiality: identification, disposal | Partly | Encryption at rest; redaction; retention purge `core/platform/.../retention/DataRetentionPurge.java`, key `app.retention.purge.enabled` (**default false**, `dry-run` default true), test `DataRetentionPurgeTest`; per-tenant erasure `core/tenancy/.../TenantDataExportService.java` (test `TenantErasureCompletenessTest`); contact privacy purge `core/app/.../common/privacy/RetentionPurger.java` | Purge is opt-in. Erasure does not reach backups or provider copies. Data classification: `core/app/.../common/privacy/PrivacyDataClass.java` (test `PrivacyDataClassTest`) covers privacy data classes only |
| PI1 Processing integrity | Partly | Idempotent replay `core/agents/.../tools/EffectJournal.java` (test `EffectJournalTest`); step journal key `durability.step-journal-enabled` (default true; test `AgentJobStepJournalSafetyTest`); Flyway migrations checked in CI; OpenAPI freshness test `OpenApiSpecFreshnessTest` | **Workflow execution is not durable by default** (`app.workflow.execution-engine` = `legacy`: a crashed run is reaped FAILED). No criteria-specific accuracy and completeness reconciliation beyond tests |
| P1-P8 Privacy | Partly | See the [DPDP page](dpdp-obligations.md): notice and consent step (`AgentChatServiceConsentGateTest`, `ConsentTextTest`), purpose tags, retention, per-contact export and erasure, audit export | The posture page says the privacy criteria are not addressed specifically; lawful basis, notices and DPAs are the operator's |

## ISO/IEC 27001:2022 Annex A

Control ids are the published 2022 numbering (A.5 organisational, A.6 people, A.7 physical, A.8
technological, 93 controls). The standard is paid and its text was not consulted; ids and titles
are an index. Controls not listed are **Organisational** or not applicable to software (all of
A.6 people controls and A.7 physical controls).

| Control | Status | Evidence (path, key, test) | Gap |
|---|---|---|---|
| A.5.1-A.5.4 Policies, roles, segregation of duties, management responsibilities | Organisational | CODEOWNERS; roles in `SecurityConfig.java` | Segregation of duties in the product: no distinct-approver option for tenant admins (`core/approval/.../ApprovalController.java`, `rejectSelfApproval`) |
| A.5.7 Threat intelligence | Partly | Dependabot, Trivy, CodeQL feeds; [OWASP mapping](../security/owasp-mapping.md) | |
| A.5.9 Inventory of information and assets | Partly | SBOMs per release image; generated config reference `docs/engineering/CONFIG-REFERENCE.generated.md` | No asset register |
| A.5.15-A.5.18 Access control, identity, authentication information, access rights | Covered (platform) | As CC6.1; Keycloak and SCIM | Realm policy is the operator's |
| A.5.19-A.5.23 Supplier relationships, cloud services | Partly | As CC9.2 | No supplier register |
| A.5.24-A.5.28 Incident management | Partly | As CC7.3-CC7.4 | |
| A.5.30 ICT readiness for business continuity | Partly | As A1 | No tested drill |
| A.5.31-A.5.36 Legal, IP, records, privacy, compliance | Partly | [DPDP page](dpdp-obligations.md); licence gates | Records retention policy is the operator's |
| A.8.1-A.8.3 Endpoint devices, privileged access, information access restriction | Partly | `PERM_*` matchers; tenant isolation (CC6.1) | |
| A.8.5 Secure authentication | Partly | `core/security/.../SecurityConfig.java`; Keycloak | MFA is a realm setting |
| A.8.7 Protection against malware | Partly | Skill scanner; image scans | |
| A.8.8 Management of technical vulnerabilities | Partly | CodeQL, Trivy, Dependabot, grype at release (CC7.1) | Running-system scanning is the operator's |
| A.8.9 Configuration management | Partly | Config in `core/app-config/src/main/resources/application*.yml`; generated reference; `scripts/ci/compose-validate.sh`, `scripts/ci/helm-validate.sh` | |
| A.8.10 Information deletion | Partly | `DataRetentionPurge` (opt-in), tenant erasure | Backups and provider copies |
| A.8.11-A.8.12 Data masking, data leakage prevention | Partly | `LogMasking`, `SecretRedactor`, `PiiGuardrail`; fuzzed redaction (`fuzz.yml`) | Regex-based |
| A.8.13 Information backup | Partly | As A1 | No restore drill |
| A.8.15-A.8.16 Logging, monitoring | Partly | As CC7.2 | Seals off by default; no SIEM |
| A.8.20-A.8.22 Network security | Partly | `SsrfGuard`, Helm network policy, sidecar isolation | Deployment-specific |
| A.8.24 Use of cryptography | Partly | `EncryptionService`; Ed25519 seals; cosign | |
| A.8.25-A.8.28 Secure development, application security requirements, secure coding | Partly | CodeQL, gitleaks, ArchUnit rules, `AGENTS.md` rules | No static analysis beyond CodeQL for Java (see `docs/engineering/STANDARDS-AUDIT.md`) |
| A.8.29 Security testing in development and acceptance | Partly | CI test suites, ZAP, fuzz, e2e | **Coverage report-only** |
| A.8.31-A.8.33 Separation of environments, change management, test information | Partly | As CC8.1; `scripts/ci/testdata` | |
| A.8.34 Protection of information systems during audit testing | Not covered | none | |

## Known gaps

- Coverage is report-only in CI; the boot check is advisory.
- No tested backup restore or DR drill; no RTO/RPO targets (operator).
- Audit seals are off by default and the audit log has no central SIEM export.
- Tenant isolation is logical (schema per tenant); no row-level security layer and no published cross-tenant test report.
- Knowledge collections had no per-collection access control list; per-collection access control is in progress on a branch, not yet released.
- No distinct-approver option for tenant admins.
- Workflow execution is not durable by default.
- No vendor register, subprocessor list or DPAs; reviews so far were internal and are unpublished.
- Branch protection, required reviews and org-level controls could not be verified from the repository.

## Not verified

- Nothing was run on a stack; workflows were read, not executed. Whether each gate is a *required*
  status check on `main` is a repository setting.
- The SOC 2 criteria and ISO/IEC 27001 control texts were not consulted; identifiers are an index.
- The cosign, SBOM and provenance steps were read in `release.yml`; no release artifact was verified for this pack.
