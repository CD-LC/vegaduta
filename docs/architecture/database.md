# Database architecture

VegaDūta stores data in one PostgreSQL database with two kinds of schema:

- **`control_plane`** - one schema for the whole platform: tenants, users, billing, feature flags, platform settings.
- **One schema per tenant** - every tenant's agents, workflows, knowledge base, chats and logs. Tenant schemas are named by `TenantSchemaResolver` and created from the tenant migration set.

The full, column-level reference is generated from a live database and lives in [database-reference.md](database-reference.md).

## Sizes (snapshot)

Taken from a running stack (`ghcr.io/cd-lc/core:0.1.12`) on 2026-10-30:

| Schema | Tables | Notes |
|---|---|---|
| `control_plane` | 63 | Platform-wide |
| `public` | 227 | The tenant template. Every tenant schema has the same 227 tables. |
| `demo_shared` | 227 | The shared demo tenant |

The tenant Flyway history in the snapshot reaches version 674, which matches the highest tenant migration in `core/app/src/main/resources/db/migration/tenant/`.

## How requests reach the right schema

1. The request's tenant is resolved from the token.
2. `SchemaMultiTenantConnectionProvider` (`core/tenancy`) sets the connection's `search_path` to that tenant's schema.
3. Every query after that sees only that tenant's tables.

Two consequences:

- **PgBouncer must run in session pooling mode.** Transaction pooling lets a connection keep one tenant's `search_path` while the next transaction belongs to another tenant. See `AGENTS.md` rule 8.
- **Code that bypasses the JPA connection must name the schema.** The pgvector table `knowledge_chunks` is written with raw JDBC, so it uses `KnowledgeSchemaSupport.qualifiedTableName()`, which spells out the schema.

## Migrations

There are two Flyway sets, in `core/app/src/main/resources/db/migration/`:

- `controlplane/` - applied once, to `control_plane`.
- `tenant/` - applied to every tenant schema, including each new tenant at signup.

The two folders share **one version namespace**. `V412` in one folder and `V412` in the other is a duplicate that stops every core boot. Released migrations are never edited. Run `bash scripts/ci/flyway-lint.sh` before a PR.

## Vector storage (pgvector)

Knowledge chunks are the only table with a vector column:

- `knowledge_chunks.embedding` is `vector(768)`. Every embedding model is zero-padded to 768 dimensions, so one column serves all of them. `embedding_model` says which model produced each row.
- Vector search uses one **partial HNSW index per model**: `knowledge_chunks_hnsw_<model>` with `WHERE embedding_model = '<model>'`. These are created at runtime by `VectorSchemaInitializer`, not by Flyway, once a model has at least 5,000 chunks.
- `knowledge_chunk_reembed_shadow` holds new vectors while a collection is re-embedded to another model. The swap copies them into `knowledge_chunks` in one transaction.

## Table groups

These are the tenant tables grouped by area. The reference lists every column.

| Area | Tables |
|---|---|
| Agents | `agent`, `agent_version`, `agent_policy`, `agent_capability`, `agent_memory`, `agent_job`, `agent_job_progress`, `agent_schedule`, `agent_schedule_run`, `agent_event_trigger`, `agent_event_trigger_fire`, `agent_step_journal`, `agent_scratchpad`, `tenant_memory`, `agent_share`, `agent_collaborator`, `agent_delegation_grant`, `agent_delegation_token`, `agent_experiment`, `agent_experiment_assignment`, `agent_health_score`, `agent_handoff_audit`, `agent_specialist`, `agent_wallet`, `subagent_run`, `turn_journal_event`, `turn_feedback`, `tool_call_log`, `llm_call_log` |
| Allowed resources (what an agent may use) | `agent_allowed_tool`, `agent_allowed_skill`, `agent_allowed_knowledge_collection`, `agent_allowed_knowledge_write_collection`, `agent_allowed_codeintel_repo`, `agent_enabled_guardrail`, `agent_tool_arg_policy` |
| Knowledge | `knowledge_collection`, `knowledge_document`, `knowledge_chunks`, `knowledge_parent_chunk`, `knowledge_chunk_reembed_shadow`, `knowledge_reembed_job`, `knowledge_ingestion_job`, `knowledge_connector`, `knowledge_face_enrollment` |
| Workflows | `workflow_definition`, `workflow_version`, `workflow_run`, `workflow_run_node_output`, `workflow_node_pin`, `workflow_trigger_node`, `workflow_wait`, `workflow_input_request`, `workflow_poll_trigger`, `workflow_event_outbox`, `workflow_share`, `workflow_comment`, `workflow_voice_menu_request`, `webhook_subscription`, `webhook_delivery` |
| Data tables | `data_table`, `data_table_row` |
| Channels (chat apps) | `channel_session`, `channel_message_log`, `channel_contact`, `channel_default_agent`, `contact_group`, `whatsapp_opt_in`, `whatsapp_recipient_backoff`, `whatsapp_qr_connection`, `teams_conversation_route`, `voice_number_agent`, `voice_call_recording`, `voice_dtmf_mapping` |
| Credentials | `provider_credential`, `llm_provider_credential`, `tenant_secret`, `tenant_api_key`, `*_credential` (one per integration: Google, Slack, Telegram, Twilio, Matrix, Teams and others) |
| Governance and audit | `audit_log_entry`, `audit_seal`, `approval_request`, `approval_policy_override`, `guardrail_rule`, `abuse_events`, `ip_blacklist`, `user_blacklist`, `moderation_review_item`, `moderation_restricted_audit`, `privacy_consent_record`, `privacy_consent_policy`, `privacy_purpose_tag`, `privacy_retention_policy` |
| Usage and cost | `llm_call_log`, `llm_daily_usage_rollup`, `llm_spend_month`, `llm_price`, `llm_response_cache`, `roi_budget_alert`, `wallet_ledger`, `mandate_ledger`, `payment_transaction`, `payment_mandate`, `commerce_mandate` |
| Learning and evaluation | `eval_dataset`, `eval_dataset_case`, `eval_run`, `eval_run_case_result`, `agent_eval_gate`, `learning_proposals`, `learning_review_runs`, `rl_episode`, `rl_trajectory_step`, `rl_reward_record`, `policy_arm`, `preference_signals`, `procedures`, `procedure_rule` |
| Skills and tools | `skill`, `skill_version`, `skill_file`, `skill_builtin_override`, `custom_code_tool`, `custom_code_tool_secret`, `openapi_tool`, `openapi_tool_source`, `mcp_server_registration`, `mcp_server_access_grant`, `mcp_tool_exposure`, `mcp_oauth_connection`, `tool_classification` |
| Generated media | `generated_image`, `generated_music`, `elevenlabs_generated_audio`, `sarvam_generated_audio`, `video_generation_job`, `document_generation_job` |
| Browser and devices | `browser_profile`, `browser_recipe`, `desktop_recipe`, `device_command`, `device_screen_state`, `device_notification`, `device_app_usage_event`, `mobile_device`, `mobile_sentinel_policy`, `mobile_sentinel_event` |
| Studio | `studio_app`, `studio_app_user`, `studio_app_connection`, `studio_app_domain`, `studio_project`, `studio_project_member`, `studio_project_version`, `studio_project_comment` |
| Briefs | `brief`, `brief_version`, `brief_binding`, `brief_apply_step`, `brief_trust_event` |
| Other | `scheduled_task`, `scheduled_task_run`, `notification`, `lead`, `lead_activity`, `imported_chat`, `chat_branch_session`, `chat_branch_node`, `conversation_correction`, `simulation_run`, `simulation_session`, `simulation_persona`, `study_plan`, `quick_task_run`, `platform_issue`, `flyway_schema_history` |

## Control plane tables

| Area | Tables |
|---|---|
| Tenants and users | `tenant`, `app_user`, `local_account`, `local_device`, `local_passkey`, `local_recovery_code`, `local_refresh_token`, `local_setup_token`, `impersonation_session`, `user_permission_grant`, `tenant_scim_token`, `tenant_sso_config` |
| Billing | `billing_invoice`, `billing_webhook_event`, `checkout_session`, `commerce_product`, `plan_limits`, `media_model_cost`, `model_pricing_history`, `model_pricing_overrides`, `x402_offer`, `x402_payment` |
| Platform settings | `platform_setting`, `platform_kill_switch`, `platform_alert_settings`, `platform_alert_webhook_event`, `platform_llm_task_config`, `platform_hosted_llm_provider`, `hosted_llm_pool_settings`, `platform_tool_credential`, `platform_prompt_override`, `platform_prompt_override_history`, `feature_flag`, `addon_state`, `experiment_assignment`, `translation_job_settings`, `llm_gateway_content_policy` |
| Community and directory | `agent_directory_entry`, `community_template`, `community_template_rating`, `agent_quality_record`, `agent_quality_record_feedback`, `agent_quality_record_scores`, `ai_content_report`, `moderation_blocklist_entry`, `newsletter_campaign` |
| Demo | `demo_session`, `demo_chat_message`, `demo_channel_link`, `demo_channel_message`, `demo_generated_image`, `demo_generated_music`, `demo_generated_video`, `demo_magic_workflow`, `demo_studio_run`, `demo_studio_workflow`, `demo_whatsapp_phone` |
| Channel links | `channel_account_link`, `atlassian_oauth_client`, `device_pairing_code` |
| Operations | `platform_quota_reset_log`, `platform_tenant_deletion_log` |

## Where to look in code

- Migration files: `core/app/src/main/resources/db/migration/`
- Tenant routing: `core/tenancy/`
- Knowledge and pgvector: `core/knowledge/` (`VectorSchemaInitializer`, `KnowledgeChunkScanner`)
- Entities: each module's `*.java` with `@Entity`, for example `core/agents/.../Agent.java`
- Regenerating the reference: query `information_schema.columns`, `pg_constraint` and `pg_index` on a running database, as the reference was produced.
