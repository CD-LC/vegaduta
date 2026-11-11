# Database reference (generated)

Column-level reference for every table in the `control_plane` schema and the tenant schema template. Generated 2026-10-30 from a running stack (`ghcr.io/cd-lc/core:0.1.12`, Flyway tenant head 674). Read the narrative in [database.md](database.md). Regenerate after a migration changes a table; this file is not hand-edited.

## Control plane (`control_plane` schema)

63 tables.

### `control_plane.addon_state`

| Column | Type | Null | Default |
|---|---|---|---|
| `addon_id` | character varying(64) | no |  |
| `desired` | boolean | no |  |
| `rev` | bigint | no | `1` |
| `desired_at` | timestamp with time zone | no | `now()` |
| `desired_by` | character varying(255) | yes |  |
| `reported_status` | character varying(16) | yes |  |
| `reported_rev` | bigint | yes |  |
| `reported_message` | character varying(500) | yes |  |
| `reported_at` | timestamp with time zone | yes |  |
| `lock_version` | bigint | no | `0` |

- **Primary key:** `PRIMARY KEY (addon_id)`

### `control_plane.agent_directory_entry`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `tenant_id` | character varying(255) | no |  |
| `display_name` | character varying(200) | no |  |
| `description` | text | yes |  |
| `capabilities` | text | yes |  |
| `homepage_url` | character varying(1024) | yes |  |
| `card_snapshot` | text | no |  |
| `manifest_snapshot` | text | yes |  |
| `status` | character varying(16) | no | `'PUBLISHED'::character varying` |
| `published_at` | timestamp with time zone | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (agent_id)`
- **Index:** `idx_agent_directory_tenant — btree (tenant_id)`
- **Index:** `idx_agent_directory_published — btree (published_at DESC) WHERE ((status)::text = 'PUBLISHED'::text)`

### `control_plane.agent_quality_record`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `agent_id` | uuid | no |  |
| `turn_number` | integer | no |  |
| `overall_score` | double precision | no |  |
| `user_message` | text | yes |  |
| `agent_response` | text | yes |  |
| `input_tokens` | bigint | yes |  |
| `output_tokens` | bigint | yes |  |
| `created_at` | timestamp without time zone | no | `CURRENT_TIMESTAMP` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_agent_quality_created_at — btree (created_at DESC)`
- **Index:** `idx_agent_quality_agent_id_created_at — btree (agent_id, created_at DESC)`
- **Index:** `idx_agent_quality_agent_id — btree (agent_id)`

### `control_plane.agent_quality_record_feedback`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_quality_record_id` | uuid | no |  |
| `feedback` | text | yes |  |
| `feedback_key` | character varying(255) | no |  |

- **Primary key:** `PRIMARY KEY (agent_quality_record_id, feedback_key)`
- **Foreign keys:** `FOREIGN KEY (agent_quality_record_id) REFERENCES control_plane.agent_quality_record(id) ON DELETE CASCADE`
- **Index:** `idx_quality_feedback_record_id — btree (agent_quality_record_id)`

### `control_plane.agent_quality_record_scores`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_quality_record_id` | uuid | no |  |
| `score` | double precision | no |  |
| `scores_key` | character varying(255) | no |  |

- **Primary key:** `PRIMARY KEY (agent_quality_record_id, scores_key)`
- **Foreign keys:** `FOREIGN KEY (agent_quality_record_id) REFERENCES control_plane.agent_quality_record(id) ON DELETE CASCADE`
- **Index:** `idx_quality_scores_record_id — btree (agent_quality_record_id)`

### `control_plane.ai_content_report`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | character varying(63) | yes |  |
| `reporter_id` | character varying(255) | yes |  |
| `surface` | character varying(16) | no |  |
| `reason` | character varying(32) | no |  |
| `comment` | character varying(1000) | yes |  |
| `content` | text | no |  |
| `prompt` | text | yes |  |
| `agent_id` | character varying(128) | yes |  |
| `session_id` | character varying(255) | yes |  |
| `message_ref` | character varying(255) | yes |  |
| `on_device` | boolean | no | `false` |
| `app_platform` | character varying(16) | yes |  |
| `app_version` | character varying(32) | yes |  |
| `status` | character varying(16) | no | `'OPEN'::character varying` |
| `review_note` | character varying(2000) | yes |  |
| `reviewed_by` | character varying(255) | yes |  |
| `reviewed_at` | timestamp with time zone | yes |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_ai_content_report_reporter_created — btree (reporter_id, created_at)`
- **Index:** `idx_ai_content_report_status_created — btree (status, created_at DESC)`

### `control_plane.app_user`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | uuid | no |  |
| `keycloak_user_id` | character varying(255) | no |  |
| `email` | character varying(255) | no |  |
| `role` | character varying(32) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `phone_number` | character varying(32) | yes |  |
| `avatar_url` | character varying(512) | yes |  |
| `last_seen_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (keycloak_user_id)`
- **Unique:** `UNIQUE (tenant_id, email)`
- **Foreign keys:** `FOREIGN KEY (tenant_id) REFERENCES control_plane.tenant(id)`
- **Index:** `idx_app_user_tenant_last_seen — btree (tenant_id, last_seen_at)`
- **Index:** `UNIQUE uq_app_user_tenant_email — btree (tenant_id, email)`
- **Index:** `UNIQUE uq_app_user_keycloak_user_id — btree (keycloak_user_id)`
- **Index:** `idx_app_user_tenant_id — btree (tenant_id)`

### `control_plane.atlassian_oauth_client`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `client_id` | character varying(255) | no |  |
| `encrypted_client_secret` | text | yes |  |
| `token_endpoint_auth_method` | character varying(32) | no |  |
| `redirect_uri` | character varying(512) | no |  |
| `registered_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`

### `control_plane.billing_invoice`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `tenant_id` | uuid | no |  |
| `invoice_number` | character varying(32) | no |  |
| `gateway` | character varying(16) | yes |  |
| `gateway_payment_id` | character varying(64) | yes |  |
| `plan` | character varying(16) | yes |  |
| `period` | character varying(8) | yes |  |
| `currency` | character varying(3) | yes |  |
| `amount_minor` | bigint | yes |  |
| `gst_rate_bp` | integer | yes |  |
| `gst_amount_minor` | bigint | yes |  |
| `place_of_supply` | character varying(64) | yes |  |
| `customer_gstin` | character varying(15) | yes |  |
| `customer_name` | text | yes |  |
| `issued_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (invoice_number)`
- **Index:** `UNIQUE billing_invoice_invoice_number_key — btree (invoice_number)`
- **Index:** `idx_billing_invoice_tenant — btree (tenant_id, issued_at DESC)`
- **Index:** `UNIQUE uq_billing_invoice_gateway_payment — btree (gateway_payment_id) WHERE (gateway_payment_id IS NOT NULL)`

### `control_plane.billing_webhook_event`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `gateway` | character varying(32) | no |  |
| `consumer` | character varying(64) | no |  |
| `gateway_event_id` | character varying(160) | no |  |
| `event_type` | character varying(64) | yes |  |
| `payload` | jsonb | yes |  |
| `signature_valid` | boolean | no | `true` |
| `processing_status` | character varying(16) | no | `'RECEIVED'::character varying` |
| `error` | text | yes |  |
| `received_at` | timestamp with time zone | no | `now()` |
| `processed_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_billing_webhook_event_status_received — btree (processing_status, received_at DESC)`
- **Index:** `UNIQUE uq_billing_webhook_event_dedupe — btree (gateway, consumer, gateway_event_id)`

### `control_plane.channel_account_link`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `provider` | character varying(32) | no |  |
| `external_account_id` | character varying(128) | no |  |
| `tenant_schema` | character varying(63) | no |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (provider, external_account_id)`
- **Index:** `UNIQUE uq_channel_account_link_provider_external — btree (provider, external_account_id)`

### `control_plane.checkout_session`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | character varying(63) | no |  |
| `agent_id` | uuid | yes |  |
| `session_id` | character varying(255) | yes |  |
| `channel` | character varying(32) | no | `'agent'::character varying` |
| `customer_phone` | character varying(32) | yes |  |
| `customer_name` | text | yes |  |
| `currency` | character varying(3) | no | `'INR'::character varying` |
| `amount_paise` | bigint | no |  |
| `description` | text | no |  |
| `line_items` | text | yes |  |
| `status` | character varying(32) | no |  |
| `payment_provider` | character varying(16) | no | `'razorpay'::character varying` |
| `gateway_ref_id` | character varying(64) | yes |  |
| `short_url` | text | yes |  |
| `paid_at` | timestamp with time zone | yes |  |
| `payment_id` | character varying(64) | yes |  |
| `review_note` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `expires_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_checkout_session_tenant_created — btree (tenant_id, created_at DESC)`
- **Index:** `UNIQUE uq_checkout_session_gateway_ref — btree (gateway_ref_id) WHERE (gateway_ref_id IS NOT NULL)`

### `control_plane.commerce_product`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `tenant_id` | character varying(255) | no |  |
| `sku` | character varying(128) | yes |  |
| `name` | character varying(200) | no |  |
| `description` | text | yes |  |
| `category` | character varying(64) | yes |  |
| `price_paise` | bigint | no |  |
| `currency` | character varying(3) | no | `'INR'::character varying` |
| `availability` | character varying(16) | no | `'IN_STOCK'::character varying` |
| `image_url` | character varying(1024) | yes |  |
| `product_url` | character varying(1024) | yes |  |
| `published` | boolean | no | `false` |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_commerce_product_tenant — btree (tenant_id, updated_at DESC)`
- **Index:** `idx_commerce_product_feed — btree (tenant_id) WHERE published`

### `control_plane.community_template`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `kind` | character varying(16) | no |  |
| `title` | character varying(200) | no |  |
| `summary` | text | yes |  |
| `category` | character varying(64) | yes |  |
| `tags` | text | yes |  |
| `status` | character varying(16) | no | `'DRAFT'::character varying` |
| `config_json` | text | no |  |
| `source_tenant_id` | character varying(255) | yes |  |
| `submitted_by` | character varying(255) | yes |  |
| `submitted_at` | timestamp with time zone | yes |  |
| `reviewed_by` | character varying(255) | yes |  |
| `reviewed_at` | timestamp with time zone | yes |  |
| `reject_reason` | text | yes |  |
| `import_count` | bigint | no | `0` |
| `version` | integer | no | `1` |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `verified` | boolean | no | `false` |
| `verified_by` | character varying(255) | yes |  |
| `verified_at` | timestamp with time zone | yes |  |
| `rating_count` | bigint | no | `0` |
| `rating_avg` | double precision | no | `0` |
| `ai_review_json` | text | yes |  |
| `ai_reviewed_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_community_template_rating_sort — btree (rating_avg DESC, rating_count DESC) WHERE ((status)::text = 'APPROVED'::text)`
- **Index:** `idx_community_template_source_tenant — btree (source_tenant_id, created_at)`
- **Index:** `idx_community_template_submitter — btree (submitted_by)`
- **Index:** `idx_community_template_pending — btree (submitted_at) WHERE ((status)::text = 'PENDING'::text)`
- **Index:** `idx_community_template_gallery — btree (kind, category, reviewed_at DESC) WHERE ((status)::text = 'APPROVED'::text)`

### `control_plane.community_template_rating`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `template_id` | uuid | no |  |
| `rater_subject` | character varying(255) | no |  |
| `rater_tenant_id` | character varying(255) | yes |  |
| `stars` | integer | no |  |
| `review` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (template_id, rater_subject)`
- **Foreign keys:** `FOREIGN KEY (template_id) REFERENCES control_plane.community_template(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_community_template_rating_rater — btree (template_id, rater_subject)`
- **Index:** `idx_community_template_rating_rater — btree (rater_subject)`
- **Index:** `idx_community_template_rating_template — btree (template_id, created_at DESC)`

### `control_plane.demo_channel_link`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `demo_session_id` | uuid | no |  |
| `channel` | character varying(24) | no |  |
| `state` | character varying(16) | no |  |
| `pairing_code` | character varying(16) | yes |  |
| `external_id` | character varying(128) | yes |  |
| `peer_label` | character varying(64) | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `linked_at` | timestamp with time zone | yes |  |
| `revoked_at` | timestamp with time zone | yes |  |
| `last_message_at` | timestamp with time zone | yes |  |
| `message_count` | integer | no | `0` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (demo_session_id, channel)`
- **Index:** `UNIQUE uq_demo_channel_link_session_channel — btree (demo_session_id, channel)`
- **Index:** `idx_demo_channel_link_session — btree (demo_session_id)`
- **Index:** `idx_demo_channel_link_external — btree (channel, external_id)`
- **Index:** `UNIQUE uq_demo_channel_link_live_external — btree (channel, external_id) WHERE (((state)::text = 'LINKED'::text) AND (external_id IS NOT NULL))`
- **Index:** `UNIQUE uq_demo_channel_link_pairing_code — btree (channel, pairing_code) WHERE (pairing_code IS NOT NULL)`

### `control_plane.demo_channel_message`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `demo_session_id` | uuid | no |  |
| `channel` | character varying(24) | no |  |
| `direction` | character varying(8) | no |  |
| `body` | text | no |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_demo_channel_message_session_time — btree (demo_session_id, created_at)`

### `control_plane.demo_chat_message`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `session_id` | uuid | no |  |
| `direction` | character varying(3) | no |  |
| `simulated` | boolean | no | `false` |
| `content` | text | no |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_demo_chat_message_session — btree (session_id, created_at)`

### `control_plane.demo_generated_image`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `session_id` | uuid | no |  |
| `object_key` | character varying(512) | no |  |
| `content_type` | character varying(128) | no |  |
| `prompt` | text | no |  |
| `model` | character varying(128) | no |  |
| `created_ip` | character varying(45) | yes |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_demo_generated_image_session — btree (session_id)`
- **Index:** `idx_demo_generated_image_created_at — btree (created_at)`

### `control_plane.demo_generated_music`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `session_id` | uuid | no |  |
| `object_key` | character varying(512) | no |  |
| `content_type` | character varying(128) | no |  |
| `prompt` | text | no |  |
| `model` | character varying(128) | no |  |
| `duration_seconds` | integer | no |  |
| `created_ip` | character varying(45) | yes |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_demo_generated_music_created_at — btree (created_at)`
- **Index:** `idx_demo_generated_music_session — btree (session_id)`

### `control_plane.demo_generated_video`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `session_id` | uuid | no |  |
| `object_key` | character varying(512) | no |  |
| `content_type` | character varying(128) | no |  |
| `prompt` | text | no |  |
| `model` | character varying(128) | no |  |
| `duration_seconds` | integer | no |  |
| `created_ip` | character varying(45) | yes |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_demo_generated_video_session — btree (session_id)`
- **Index:** `idx_demo_generated_video_created_at — btree (created_at)`

### `control_plane.demo_magic_workflow`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `session_id` | uuid | no |  |
| `share_id` | character varying(64) | yes |  |
| `title` | character varying(120) | no |  |
| `intent` | character varying(24) | no |  |
| `agent_id` | uuid | yes |  |
| `plan_json` | text | no |  |
| `form_spec_json` | text | no |  |
| `run_cap` | integer | no | `3` |
| `runs_used` | integer | no | `0` |
| `revoked` | boolean | no | `false` |
| `created_at` | timestamp with time zone | no | `now()` |
| `expires_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (session_id) REFERENCES control_plane.demo_session(id) ON DELETE CASCADE`
- **Index:** `ix_demo_magic_workflow_session — btree (session_id)`
- **Index:** `UNIQUE ux_demo_magic_workflow_share_id — btree (share_id) WHERE (share_id IS NOT NULL)`

### `control_plane.demo_session`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `token_hash` | character varying(64) | no |  |
| `tenant_id` | uuid | no |  |
| `tenant_schema` | character varying(63) | no |  |
| `agent_id` | uuid | yes |  |
| `email` | character varying(320) | yes |  |
| `created_ip` | character varying(45) | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `expires_at` | timestamp with time zone | no |  |
| `reminder_sent_at` | timestamp with time zone | yes |  |
| `expired_at` | timestamp with time zone | yes |  |
| `converted_tenant_id` | uuid | yes |  |
| `converted_at` | timestamp with time zone | yes |  |
| `message_count` | integer | no | `0` |
| `image_count` | integer | no | `0` |
| `persona` | character varying(32) | yes |  |
| `transcript_emails_sent` | integer | no | `0` |
| `share_id` | character varying(64) | yes |  |
| `share_created_at` | timestamp with time zone | yes |  |
| `share_revoked` | boolean | no | `false` |
| `share_reported_count` | integer | no | `0` |
| `share_view_count` | integer | no | `0` |
| `share_viewer_count` | integer | no | `0` |
| `lab_started_at` | timestamp with time zone | yes |  |
| `lab_completed_at` | timestamp with time zone | yes |  |
| `last_activity_at` | timestamp with time zone | no |  |
| `video_count` | integer | no | `0` |
| `music_count` | integer | no | `0` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (token_hash)`
- **Index:** `idx_demo_session_converted_at — btree (converted_at) WHERE (converted_at IS NOT NULL)`
- **Index:** `idx_demo_session_created_ip_live — btree (created_ip) WHERE (expired_at IS NULL)`
- **Index:** `UNIQUE demo_session_token_hash_key — btree (token_hash)`
- **Index:** `idx_demo_session_expires_at — btree (expires_at)`
- **Index:** `idx_demo_session_tenant_id — btree (tenant_id)`
- **Index:** `idx_demo_session_created_at — btree (created_at DESC)`
- **Index:** `UNIQUE idx_demo_session_share_id — btree (share_id) WHERE (share_id IS NOT NULL)`

### `control_plane.demo_studio_run`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `workflow_id` | uuid | no |  |
| `session_id` | uuid | no |  |
| `status` | character varying(24) | no |  |
| `input_text` | text | yes |  |
| `output_text` | text | yes |  |
| `trace_json` | text | no | `'[]'::text` |
| `started_at` | timestamp with time zone | no | `now()` |
| `finished_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (session_id) REFERENCES control_plane.demo_session(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (workflow_id) REFERENCES control_plane.demo_studio_workflow(id) ON DELETE CASCADE`
- **Index:** `idx_demo_studio_run_workflow — btree (workflow_id, started_at DESC)`

### `control_plane.demo_studio_workflow`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `session_id` | uuid | no |  |
| `created_ip` | character varying(45) | yes |  |
| `name` | character varying(120) | no |  |
| `definition_json` | text | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (session_id) REFERENCES control_plane.demo_session(id) ON DELETE CASCADE`
- **Index:** `idx_demo_studio_workflow_ip — btree (created_ip)`
- **Index:** `idx_demo_studio_workflow_session — btree (session_id)`

### `control_plane.demo_whatsapp_phone`

| Column | Type | Null | Default |
|---|---|---|---|
| `phone_hash` | character varying(128) | no |  |
| `turn_count` | integer | no | `0` |
| `pair_count` | integer | no | `0` |
| `first_seen` | timestamp with time zone | no |  |
| `last_seen` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (phone_hash)`

### `control_plane.device_pairing_code`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `code` | character varying(6) | no |  |
| `tenant_schema` | character varying(63) | no |  |
| `created_by` | character varying(190) | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `expires_at` | timestamp with time zone | no |  |
| `consumed` | boolean | no | `false` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_device_pairing_code_unconsumed — btree (code) WHERE (NOT consumed)`

### `control_plane.experiment_assignment`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `flag_key` | character varying(120) | no |  |
| `tenant_id` | character varying(64) | no |  |
| `variant` | character varying(32) | no |  |
| `assigned_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (flag_key, tenant_id)`
- **Index:** `idx_experiment_assignment_flag_key — btree (flag_key)`
- **Index:** `UNIQUE uq_experiment_assignment_flag_tenant — btree (flag_key, tenant_id)`

### `control_plane.feature_flag`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `flag_key` | character varying(120) | no |  |
| `description` | character varying(500) | yes |  |
| `enabled` | boolean | no | `false` |
| `rollout_percentage` | integer | no | `0` |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `updated_by` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (flag_key)`
- **Index:** `UNIQUE feature_flag_flag_key_key — btree (flag_key)`

### `control_plane.hosted_llm_pool_settings`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no |  |
| `enabled` | boolean | no | `true` |
| `updated_at` | timestamp with time zone | no |  |
| `updated_by` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `control_plane.impersonation_session`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `admin_keycloak_id` | character varying(255) | no |  |
| `admin_email` | character varying(255) | yes |  |
| `tenant_id` | uuid | no |  |
| `tenant_schema` | character varying(63) | no |  |
| `target_keycloak_id` | character varying(255) | no |  |
| `target_email` | character varying(255) | yes |  |
| `target_roles` | text | no | `''::text` |
| `reason` | text | no |  |
| `grant_hash` | character varying(64) | no |  |
| `started_at` | timestamp with time zone | no |  |
| `expires_at` | timestamp with time zone | no |  |
| `ended_at` | timestamp with time zone | yes |  |
| `request_count` | integer | no | `0` |
| `last_request_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_impersonation_session_admin_active — btree (admin_keycloak_id, expires_at) WHERE (ended_at IS NULL)`
- **Index:** `UNIQUE uq_impersonation_session_grant_hash — btree (grant_hash)`
- **Index:** `idx_impersonation_session_started — btree (started_at DESC)`

### `control_plane.llm_gateway_content_policy`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `tenant_id` | character varying(64) | no |  |
| `pii_redaction_enabled` | boolean | no | `false` |
| `pii_redact_email` | boolean | no | `true` |
| `pii_redact_phone` | boolean | no | `true` |
| `pii_redact_government_id` | boolean | no | `true` |
| `pii_redact_credit_card` | boolean | no | `true` |
| `injection_screening_enabled` | boolean | no | `false` |
| `injection_screening_action` | character varying(16) | no | `'FLAG'::character varying` |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (tenant_id)`
- **Index:** `UNIQUE llm_gateway_content_policy_tenant_id_key — btree (tenant_id)`

### `control_plane.local_account`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `email` | character varying(320) | no |  |
| `email_lower` | character varying(320) | no |  |
| `first_name` | character varying(255) | yes |  |
| `last_name` | character varying(255) | yes |  |
| `password_hash` | character varying(255) | yes |  |
| `must_change_password` | boolean | no | `false` |
| `enabled` | boolean | no | `true` |
| `setup_pending` | boolean | no | `false` |
| `tenant_id` | character varying(63) | yes |  |
| `roles` | character varying(1000) | no | `''::character varying` |
| `attributes` | text | no | `'{}'::text` |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `password_changed_at` | timestamp with time zone | yes |  |
| `last_login_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (email_lower)`
- **Index:** `UNIQUE uq_local_account_email — btree (email_lower)`
- **Index:** `idx_local_account_tenant — btree (tenant_id)`

### `control_plane.local_device`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `account_id` | uuid | no |  |
| `label` | character varying(100) | no | `''::character varying` |
| `pin_hash` | character varying(255) | no |  |
| `failed_attempts` | integer | no | `0` |
| `created_at` | timestamp with time zone | no |  |
| `last_used_at` | timestamp with time zone | yes |  |
| `revoked_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (account_id) REFERENCES control_plane.local_account(id) ON DELETE CASCADE`
- **Index:** `idx_local_device_account — btree (account_id)`

### `control_plane.local_passkey`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `account_id` | uuid | no |  |
| `credential_id` | character varying(1400) | no |  |
| `attested_credential_data` | bytea | no |  |
| `sign_count` | bigint | no | `0` |
| `uv_initialized` | boolean | no | `false` |
| `backup_eligible` | boolean | no | `false` |
| `backup_state` | boolean | no | `false` |
| `transports` | character varying(255) | no | `''::character varying` |
| `rp_id` | character varying(255) | no |  |
| `label` | character varying(100) | no | `''::character varying` |
| `created_at` | timestamp with time zone | no |  |
| `last_used_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (credential_id)`
- **Foreign keys:** `FOREIGN KEY (account_id) REFERENCES control_plane.local_account(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_local_passkey_credential — btree (credential_id)`
- **Index:** `idx_local_passkey_account — btree (account_id)`

### `control_plane.local_recovery_code`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `account_id` | uuid | no |  |
| `code_hash` | character(64) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `used_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (code_hash)`
- **Foreign keys:** `FOREIGN KEY (account_id) REFERENCES control_plane.local_account(id) ON DELETE CASCADE`
- **Index:** `idx_local_recovery_code_account — btree (account_id)`
- **Index:** `UNIQUE uq_local_recovery_code_hash — btree (code_hash)`

### `control_plane.local_refresh_token`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `account_id` | uuid | no |  |
| `family_id` | uuid | no |  |
| `token_hash` | character(64) | no |  |
| `auth_method` | character varying(32) | no | `'pwd'::character varying` |
| `auth_time` | timestamp with time zone | no |  |
| `issued_at` | timestamp with time zone | no |  |
| `expires_at` | timestamp with time zone | no |  |
| `family_expires_at` | timestamp with time zone | no |  |
| `used_at` | timestamp with time zone | yes |  |
| `revoked_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (token_hash)`
- **Foreign keys:** `FOREIGN KEY (account_id) REFERENCES control_plane.local_account(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_local_refresh_token_hash — btree (token_hash)`
- **Index:** `idx_local_refresh_token_family — btree (family_id)`
- **Index:** `idx_local_refresh_token_account — btree (account_id)`
- **Index:** `idx_local_refresh_token_expiry — btree (expires_at)`

### `control_plane.local_setup_token`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `account_id` | uuid | no |  |
| `token_hash` | character(64) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `expires_at` | timestamp with time zone | no |  |
| `consumed_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (token_hash)`
- **Foreign keys:** `FOREIGN KEY (account_id) REFERENCES control_plane.local_account(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_local_setup_token_hash — btree (token_hash)`
- **Index:** `idx_local_setup_token_account — btree (account_id)`

### `control_plane.media_model_cost`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('control_plane.media_model_cost_id_seq'::regclass)` |
| `modality` | character varying(16) | no |  |
| `provider` | character varying(64) | no |  |
| `model_id` | character varying(255) | no |  |
| `unit` | character varying(32) | no |  |
| `unit_cost_usd` | numeric | no |  |
| `typical_call_cost_usd` | numeric | no |  |
| `quality_rank` | integer | no | `0` |
| `enabled` | boolean | no | `true` |
| `source_note` | text | yes |  |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (modality, provider, model_id)`
- **Index:** `UNIQUE uq_media_model_cost — btree (modality, provider, model_id)`

### `control_plane.model_pricing_history`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('control_plane.model_pricing_history_id_seq'::regclass)` |
| `provider_name` | character varying(64) | no |  |
| `model_id` | character varying(255) | no |  |
| `input_price_per_million` | numeric | yes |  |
| `output_price_per_million` | numeric | yes |  |
| `cache_write_price_per_million` | numeric | yes |  |
| `cache_read_price_per_million` | numeric | yes |  |
| `pricing_source` | character varying(32) | no |  |
| `pricing_confidence` | character varying(16) | no |  |
| `effective_from` | timestamp with time zone | no |  |
| `effective_until` | timestamp with time zone | yes |  |
| `change_reason` | text | yes |  |
| `changed_by` | character varying(128) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_pricing_history_model — btree (provider_name, model_id, effective_from DESC)`
- **Index:** `idx_pricing_history_changed_by — btree (changed_by)`
- **Index:** `idx_pricing_history_effective — btree (effective_from, effective_until)`

### `control_plane.model_pricing_overrides`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('control_plane.model_pricing_overrides_id_seq'::regclass)` |
| `provider_name` | character varying(64) | no |  |
| `model_id` | character varying(255) | no |  |
| `input_price_per_million` | numeric | no |  |
| `output_price_per_million` | numeric | no |  |
| `cache_write_price_per_million` | numeric | yes |  |
| `cache_read_price_per_million` | numeric | yes |  |
| `override_reason` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `created_by` | character varying(128) | yes |  |
| `expires_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (provider_name, model_id)`
- **Index:** `idx_pricing_overrides_active — btree (provider_name, model_id, created_at)`
- **Index:** `UNIQUE uq_pricing_overrides — btree (provider_name, model_id)`

### `control_plane.moderation_blocklist_entry`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `entry_type` | character varying(16) | no |  |
| `value` | character varying(512) | no |  |
| `reason` | text | yes |  |
| `active` | boolean | no | `true` |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `UNIQUE moderation_blocklist_entry_uk — btree (entry_type, value)`

### `control_plane.newsletter_campaign`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `subject` | character varying(500) | no |  |
| `sent_count` | integer | no |  |
| `created_at` | timestamp with time zone | no |  |
| `created_by` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_newsletter_campaign_created_at — btree (created_at DESC)`

### `control_plane.plan_limits`

| Column | Type | Null | Default |
|---|---|---|---|
| `plan` | character varying(32) | no |  |
| `max_users` | integer | no |  |
| `max_agents` | integer | no |  |
| `max_workflows` | integer | no |  |
| `max_kb_collections` | integer | no |  |
| `byok_allowed` | boolean | no | `false` |
| `whatsapp_allowed` | boolean | no | `false` |
| `white_label` | boolean | no | `false` |
| `live_vision_allowed` | boolean | no | `false` |
| `external_channels_allowed` | boolean | no | `false` |
| `developer_api_allowed` | boolean | no | `false` |
| `sso_allowed` | boolean | no | `false` |
| `scim_allowed` | boolean | no | `false` |
| `workflow_export_allowed` | boolean | no | `false` |
| `automation_allowed` | boolean | no | `false` |

- **Primary key:** `PRIMARY KEY (plan)`

### `control_plane.platform_alert_settings`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no |  |
| `fallback_failure_threshold` | integer | no | `10` |
| `updated_by` | character varying(255) | yes |  |
| `updated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `control_plane.platform_alert_webhook_event`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `fingerprint` | character varying(64) | no |  |
| `alert_name` | character varying(255) | yes |  |
| `severity` | character varying(32) | yes |  |
| `status` | character varying(16) | no |  |
| `summary` | character varying(500) | yes |  |
| `description` | text | yes |  |
| `labels_json` | text | yes |  |
| `annotations_json` | text | yes |  |
| `generator_url` | character varying(1000) | yes |  |
| `starts_at` | timestamp with time zone | yes |  |
| `ends_at` | timestamp with time zone | yes |  |
| `occurrence_count` | integer | no | `1` |
| `first_seen_at` | timestamp with time zone | no |  |
| `last_seen_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (fingerprint)`
- **Index:** `idx_platform_alert_webhook_event_last_seen — btree (last_seen_at DESC)`
- **Index:** `UNIQUE platform_alert_webhook_event_fingerprint_key — btree (fingerprint)`
- **Index:** `idx_platform_alert_webhook_event_status — btree (status)`

### `control_plane.platform_hosted_llm_provider`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('control_plane.platform_hosted_llm_provider_id_seq'::regclass)` |
| `label` | character varying(128) | no |  |
| `base_url` | character varying(255) | no |  |
| `model` | character varying(128) | no |  |
| `encrypted_api_key` | text | yes |  |
| `enabled` | boolean | no | `true` |
| `priority` | integer | no | `100` |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_validated_at` | timestamp with time zone | yes |  |
| `region` | character varying(64) | yes |  |
| `completions_path` | character varying(255) | yes |  |
| `provider_name` | character varying(64) | yes |  |
| `model_auto_selected` | boolean | no | `false` |
| `auth_type` | character varying(32) | no | `'API_KEY'::character varying` |
| `vertex_project_id` | character varying(128) | yes |  |
| `vertex_location` | character varying(64) | yes | `'us-central1'::character varying` |
| `zero_cost` | boolean | no | `false` |
| `tool_call_grade` | character varying(16) | yes |  |
| `tool_router_mode` | character varying(16) | yes |  |
| `tool_router_enabled` | boolean | yes |  |
| `parameter_billions` | double precision | yes |  |
| `small_model` | boolean | yes |  |
| `reasoning_effort` | character varying(16) | yes |  |
| `keep_alive` | character varying(16) | yes |  |
| `num_batch` | integer | yes |  |
| `response_timeout_ms` | bigint | yes |  |
| `tool_deferral` | boolean | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `control_plane.platform_kill_switch`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no |  |
| `stopped` | boolean | no | `false` |
| `reason` | character varying(500) | yes |  |
| `stopped_at` | timestamp with time zone | yes |  |
| `updated_by` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `control_plane.platform_llm_task_config`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('control_plane.platform_llm_task_config_id_seq'::regclass)` |
| `task` | character varying(32) | no |  |
| `provider_name` | character varying(32) | no |  |
| `model` | character varying(128) | no |  |
| `encrypted_api_key` | text | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_validated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (task)`
- **Index:** `UNIQUE uq_platform_llm_task_config_task — btree (task)`

### `control_plane.platform_prompt_override`

| Column | Type | Null | Default |
|---|---|---|---|
| `scope` | character varying(16) | no | `'platform'::character varying` |
| `scope_id` | character varying(64) | no | `''::character varying` |
| `prompt_key` | character varying(128) | no |  |
| `locale` | character varying(16) | no | `'en'::character varying` |
| `prompt_text` | text | yes |  |
| `enabled` | boolean | no | `true` |
| `version` | integer | no |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (scope, scope_id, prompt_key, locale)`

### `control_plane.platform_prompt_override_history`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('control_plane.platform_prompt_override_history_id_seq'::regclass)` |
| `scope` | character varying(16) | no | `'platform'::character varying` |
| `scope_id` | character varying(64) | no | `''::character varying` |
| `prompt_key` | character varying(128) | no |  |
| `locale` | character varying(16) | no | `'en'::character varying` |
| `version` | integer | no |  |
| `action` | character varying(16) | no |  |
| `prompt_text` | text | yes |  |
| `enabled` | boolean | no |  |
| `previous_text` | text | yes |  |
| `previous_enabled` | boolean | no | `true` |
| `reason` | character varying(500) | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (scope, scope_id, prompt_key, locale, version)`
- **Index:** `idx_platform_prompt_override_history_lookup — btree (scope, scope_id, prompt_key, locale, created_at DESC)`
- **Index:** `UNIQUE platform_prompt_override_history_version_uq — btree (scope, scope_id, prompt_key, locale, version)`

### `control_plane.platform_quota_reset_log`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `job_name` | character varying(80) | no |  |
| `reset_date` | date | no |  |
| `executed_at` | timestamp with time zone | no |  |
| `executed_by` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (job_name, reset_date)`
- **Index:** `idx_platform_quota_reset_log_job_date — btree (job_name, reset_date DESC)`
- **Index:** `UNIQUE uq_platform_quota_reset_log_job_date — btree (job_name, reset_date)`

### `control_plane.platform_setting`

| Column | Type | Null | Default |
|---|---|---|---|
| `key` | character varying(120) | no |  |
| `value` | text | no |  |
| `description` | character varying(500) | yes |  |
| `updated_by` | character varying(255) | yes |  |
| `updated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (key)`

### `control_plane.platform_tenant_deletion_log`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | uuid | no |  |
| `tenant_name` | character varying(255) | no |  |
| `actor_id` | character varying(255) | yes |  |
| `status` | character varying(16) | no |  |
| `initiated_at` | timestamp with time zone | no |  |
| `completed_at` | timestamp with time zone | yes |  |
| `failure_detail` | text | yes |  |
| `steps_completed` | text | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_platform_tenant_deletion_log_tenant_id — btree (tenant_id)`

### `control_plane.platform_tool_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `tool_key` | character varying(32) | no |  |
| `encrypted_api_key` | text | no |  |
| `extra_config` | character varying(255) | yes |  |
| `enabled` | boolean | no | `true` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `updated_by` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (tool_key)`

### `control_plane.provider_model_catalog`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('control_plane.provider_model_catalog_id_seq'::regclass)` |
| `provider_name` | character varying(64) | no |  |
| `model_id` | character varying(255) | no |  |
| `display_name` | character varying(255) | yes |  |
| `context_window` | integer | yes |  |
| `updated_at` | timestamp with time zone | no |  |
| `supports_image_input` | boolean | no | `false` |
| `supports_audio_input` | boolean | no | `false` |
| `supports_video_input` | boolean | no | `false` |
| `capability_summary` | text | yes |  |
| `input_price_per_million` | numeric | yes |  |
| `output_price_per_million` | numeric | yes |  |
| `cache_write_price_per_million` | numeric | yes |  |
| `cache_read_price_per_million` | numeric | yes |  |
| `pricing_source` | character varying(32) | no | `'static_fallback'::character varying` |
| `pricing_confidence` | character varying(16) | no | `'unknown'::character varying` |
| `pricing_fetched_at` | timestamp with time zone | yes |  |
| `pricing_metadata` | jsonb | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (provider_name, model_id)`
- **Index:** `idx_provider_model_catalog_pricing — btree (pricing_source, pricing_confidence)`
- **Index:** `idx_provider_model_catalog_provider — btree (provider_name)`
- **Index:** `UNIQUE uq_provider_model_catalog — btree (provider_name, model_id)`

### `control_plane.tenant`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(255) | no |  |
| `plan` | character varying(32) | no |  |
| `schema_name` | character varying(63) | no |  |
| `status` | character varying(32) | no |  |
| `keycloak_realm` | character varying(255) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `payment_gateway` | character varying(32) | yes |  |
| `gateway_customer_id` | character varying(64) | yes |  |
| `gateway_subscription_id` | character varying(64) | yes |  |
| `subscription_status` | character varying(32) | yes |  |
| `trial_ends_at` | timestamp with time zone | yes |  |
| `current_period_end` | timestamp with time zone | yes |  |
| `account_type` | character varying(16) | no |  |
| `experience_mode` | character varying(16) | no |  |
| `pending_plan` | character varying(32) | yes |  |
| `pending_gateway_subscription_id` | character varying(64) | yes |  |
| `emergency_stopped` | boolean | no | `false` |
| `emergency_stop_reason` | character varying(500) | yes |  |
| `emergency_stopped_at` | timestamp with time zone | yes |  |
| `data_residency_region` | character varying(32) | yes |  |
| `erased_at` | timestamp with time zone | yes |  |
| `erased_by` | character varying(255) | yes |  |
| `use_platform_llm` | boolean | no | `false` |
| `comp_plan_expires_at` | timestamp with time zone | yes |  |
| `comp_plan_granted_at` | timestamp with time zone | yes |  |
| `comp_plan_reason` | character varying(255) | yes |  |
| `news_opt_in` | boolean | no | `false` |
| `is_demo` | boolean | no | `false` |
| `event_trial_claimed_at` | timestamp with time zone | yes |  |
| `event_trial_expires_at` | timestamp with time zone | yes |  |
| `event_trial_trigger` | character varying(64) | yes |  |
| `referral_code` | character varying(16) | yes |  |
| `referred_by_tenant_id` | uuid | yes |  |
| `referral_redeemed_at` | timestamp with time zone | yes |  |
| `gstin` | character varying(15) | yes |  |
| `billing_name` | text | yes |  |
| `billing_address` | text | yes |  |
| `past_due_since` | timestamp with time zone | yes |  |
| `cancel_scheduled` | boolean | no | `false` |
| `brand_display_name` | character varying(64) | yes |  |
| `brand_logo_url` | character varying(512) | yes |  |
| `ai_model_preferences` | text | yes |  |
| `plan_before_comp_grant` | character varying(32) | yes |  |
| `llm_source_explicit` | boolean | no | `false` |
| `version` | bigint | no | `0` |
| `pending_upgrade_started_at` | timestamp with time zone | yes |  |
| `comp_plan_reminder_stage` | integer | no | `0` |
| `spend_cap_usd` | numeric | yes |  |
| `spend_cap_alert_stage` | smallint | no | `0` |
| `spend_cap_alert_month` | date | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (name)`
- **Unique:** `UNIQUE (referral_code)`
- **Unique:** `UNIQUE (schema_name)`
- **Index:** `UNIQUE uq_tenant_referral_code — btree (referral_code)`
- **Index:** `idx_tenant_is_demo — btree (is_demo) WHERE is_demo`
- **Index:** `UNIQUE uq_tenant_name — btree (name)`
- **Index:** `idx_tenant_referred_by_tenant_id — btree (referred_by_tenant_id) WHERE (referred_by_tenant_id IS NOT NULL)`
- **Index:** `UNIQUE uq_tenant_schema_name — btree (schema_name)`

### `control_plane.tenant_scim_token`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `tenant_schema` | character varying(64) | no |  |
| `token_hash` | character varying(64) | no |  |
| `label` | character varying(255) | no |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `last_used_at` | timestamp with time zone | yes |  |
| `revoked_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (token_hash)`
- **Index:** `UNIQUE tenant_scim_token_token_hash_key — btree (token_hash)`
- **Index:** `idx_tenant_scim_token_schema — btree (tenant_schema)`

### `control_plane.tenant_sso_config`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `tenant_schema` | character varying(64) | no |  |
| `provider_type` | character varying(8) | no |  |
| `alias` | character varying(128) | no |  |
| `display_name` | character varying(255) | no |  |
| `enabled` | boolean | no | `true` |
| `oidc_discovery_url` | character varying(2048) | yes |  |
| `oidc_authorization_url` | character varying(2048) | yes |  |
| `oidc_token_url` | character varying(2048) | yes |  |
| `oidc_userinfo_url` | character varying(2048) | yes |  |
| `oidc_jwks_url` | character varying(2048) | yes |  |
| `oidc_issuer` | character varying(2048) | yes |  |
| `oidc_client_id` | character varying(512) | yes |  |
| `client_secret_encrypted` | text | yes |  |
| `saml_entity_id` | character varying(2048) | yes |  |
| `saml_sso_url` | character varying(2048) | yes |  |
| `saml_x509_cert` | text | yes |  |
| `saml_name_id_format` | character varying(128) | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `last_synced_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (alias)`
- **Unique:** `UNIQUE (tenant_schema)`
- **Index:** `UNIQUE tenant_sso_config_alias_key — btree (alias)`
- **Index:** `UNIQUE tenant_sso_config_tenant_schema_key — btree (tenant_schema)`

### `control_plane.translation_job_settings`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no |  |
| `cron_schedule_display` | character varying(64) | no |  |
| `provider_name` | character varying(32) | no |  |
| `model` | character varying(128) | no |  |
| `workflow_path_hint` | character varying(255) | no |  |
| `notes` | character varying(1000) | yes |  |
| `updated_at` | timestamp with time zone | no |  |
| `updated_by` | character varying(255) | yes |  |
| `last_triggered_at` | timestamp with time zone | yes |  |
| `last_triggered_by` | character varying(255) | yes |  |
| `last_trigger_status` | character varying(32) | yes |  |
| `last_trigger_message` | character varying(1000) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `control_plane.user_permission_grant`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | character varying(255) | no |  |
| `keycloak_user_id` | character varying(255) | no |  |
| `permission` | character varying(64) | no |  |
| `granted_by` | character varying(255) | no |  |
| `granted_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (keycloak_user_id, permission)`
- **Index:** `UNIQUE uq_user_permission_grant — btree (keycloak_user_id, permission)`
- **Index:** `idx_user_permission_grant_tenant_user — btree (tenant_id, keycloak_user_id)`

### `control_plane.x402_offer`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `slug` | character varying(128) | no |  |
| `tenant_id` | character varying(255) | no |  |
| `agent_id` | uuid | no |  |
| `wallet_id` | uuid | no |  |
| `title` | character varying(200) | no |  |
| `description` | text | yes |  |
| `price_minor` | bigint | no |  |
| `currency` | character varying(3) | no | `'INR'::bpchar` |
| `evm_price_atomic` | bigint | yes |  |
| `evm_asset` | character varying(64) | yes |  |
| `evm_network` | character varying(32) | yes |  |
| `evm_asset_name` | character varying(64) | yes |  |
| `evm_asset_version` | character varying(16) | yes |  |
| `pay_to` | character varying(256) | yes |  |
| `resource_payload` | text | yes |  |
| `status` | character varying(16) | no | `'ACTIVE'::character varying` |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (slug)`
- **Index:** `idx_x402_offer_tenant — btree (tenant_id, agent_id)`
- **Index:** `idx_x402_offer_slug — btree (slug) WHERE ((status)::text = 'ACTIVE'::text)`
- **Index:** `UNIQUE x402_offer_slug_key — btree (slug)`

### `control_plane.x402_payment`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `offer_id` | uuid | no |  |
| `payer_tenant_id` | character varying(255) | yes |  |
| `payer_ref` | character varying(256) | no |  |
| `amount_atomic` | bigint | no |  |
| `scheme` | character varying(32) | no |  |
| `network` | character varying(32) | no |  |
| `nonce` | character varying(160) | no |  |
| `status` | character varying(16) | no |  |
| `buyer_ledger_ref` | bigint | yes |  |
| `seller_ledger_ref` | bigint | yes |  |
| `reject_reason` | character varying(128) | yes |  |
| `payload` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `settled_at` | timestamp with time zone | yes |  |
| `settlement_ref` | character varying(160) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (nonce)`
- **Foreign keys:** `FOREIGN KEY (offer_id) REFERENCES control_plane.x402_offer(id)`
- **Index:** `UNIQUE x402_payment_nonce_key — btree (nonce)`
- **Index:** `idx_x402_payment_offer — btree (offer_id, id DESC)`

## Tenant schema (`public` template; each tenant gets the same set)

227 tables.

### `public.abuse_events`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('abuse_events_id_seq'::regclass)` |
| `tenant_id` | uuid | no |  |
| `event_type` | character varying(50) | no |  |
| `severity` | character varying(20) | no |  |
| `client_ip` | inet | no |  |
| `user_id` | uuid | yes |  |
| `user_agent` | text | yes |  |
| `tls_ja3_fingerprint` | character varying(100) | yes |  |
| `reason` | text | yes |  |
| `decision` | character varying(20) | no |  |
| `challenge_nonce` | character varying(255) | yes |  |
| `created_at` | timestamp without time zone | yes | `CURRENT_TIMESTAMP` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_abuse_events_tenant_user_created — btree (tenant_id, user_id, created_at DESC)`
- **Index:** `idx_abuse_events_severity — btree (severity)`
- **Index:** `idx_abuse_events_event_type — btree (event_type)`
- **Index:** `idx_abuse_events_tenant_ip_created — btree (tenant_id, client_ip, created_at DESC)`

### `public.agent`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(255) | no |  |
| `system_prompt` | text | no |  |
| `provider_name` | character varying(32) | no |  |
| `model` | character varying(128) | no |  |
| `max_tokens` | integer | no |  |
| `temperature` | double precision | no |  |
| `knowledge_base_enabled` | boolean | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `experience_mode` | character varying(16) | yes |  |
| `top_p` | double precision | no | `1.0` |
| `reasoning_depth` | character varying(16) | no | `'FAST'::character varying` |
| `sarvam_enabled` | boolean | no | `false` |
| `tuning_mode` | character varying(16) | no | `'MANUAL'::character varying` |
| `fallback_provider_name` | character varying(32) | yes |  |
| `fallback_model` | character varying(128) | yes |  |
| `autonomy_tier` | character varying(32) | no | `'SEMI_AUTONOMOUS'::character varying` |
| `share_memory_with_tenant` | boolean | no | `false` |
| `fallback_triggered_count` | bigint | no | `0` |
| `fallback_failed_count` | bigint | no | `0` |
| `last_fallback_at` | timestamp with time zone | yes |  |
| `response_caching_enabled` | boolean | no | `false` |
| `e2b_enabled` | boolean | no | `false` |
| `cost_routing_enabled` | boolean | no | `false` |
| `economy_provider_name` | character varying(32) | yes |  |
| `economy_model` | character varying(128) | yes |  |
| `code_workspace_enabled` | boolean | no | `false` |
| `semantic_response_caching_enabled` | boolean | no | `false` |
| `voice_model_provider` | character varying(32) | yes |  |
| `voice_model` | character varying(128) | yes |  |
| `video_model_provider` | character varying(32) | yes |  |
| `video_model` | character varying(128) | yes |  |
| `text_model_provider` | character varying(32) | yes |  |
| `text_model` | character varying(128) | yes |  |
| `created_by` | character varying(255) | yes |  |
| `input_schema_json` | text | yes |  |
| `output_schema_json` | text | yes |  |
| `schema_version` | integer | no | `0` |
| `biometric_consent_enabled` | boolean | no | `false` |
| `edge_routing_policy` | character varying(16) | yes |  |
| `tts_provider` | character varying(32) | yes |  |
| `tts_model` | character varying(128) | yes |  |
| `knowledge_write_enabled` | boolean | no | `false` |
| `result_schema_json` | text | yes |  |
| `xai_server_tools_enabled` | boolean | no | `false` |
| `experiment_clone_of` | uuid | yes |  |
| `top_k` | integer | yes |  |
| `stop_sequences` | text | no | `'[]'::text` |
| `frequency_penalty` | double precision | yes |  |
| `presence_penalty` | double precision | yes |  |
| `seed` | integer | yes |  |
| `cost_routing_jev_threshold` | double precision | yes |  |
| `version` | bigint | no | `0` |
| `updated_by` | character varying(255) | yes |  |
| `run_budget_usd` | numeric | yes |  |
| `max_tool_steps` | integer | yes |  |
| `turn_timeout_seconds` | integer | yes |  |
| `tool_timeout_seconds` | integer | yes |  |
| `tool_output_max_chars` | integer | yes |  |
| `retrieval_top_k` | integer | yes |  |
| `retrieval_min_score` | double precision | yes |  |
| `continuation_passes` | integer | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (experiment_clone_of) REFERENCES agent(id)`
- **Index:** `idx_agent_experiment_clone_of — btree (experiment_clone_of) WHERE (experiment_clone_of IS NOT NULL)`

### `public.agent_allowed_codeintel_repo`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `repo_id` | character varying(255) | no |  |

- **Primary key:** `PRIMARY KEY (agent_id, repo_id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`

### `public.agent_allowed_knowledge_collection`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `collection_id` | uuid | no |  |

- **Primary key:** `PRIMARY KEY (agent_id, collection_id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (collection_id) REFERENCES knowledge_collection(id) ON DELETE CASCADE`

### `public.agent_allowed_knowledge_write_collection`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `collection_id` | uuid | no |  |

- **Primary key:** `PRIMARY KEY (agent_id, collection_id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`

### `public.agent_allowed_skill`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `skill_name` | character varying(64) | no |  |

- **Primary key:** `PRIMARY KEY (agent_id, skill_name)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent_skill_config(agent_id) ON DELETE CASCADE`

### `public.agent_allowed_tool`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `tool_name` | character varying(255) | no |  |

- **Primary key:** `PRIMARY KEY (agent_id, tool_name)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`

### `public.agent_browser_profile`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `profile_id` | uuid | no |  |
| `attached_by` | character varying(255) | yes |  |
| `attached_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (agent_id)`
- **Foreign keys:** `FOREIGN KEY (profile_id) REFERENCES browser_profile(id) ON DELETE CASCADE`
- **Index:** `ix_agent_browser_profile_profile — btree (profile_id)`

### `public.agent_capability`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `capability_key` | character varying(64) | no |  |
| `enabled` | boolean | no | `false` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (agent_id, capability_key)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`

### `public.agent_collaborator`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `collaborator_agent_id` | uuid | no |  |

- **Primary key:** `PRIMARY KEY (agent_id, collaborator_agent_id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (collaborator_agent_id) REFERENCES agent(id) ON DELETE CASCADE`

### `public.agent_delegation_grant`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `agent_id` | uuid | no |  |
| `granted_by` | character varying(128) | no |  |
| `audience` | character varying(512) | no |  |
| `scopes` | text | no |  |
| `max_token_ttl_seconds` | bigint | no |  |
| `expires_at` | timestamp with time zone | no |  |
| `wallet_id` | uuid | yes |  |
| `status` | character varying(16) | no | `'ACTIVE'::character varying` |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `revoked_at` | timestamp with time zone | yes |  |
| `revoked_by` | character varying(128) | yes |  |
| `revoke_reason` | text | yes |  |
| `spend_cap_minor` | bigint | yes |  |
| `spent_minor` | bigint | no | `0` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `idx_delegation_grant_agent — btree (agent_id, status)`

### `public.agent_delegation_token`

| Column | Type | Null | Default |
|---|---|---|---|
| `jti` | character varying(64) | no |  |
| `grant_id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `audience` | character varying(512) | no |  |
| `issued_at` | timestamp with time zone | no | `now()` |
| `expires_at` | timestamp with time zone | no |  |
| `status` | character varying(16) | no | `'ACTIVE'::character varying` |
| `wallet_id` | uuid | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (jti)`
- **Foreign keys:** `FOREIGN KEY (grant_id) REFERENCES agent_delegation_grant(id) ON DELETE CASCADE`
- **Index:** `idx_delegation_token_grant — btree (grant_id, status)`

### `public.agent_enabled_guardrail`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `guardrail_type` | character varying(32) | no |  |

- **Primary key:** `PRIMARY KEY (agent_id, guardrail_type)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `idx_guardrail_type — btree (guardrail_type)`

### `public.agent_eval_gate`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `dataset_id` | uuid | no |  |
| `enabled` | boolean | no | `true` |
| `max_run_age_hours` | integer | yes |  |
| `created_by` | character varying(255) | yes |  |
| `updated_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `require_simulation` | boolean | no | `false` |
| `max_simulation_age_hours` | integer | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (agent_id)`
- **Foreign keys:** `FOREIGN KEY (dataset_id) REFERENCES eval_dataset(id) ON DELETE CASCADE`
- **Index:** `UNIQUE agent_eval_gate_agent_id_key — btree (agent_id)`
- **Index:** `idx_agent_eval_gate_dataset — btree (dataset_id)`

### `public.agent_event_trigger`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `event_type` | character varying(128) | no |  |
| `filter_json` | text | yes |  |
| `goal_template` | text | no |  |
| `enabled` | boolean | no | `true` |
| `disabled_reason` | character varying(48) | yes |  |
| `guard_reset_at` | timestamp with time zone | yes |  |
| `last_fired_at` | timestamp with time zone | yes |  |
| `fire_count` | bigint | no | `0` |
| `created_by` | character varying(200) | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (agent_id, event_type)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `idx_agent_event_trigger_dispatch — btree (event_type, enabled) WHERE enabled`
- **Index:** `UNIQUE uq_agent_event_trigger_agent_event — btree (agent_id, event_type)`

### `public.agent_event_trigger_fire`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `trigger_id` | uuid | no |  |
| `event_key` | character varying(255) | no |  |
| `payload_fingerprint` | character varying(64) | yes |  |
| `job_id` | uuid | yes |  |
| `fired_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (trigger_id, event_key)`
- **Foreign keys:** `FOREIGN KEY (trigger_id) REFERENCES agent_event_trigger(id) ON DELETE CASCADE`
- **Index:** `idx_agent_event_trigger_fire_fingerprint — btree (trigger_id, payload_fingerprint, fired_at)`
- **Index:** `idx_agent_event_trigger_fire_window — btree (trigger_id, fired_at)`
- **Index:** `UNIQUE uq_agent_event_trigger_fire — btree (trigger_id, event_key)`
- **Index:** `idx_agent_event_trigger_fire_age — btree (fired_at)`

### `public.agent_experiment`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `name` | character varying(255) | no |  |
| `status` | character varying(16) | no |  |
| `control_agent_id` | uuid | no |  |
| `treatment_agent_id` | uuid | no |  |
| `treatment_version_id` | uuid | yes |  |
| `traffic_percent` | integer | no |  |
| `auto_stop_error_threshold_pct` | integer | yes |  |
| `stop_reason` | text | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `started_at` | timestamp with time zone | yes |  |
| `stopped_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (control_agent_id) REFERENCES agent(id)`
- **Foreign keys:** `FOREIGN KEY (treatment_agent_id) REFERENCES agent(id)`
- **Index:** `UNIQUE uq_agent_experiment_active_per_agent — btree (agent_id) WHERE ((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'RUNNING'::character varying])::text[]))`
- **Index:** `idx_agent_experiment_running_autostop — btree (status) WHERE (((status)::text = 'RUNNING'::text) AND (auto_stop_error_threshold_pct IS NOT NULL))`
- **Index:** `idx_agent_experiment_agent_id — btree (agent_id, created_at DESC)`

### `public.agent_experiment_assignment`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `experiment_id` | uuid | no |  |
| `subject_key` | character varying(255) | no |  |
| `variant` | character varying(16) | no |  |
| `assigned_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (experiment_id) REFERENCES agent_experiment(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_agent_experiment_assignment_subject — btree (experiment_id, subject_key)`

### `public.agent_handoff_audit`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `turn_session_id` | character varying(255) | no |  |
| `root_agent_id` | uuid | no |  |
| `from_agent_id` | uuid | no |  |
| `to_agent_id` | uuid | no |  |
| `capability_tag` | character varying(100) | yes |  |
| `depth` | integer | no |  |
| `sub_task` | character varying(2000) | yes |  |
| `status` | character varying(20) | no |  |
| `error_message` | character varying(1000) | yes |  |
| `started_at` | timestamp with time zone | no |  |
| `completed_at` | timestamp with time zone | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (from_agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (root_agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (to_agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `idx_agent_handoff_audit_root_agent — btree (root_agent_id, created_at DESC)`
- **Index:** `idx_agent_handoff_audit_turn_session — btree (turn_session_id, depth)`

### `public.agent_health_score`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | character varying(255) | yes |  |
| `agent_id` | uuid | no |  |
| `score` | integer | no |  |
| `status` | character varying(16) | no |  |
| `open_issue_count` | integer | no | `0` |
| `pending_suggestion_count` | integer | no | `0` |
| `last_swept_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (agent_id)`
- **Index:** `idx_agent_health_score_status — btree (status)`
- **Index:** `UNIQUE agent_health_score_agent_id_key — btree (agent_id)`

### `public.agent_hook_config`

| Column | Type | Null | Default |
|---|---|---|---|
| `scope_key` | character varying(64) | no |  |
| `agent_id` | uuid | yes |  |
| `hooks_json` | text | no | `'[]'::text` |
| `settings_json` | text | no | `'{}'::text` |
| `updated_by` | character varying(255) | yes |  |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (scope_key)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `idx_agent_hook_config_agent_id — btree (agent_id)`

### `public.agent_improvement_suggestion`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | character varying(255) | yes |  |
| `agent_id` | uuid | no |  |
| `issue_id` | uuid | yes |  |
| `kind` | character varying(32) | no |  |
| `title` | character varying(255) | no |  |
| `rationale` | text | yes |  |
| `suggested_text` | text | no |  |
| `status` | character varying(16) | no | `'PENDING'::character varying` |
| `applied_ref` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `decided_at` | timestamp with time zone | yes |  |
| `decided_by` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_agent_improvement_agent_created — btree (agent_id, created_at DESC)`
- **Index:** `idx_agent_improvement_issue_status — btree (issue_id, status)`

### `public.agent_job`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | character varying(255) | no |  |
| `agent_id` | uuid | no |  |
| `goal` | text | no |  |
| `status` | character varying(32) | no |  |
| `requested_by` | character varying(255) | no |  |
| `requested_by_roles` | character varying(512) | yes |  |
| `subject_key` | character varying(255) | no |  |
| `origin` | character varying(32) | no |  |
| `origin_ref` | character varying(255) | yes |  |
| `session_id` | character varying(128) | no |  |
| `progress_note` | character varying(500) | yes |  |
| `progress_updated_at` | timestamp with time zone | yes |  |
| `result_text` | text | yes |  |
| `result_json` | text | yes |  |
| `result_schema_json` | text | yes |  |
| `schema_attempts` | integer | no | `0` |
| `cost_usd` | numeric | yes |  |
| `error_code` | character varying(64) | yes |  |
| `error_message` | text | yes |  |
| `timeout_seconds` | integer | no |  |
| `deadline_at` | timestamp with time zone | yes |  |
| `max_turns` | integer | no | `1` |
| `turns_used` | integer | no | `0` |
| `max_tool_calls` | integer | no |  |
| `cancel_requested` | boolean | no | `false` |
| `cancel_requested_by` | character varying(255) | yes |  |
| `cancel_requested_at` | timestamp with time zone | yes |  |
| `owner_instance` | character varying(128) | yes |  |
| `heartbeat_at` | timestamp with time zone | yes |  |
| `attempt_count` | integer | no | `0` |
| `schedule_id` | uuid | yes |  |
| `schedule_occurrence_at` | timestamp with time zone | yes |  |
| `trigger_id` | uuid | yes |  |
| `trigger_depth` | integer | no | `0` |
| `parked_at` | timestamp with time zone | yes |  |
| `approval_deadline_at` | timestamp with time zone | yes |  |
| `park_count` | integer | no | `0` |
| `resume_reason` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `started_at` | timestamp with time zone | yes |  |
| `finished_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_agent_job_live — btree (deadline_at) WHERE ((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'AWAITING_APPROVAL'::character varying])::text[]))`
- **Index:** `idx_agent_job_agent_created — btree (agent_id, created_at DESC)`
- **Index:** `idx_agent_job_status_created — btree (status, created_at)`
- **Index:** `UNIQUE uq_agent_job_live_per_schedule — btree (schedule_id) WHERE ((schedule_id IS NOT NULL) AND ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'AWAITING_APPROVAL'::character varying])::text[])))`
- **Index:** `UNIQUE uq_agent_job_schedule_occurrence — btree (schedule_id, schedule_occurrence_at) WHERE (schedule_id IS NOT NULL)`
- **Index:** `idx_agent_job_approval_deadline — btree (approval_deadline_at) WHERE ((status)::text = 'AWAITING_APPROVAL'::text)`

### `public.agent_job_progress`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `job_id` | uuid | no |  |
| `note` | character varying(500) | no |  |
| `percent` | smallint | yes |  |
| `source` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (job_id) REFERENCES agent_job(id) ON DELETE CASCADE`
- **Index:** `idx_agent_job_progress_job — btree (job_id, created_at)`

### `public.agent_memory`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `subject_key` | character varying(255) | no |  |
| `content` | text | no |  |
| `embedding` | vector(384) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `last_used_at` | timestamp with time zone | no | `now()` |
| `use_count` | integer | no | `0` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `idx_agent_memory_agent_subject — btree (agent_id, subject_key)`

### `public.agent_pilot_baseline`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `job_type` | character varying(32) | yes |  |
| `baseline_period` | character varying(80) | yes |  |
| `monthly_volume` | numeric | yes |  |
| `avg_handling_minutes` | numeric | yes |  |
| `missed_rate` | numeric | yes |  |
| `avg_value_per_conversion` | numeric | yes |  |
| `value_currency` | character(3) | yes |  |
| `entered_by` | character varying(255) | yes |  |
| `entered_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (agent_id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`

### `public.agent_policy`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `dimension` | character varying(32) | no |  |
| `enabled` | boolean | no | `false` |
| `exploration_rate` | numeric | no | `0.100` |
| `algorithm` | character varying(16) | no | `'THOMPSON'::character varying` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (agent_id, dimension)`
- **Index:** `idx_agent_policy_agent_id — btree (agent_id)`
- **Index:** `UNIQUE uq_agent_policy_agent_dimension — btree (agent_id, dimension)`

### `public.agent_procedure`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `procedure_id` | uuid | no |  |
| `position` | integer | no | `0` |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (agent_id, procedure_id)`
- **Foreign keys:** `FOREIGN KEY (procedure_id) REFERENCES procedures(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_agent_procedure — btree (agent_id, procedure_id)`
- **Index:** `idx_agent_procedure_agent — btree (agent_id, "position")`

### `public.agent_schedule`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `cron` | character varying(64) | yes |  |
| `timezone` | character varying(64) | no | `'UTC'::character varying` |
| `goal_template` | text | no |  |
| `enabled` | boolean | no | `true` |
| `next_run_at` | timestamp with time zone | yes |  |
| `last_fired_at` | timestamp with time zone | yes |  |
| `last_job_id` | uuid | yes |  |
| `last_skipped_at` | timestamp with time zone | yes |  |
| `last_skip_reason` | character varying(48) | yes |  |
| `consecutive_skip_count` | integer | no | `0` |
| `total_fire_count` | bigint | no | `0` |
| `total_skip_count` | bigint | no | `0` |
| `created_by` | character varying(200) | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `interval_seconds` | integer | yes |  |
| `target_type` | character varying(16) | no | `'AGENT'::character varying` |
| `workflow_id` | uuid | yes |  |
| `sentinel_enabled` | boolean | no | `false` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (agent_id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id) ON DELETE CASCADE`
- **Index:** `idx_agent_schedule_due — btree (enabled, next_run_at) WHERE enabled`
- **Index:** `UNIQUE uq_agent_schedule_agent — btree (agent_id)`

### `public.agent_schedule_run`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `schedule_id` | uuid | no |  |
| `target_type` | character varying(16) | no |  |
| `trigger_kind` | character varying(16) | no |  |
| `status` | character varying(16) | no |  |
| `started_at` | timestamp with time zone | no |  |
| `finished_at` | timestamp with time zone | yes |  |
| `job_id` | uuid | yes |  |
| `workflow_run_id` | uuid | yes |  |
| `error` | text | yes |  |
| `sentinel_outcome` | character varying(24) | yes |  |
| `sentinel_outcome_note` | character varying(500) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (schedule_id) REFERENCES agent_schedule(id) ON DELETE CASCADE`
- **Index:** `idx_agent_schedule_run_schedule — btree (schedule_id, started_at DESC)`

### `public.agent_scratchpad`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `agent_id` | uuid | no |  |
| `session_id` | uuid | yes |  |
| `key` | text | no |  |
| `body` | text | no |  |
| `byte_size` | integer | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `UNIQUE ux_agent_scratchpad_scope_key — btree (agent_id, COALESCE(session_id, '00000000-0000-0000-0000-000000000000'::uuid), key)`
- **Index:** `idx_agent_scratchpad_list — btree (agent_id, updated_at DESC)`

### `public.agent_share`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `share_id` | character varying(64) | no |  |
| `revoked` | boolean | no | `false` |
| `report_count` | integer | no | `0` |
| `viewer_count` | bigint | no | `0` |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `expires_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (agent_id)`
- **Unique:** `UNIQUE (share_id)`
- **Index:** `UNIQUE uq_agent_share_share_id — btree (share_id)`
- **Index:** `idx_agent_share_share_id — btree (share_id)`
- **Index:** `UNIQUE uq_agent_share_agent_id — btree (agent_id)`

### `public.agent_skill_config`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `skills_enabled` | boolean | no | `true` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (agent_id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`

### `public.agent_specialist`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `capability_tag` | character varying(100) | no |  |
| `description` | character varying(500) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (agent_id, capability_tag)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `idx_agent_specialist_agent_id — btree (agent_id)`
- **Index:** `idx_agent_specialist_capability_tag — btree (capability_tag)`
- **Index:** `UNIQUE uq_agent_specialist_agent_tag — btree (agent_id, capability_tag)`

### `public.agent_step_journal`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `run_id` | character varying(128) | no |  |
| `step_id` | character varying(200) | no |  |
| `kind` | character varying(16) | no |  |
| `status` | character varying(16) | no |  |
| `result_text` | text | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `completed_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (run_id, step_id)`
- **Index:** `idx_agent_step_journal_created — btree (created_at)`
- **Index:** `UNIQUE uq_agent_step_journal_run_step — btree (run_id, step_id)`

### `public.agent_tool_arg_policy`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `policies_json` | text | no |  |
| `updated_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (agent_id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`

### `public.agent_value_assumption`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `value_per_resolution` | numeric | yes |  |
| `value_currency` | character(3) | yes |  |
| `minutes_saved_per_resolution` | numeric | yes |  |
| `updated_by` | character varying(255) | yes |  |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (agent_id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`

### `public.agent_version`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `version_number` | integer | no |  |
| `config_json` | text | no |  |
| `agent_name` | character varying(255) | no |  |
| `provider_name` | character varying(32) | yes |  |
| `model` | character varying(128) | yes |  |
| `change_summary` | character varying(500) | yes |  |
| `label` | character varying(32) | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `bundle_hash` | character varying(64) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `idx_agent_version_agent_id_created_at — btree (agent_id, created_at DESC)`
- **Index:** `idx_agent_version_agent_id_version_number — btree (agent_id, version_number DESC)`
- **Index:** `UNIQUE uq_agent_version_agent_id_label — btree (agent_id, label) WHERE (label IS NOT NULL)`

### `public.agent_wallet`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `agent_id` | uuid | no |  |
| `currency` | character varying(3) | no | `'INR'::bpchar` |
| `balance_minor` | bigint | no | `0` |
| `status` | character varying(16) | no | `'ACTIVE'::character varying` |
| `created_by` | character varying(128) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (agent_id, currency)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_agent_wallet_agent_currency — btree (agent_id, currency)`

### `public.approval_policy_override`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `action_type` | character varying(128) | no |  |
| `requires_approval` | boolean | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (action_type)`
- **Index:** `UNIQUE uq_approval_policy_override_action_type — btree (action_type)`

### `public.approval_request`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `action_type` | character varying(128) | no |  |
| `summary` | text | yes |  |
| `payload_json` | text | yes |  |
| `status` | character varying(32) | no |  |
| `requested_by` | character varying(255) | yes |  |
| `decided_by` | character varying(255) | yes |  |
| `rejection_reason` | text | yes |  |
| `execution_error` | text | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `decided_at` | timestamp with time zone | yes |  |
| `retry_count` | integer | no |  |
| `next_retry_at` | timestamp with time zone | yes |  |
| `execution_result` | text | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_approval_request_action_type — btree (action_type)`
- **Index:** `idx_approval_request_requested_by_status — btree (requested_by, status)`
- **Index:** `idx_approval_request_created_at — btree (created_at DESC, id DESC)`
- **Index:** `idx_approval_request_status — btree (status)`

### `public.atlassian_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `encrypted_refresh_token` | text | yes |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.audit_log_entry`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `actor_id` | character varying(255) | yes |  |
| `action_type` | character varying(128) | no |  |
| `target_type` | character varying(64) | yes |  |
| `target_id` | character varying(128) | yes |  |
| `outcome` | character varying(32) | no |  |
| `detail` | text | yes |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_audit_log_entry_target_type — btree (target_type)`
- **Index:** `idx_audit_log_entry_actor_created — btree (actor_id, created_at)`
- **Index:** `idx_audit_log_entry_created_at — btree (created_at)`
- **Index:** `idx_audit_log_entry_action_type — btree (action_type)`

### `public.audit_seal`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `seq` | bigint | no |  |
| `tenant_scope` | character varying(255) | no |  |
| `first_row_id` | uuid | no |  |
| `first_row_at` | timestamp with time zone | no |  |
| `last_row_id` | uuid | no |  |
| `last_row_at` | timestamp with time zone | no |  |
| `row_count` | integer | no |  |
| `range_hash` | character varying(64) | no |  |
| `prev_seal_hash` | character varying(64) | no |  |
| `seal_hash` | character varying(64) | no |  |
| `algorithm` | character varying(32) | no |  |
| `signature` | text | yes |  |
| `key_id` | character varying(128) | yes |  |
| `public_key` | character varying(128) | yes |  |
| `sealed_at` | timestamp with time zone | no |  |
| `purged_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (seq)`
- **Index:** `UNIQUE uq_audit_seal_seq — btree (seq)`
- **Index:** `ix_audit_seal_range — btree (last_row_at, first_row_at)`

### `public.brief`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `title` | character varying(120) | no |  |
| `goal` | text | no |  |
| `locale` | character varying(16) | no | `'en'::character varying` |
| `status` | character varying(16) | no | `'DRAFT'::character varying` |
| `current_version` | integer | no | `1` |
| `rung` | character varying(16) | no | `'UNDER_WATCH'::character varying` |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_brief_updated_at — btree (updated_at DESC)`

### `public.brief_apply_step`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `brief_id` | uuid | no |  |
| `version` | integer | no |  |
| `job_id` | uuid | no |  |
| `seq` | integer | no |  |
| `kind` | character varying(32) | no |  |
| `ref` | character varying(32) | yes |  |
| `entity_id` | character varying(64) | yes |  |
| `status` | character varying(16) | no | `'PENDING'::character varying` |
| `error` | text | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `compensated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (brief_id) REFERENCES brief(id) ON DELETE CASCADE`
- **Index:** `idx_brief_apply_step_job — btree (brief_id, job_id, seq)`

### `public.brief_binding`

| Column | Type | Null | Default |
|---|---|---|---|
| `brief_id` | uuid | no |  |
| `ref` | character varying(32) | no |  |
| `kind` | character varying(32) | no |  |
| `entity_id` | character varying(64) | no |  |

- **Primary key:** `PRIMARY KEY (brief_id, ref)`
- **Foreign keys:** `FOREIGN KEY (brief_id) REFERENCES brief(id) ON DELETE CASCADE`

### `public.brief_trust_event`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `brief_id` | uuid | no |  |
| `from_rung` | character varying(16) | yes |  |
| `to_rung` | character varying(16) | yes |  |
| `kind` | character varying(16) | no |  |
| `evidence_json` | jsonb | yes |  |
| `version` | integer | yes |  |
| `actor` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (brief_id) REFERENCES brief(id) ON DELETE CASCADE`
- **Index:** `idx_brief_trust_event_brief — btree (brief_id, created_at DESC)`

### `public.brief_version`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `brief_id` | uuid | no |  |
| `version` | integer | no |  |
| `manifest_json` | jsonb | no |  |
| `parent_version` | integer | yes |  |
| `note` | text | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (brief_id, version)`
- **Foreign keys:** `FOREIGN KEY (brief_id) REFERENCES brief(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_brief_version — btree (brief_id, version)`

### `public.browser_profile`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(120) | no |  |
| `scope` | character varying(16) | no | `'TENANT'::character varying` |
| `owner_subject` | character varying(255) | yes |  |
| `allowed_domains` | text | no |  |
| `state_encrypted` | text | yes |  |
| `state_bytes` | integer | no | `0` |
| `state_entries` | integer | no | `0` |
| `ttl_days` | integer | no | `30` |
| `expires_at` | timestamp with time zone | yes |  |
| `last_saved_at` | timestamp with time zone | yes |  |
| `last_used_at` | timestamp with time zone | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `UNIQUE ux_browser_profile_owner_name — btree (COALESCE(owner_subject, ''::character varying), lower((name)::text))`

### `public.browser_recipe`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | yes |  |
| `name` | character varying(120) | no |  |
| `description` | text | yes |  |
| `allowed_domains` | text | no |  |
| `recipe_json` | text | no |  |
| `step_count` | integer | no | `0` |
| `run_count` | integer | no | `0` |
| `fail_count` | integer | no | `0` |
| `last_run_at` | timestamp with time zone | yes |  |
| `last_failure` | character varying(500) | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `ix_browser_recipe_agent — btree (agent_id)`
- **Index:** `UNIQUE ux_browser_recipe_agent_name — btree (COALESCE(agent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower((name)::text))`

### `public.capability_policy`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `capability` | character varying(32) | no |  |
| `overrides` | jsonb | no | `'{}'::jsonb` |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (capability)`
- **Index:** `UNIQUE capability_policy_capability_key — btree (capability)`

### `public.challenge_nonces`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | character varying(128) | no |  |
| `tenant_id` | uuid | no |  |
| `user_id` | uuid | yes |  |
| `client_ip` | inet | no |  |
| `challenge_type` | character varying(20) | no |  |
| `metadata` | jsonb | yes |  |
| `created_at` | timestamp without time zone | yes | `CURRENT_TIMESTAMP` |
| `expires_at` | timestamp without time zone | no |  |
| `verified_at` | timestamp without time zone | yes |  |
| `verified_by_ip` | inet | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_challenge_nonces_expires — btree (expires_at)`
- **Index:** `idx_challenge_nonces_verified — btree (tenant_id, verified_at)`
- **Index:** `idx_challenge_nonces_lookup — btree (tenant_id, id, expires_at)`

### `public.channel_contact`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `channel` | character varying(16) | no |  |
| `identifier` | character varying(128) | no |  |
| `identifier_digits` | character varying(64) | yes |  |
| `display_name` | character varying(255) | yes |  |
| `group_id` | uuid | yes |  |
| `agent_id` | uuid | yes |  |
| `source` | character varying(16) | no | `'IMPORT'::character varying` |
| `session_id` | uuid | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `last_seen_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (channel, identifier)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE SET NULL`
- **Foreign keys:** `FOREIGN KEY (group_id) REFERENCES contact_group(id) ON DELETE SET NULL`
- **Foreign keys:** `FOREIGN KEY (session_id) REFERENCES channel_session(id) ON DELETE SET NULL`
- **Index:** `idx_channel_contact_group_id — btree (group_id)`
- **Index:** `idx_channel_contact_created_at — btree (created_at DESC, id DESC)`
- **Index:** `UNIQUE channel_contact_channel_identifier_key — btree (channel, identifier)`
- **Index:** `idx_channel_contact_digits — btree (channel, identifier_digits) WHERE (identifier_digits IS NOT NULL)`

### `public.channel_default_agent`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `channel` | character varying(16) | no |  |
| `agent_id` | uuid | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `auto_approve_new_senders` | boolean | no | `false` |
| `inbound_safety_mode` | character varying(16) | no | `'FLAG'::character varying` |
| `declared_task` | character varying(32) | yes |  |
| `declared_task_at` | timestamp with time zone | yes |  |
| `declared_task_by` | character varying(255) | yes |  |
| `open_ended_override` | boolean | no | `false` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (channel)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id)`
- **Index:** `UNIQUE uq_channel_default_agent_channel — btree (channel)`

### `public.channel_message_log`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `channel` | character varying(16) | no |  |
| `external_id` | character varying(255) | no |  |
| `channel_session_id` | uuid | yes |  |
| `body` | character varying(2000) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `direction` | character varying(8) | no | `'INBOUND'::character varying` |
| `author` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_channel_message_log_channel_external — btree (channel, external_id, created_at DESC)`
- **Index:** `idx_channel_message_log_session_created — btree (channel_session_id, created_at)`
- **Index:** `idx_channel_message_log_channel_created — btree (channel, created_at DESC)`

### `public.channel_session`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `channel` | character varying(16) | no |  |
| `external_id` | character varying(255) | no |  |
| `session_id` | character varying(64) | no |  |
| `agent_id` | uuid | no |  |
| `created_at` | timestamp with time zone | no |  |
| `last_message_at` | timestamp with time zone | no |  |
| `paired` | boolean | no |  |
| `pairing_code` | character varying(16) | yes |  |
| `pairing_notice_sent` | boolean | no |  |
| `ai_disclosure_sent` | boolean | no |  |
| `last_message_preview` | character varying(500) | yes |  |
| `resolved_phone_number` | character varying(64) | yes |  |
| `handoff_state` | character varying(16) | no | `'AGENT'::character varying` |
| `escalation_reason` | character varying(500) | yes |  |
| `escalated_at` | timestamp with time zone | yes |  |
| `taken_over_by` | character varying(255) | yes |  |
| `taken_over_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (channel, external_id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id)`
- **Index:** `idx_channel_session_agent_id — btree (agent_id)`
- **Index:** `idx_channel_session_session_id — btree (session_id)`
- **Index:** `idx_channel_session_handoff_live — btree (last_message_at DESC) WHERE ((handoff_state)::text = ANY ((ARRAY['AWAITING_HUMAN'::character varying, 'HUMAN'::character varying])::text[]))`
- **Index:** `UNIQUE uq_channel_session_channel_external — btree (channel, external_id)`

### `public.chat_branch_node`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `session_id` | character varying(128) | no |  |
| `parent_message_id` | uuid | yes |  |
| `role` | character varying(16) | no |  |
| `content` | text | no |  |
| `sibling_index` | integer | no | `0` |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_chat_branch_node_sibling — btree (agent_id, session_id, COALESCE(parent_message_id, '00000000-0000-0000-0000-000000000000'::uuid), sibling_index)`
- **Index:** `idx_chat_branch_node_session — btree (agent_id, session_id, created_at)`

### `public.chat_branch_session`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `session_id` | character varying(128) | no |  |
| `owner_subject` | character varying(255) | yes |  |
| `active_leaf_message_id` | uuid | yes |  |
| `reuse_user_message_id` | uuid | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `idx_chat_branch_session_owner — btree (owner_subject, updated_at DESC)`
- **Index:** `UNIQUE uq_chat_branch_session — btree (agent_id, session_id)`

### `public.commerce_mandate`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `kind` | character varying(8) | no |  |
| `intent_mandate_id` | uuid | yes |  |
| `agent_id` | uuid | no |  |
| `buyer_user_id` | character varying(128) | no |  |
| `currency` | character varying(3) | no | `'INR'::bpchar` |
| `max_total_minor` | bigint | yes |  |
| `cart_total_minor` | bigint | yes |  |
| `items_json` | text | yes |  |
| `payee` | character varying(256) | yes |  |
| `categories` | text | yes |  |
| `intent_text` | text | yes |  |
| `status` | character varying(24) | no | `'ACTIVE'::character varying` |
| `canonical_json` | text | no |  |
| `signature` | bytea | no |  |
| `key_id` | character varying(64) | no |  |
| `buyer_auth_json` | text | yes |  |
| `buyer_signature` | bytea | yes |  |
| `authorized_at` | timestamp with time zone | yes |  |
| `wallet_debit_ref` | bigint | yes |  |
| `expires_at` | timestamp with time zone | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `revoked_at` | timestamp with time zone | yes |  |
| `revoked_by` | character varying(128) | yes |  |
| `revoke_reason` | text | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (intent_mandate_id) REFERENCES commerce_mandate(id) ON DELETE RESTRICT`
- **Foreign keys:** `FOREIGN KEY (key_id) REFERENCES payment_signing_key(key_id)`
- **Index:** `idx_commerce_mandate_agent — btree (agent_id, status)`
- **Index:** `idx_commerce_mandate_intent — btree (intent_mandate_id) WHERE (intent_mandate_id IS NOT NULL)`

### `public.commerce_mandate_event`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('commerce_mandate_event_id_seq'::regclass)` |
| `mandate_id` | uuid | no |  |
| `event_type` | character varying(32) | no |  |
| `actor` | character varying(128) | no |  |
| `detail` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (mandate_id) REFERENCES commerce_mandate(id) ON DELETE CASCADE`
- **Index:** `idx_commerce_mandate_event_mandate — btree (mandate_id, id)`

### `public.contact_group`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(128) | no |  |
| `description` | character varying(500) | yes |  |
| `agent_id` | uuid | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (name)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE SET NULL`
- **Index:** `UNIQUE contact_group_name_key — btree (name)`

### `public.conversation_correction`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `conversation_key` | character varying(160) | no |  |
| `agent_id` | uuid | no |  |
| `marked_by` | character varying(255) | yes |  |
| `note` | character varying(500) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (conversation_key)`
- **Index:** `idx_conversation_correction_agent — btree (agent_id, created_at)`
- **Index:** `UNIQUE conversation_correction_uq — btree (conversation_key)`

### `public.custom_code_tool`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tool_name` | character varying(64) | no |  |
| `display_name` | character varying(128) | no |  |
| `description` | text | yes |  |
| `language` | character varying(16) | no |  |
| `source_code` | text | no |  |
| `input_schema_json` | text | yes |  |
| `output_description` | text | yes |  |
| `enabled` | boolean | no | `true` |
| `status` | character varying(16) | no | `'DRAFT'::character varying` |
| `validation_error` | text | yes |  |
| `source_sha256` | character varying(64) | yes |  |
| `timeout_ms` | integer | no | `5000` |
| `memory_mb` | integer | no | `128` |
| `version` | integer | no | `1` |
| `created_by` | character varying(128) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (tool_name)`
- **Index:** `idx_custom_code_tool_created — btree (created_at DESC)`
- **Index:** `idx_custom_code_tool_runnable — btree (enabled, status)`
- **Index:** `UNIQUE custom_code_tool_tool_name_key — btree (tool_name)`

### `public.custom_code_tool_secret`

| Column | Type | Null | Default |
|---|---|---|---|
| `tool_id` | uuid | no |  |
| `secret_name` | character varying(64) | no |  |

- **Primary key:** `PRIMARY KEY (tool_id, secret_name)`
- **Foreign keys:** `FOREIGN KEY (tool_id) REFERENCES custom_code_tool(id) ON DELETE CASCADE`
- **Index:** `idx_custom_code_tool_secret_name — btree (secret_name)`

### `public.data_table`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(100) | no |  |
| `description` | text | yes |  |
| `columns` | jsonb | no | `'[]'::jsonb` |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `UNIQUE uq_data_table_name_lower — btree (lower((name)::text))`

### `public.data_table_row`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `table_id` | uuid | no |  |
| `data` | jsonb | no | `'{}'::jsonb` |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (table_id) REFERENCES data_table(id) ON DELETE CASCADE`
- **Index:** `idx_data_table_row_table — btree (table_id, created_at, id)`

### `public.demo_dataset`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | smallint | no | `1` |
| `label` | character varying(120) | no |  |
| `seeded_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`

### `public.desktop_recipe`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | yes |  |
| `name` | character varying(120) | no |  |
| `description` | text | yes |  |
| `allowed_apps` | text | no |  |
| `default_mode` | character varying(16) | no | `'AUTO'::character varying` |
| `recipe_json` | text | no |  |
| `step_count` | integer | no | `0` |
| `run_count` | integer | no | `0` |
| `fail_count` | integer | no | `0` |
| `last_run_at` | timestamp with time zone | yes |  |
| `last_failure` | character varying(500) | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `ix_desktop_recipe_agent — btree (agent_id)`
- **Index:** `UNIQUE ux_desktop_recipe_agent_name — btree (COALESCE(agent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower((name)::text))`

### `public.device_app_usage_event`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `device_id` | uuid | no |  |
| `package_name` | character varying(255) | no |  |
| `event_type` | character varying(16) | no |  |
| `event_time` | timestamp with time zone | yes |  |
| `received_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (device_id) REFERENCES mobile_device(id)`
- **Index:** `idx_device_app_usage_device_received — btree (device_id, received_at DESC)`

### `public.device_command`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `device_id` | uuid | no |  |
| `command_type` | character varying(64) | no |  |
| `payload` | text | yes |  |
| `status` | character varying(16) | no |  |
| `result` | text | yes |  |
| `workflow_run_id` | uuid | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `delivered_at` | timestamp with time zone | yes |  |
| `completed_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (device_id) REFERENCES mobile_device(id)`
- **Index:** `idx_device_command_device_status_created — btree (device_id, status, created_at)`

### `public.device_notification`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `device_id` | uuid | no |  |
| `package_name` | character varying(255) | no |  |
| `title` | character varying(500) | yes |  |
| `text` | text | yes |  |
| `posted_at` | timestamp with time zone | yes |  |
| `received_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (device_id) REFERENCES mobile_device(id)`
- **Index:** `idx_device_notification_device_received — btree (device_id, received_at DESC)`

### `public.device_screen_state`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `device_id` | uuid | no |  |
| `screen_text` | text | yes |  |
| `foreground_app` | character varying(255) | yes |  |
| `captured_at` | timestamp with time zone | yes |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (device_id)`
- **Foreign keys:** `FOREIGN KEY (device_id) REFERENCES mobile_device(id)`
- **Index:** `UNIQUE device_screen_state_device_id_key — btree (device_id)`

### `public.device_sms_message`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `device_id` | uuid | no |  |
| `address` | character varying(64) | yes |  |
| `body` | text | yes |  |
| `direction` | character varying(16) | no |  |
| `sms_timestamp` | timestamp with time zone | yes |  |
| `received_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (device_id) REFERENCES mobile_device(id)`
- **Index:** `idx_device_sms_device_received — btree (device_id, received_at DESC)`

### `public.discord_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `application_id` | character varying(32) | no |  |
| `bot_username` | character varying(255) | yes |  |
| `encrypted_bot_token` | text | no |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_validated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.document_generation_job`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | character varying(255) | yes |  |
| `type` | character varying(16) | no |  |
| `filename` | character varying(255) | no |  |
| `status` | character varying(16) | no |  |
| `storage_key` | character varying(512) | yes |  |
| `content_json` | text | no |  |
| `error_message` | text | yes |  |
| `requested_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `completed_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_document_generation_job_tenant_created — btree (tenant_id, created_at DESC)`
- **Index:** `idx_document_generation_job_filename — btree (filename)`

### `public.e2b_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `encrypted_api_key` | text | no |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `code_execution_template_id` | character varying(64) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.edge_turn`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `session_key` | character varying(255) | yes |  |
| `tier` | smallint | yes |  |
| `model_id` | character varying(128) | yes |  |
| `outcome` | character varying(16) | no |  |
| `reason` | character varying(512) | yes |  |
| `latency_ms` | bigint | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_edge_turn_agent_created — btree (agent_id, created_at)`
- **Index:** `idx_edge_turn_created — btree (created_at)`

### `public.elevenlabs_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `encrypted_api_key` | text | no |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.elevenlabs_generated_audio`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `filename` | character varying(255) | no |  |
| `content_type` | character varying(100) | no |  |
| `object_key` | character varying(512) | no |  |
| `voice_id` | character varying(128) | no |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_elevenlabs_generated_audio_created_at — btree (created_at)`

### `public.elevenlabs_voice`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `elevenlabs_voice_id` | character varying(128) | no |  |
| `name` | character varying(255) | no |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.email_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `imap_host` | character varying(255) | no |  |
| `imap_port` | integer | no |  |
| `imap_username` | character varying(255) | no |  |
| `encrypted_imap_password` | text | no |  |
| `use_ssl` | boolean | no | `true` |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_validated_at` | timestamp with time zone | yes |  |
| `last_polled_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.email_message`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `run_id` | character varying(64) | yes |  |
| `node_id` | character varying(64) | yes |  |
| `recipient` | character varying(320) | no |  |
| `subject` | character varying(998) | yes |  |
| `message_id` | character varying(512) | yes |  |
| `thread_key` | character varying(128) | yes |  |
| `status` | character varying(16) | no |  |
| `error` | character varying(1000) | yes |  |
| `sent_at` | timestamp with time zone | yes |  |
| `replied_at` | timestamp with time zone | yes |  |
| `reply_kind` | character varying(16) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_email_message_run_id — btree (run_id)`
- **Index:** `idx_email_message_message_id — btree (message_id)`
- **Index:** `idx_email_message_recipient — btree (lower((recipient)::text))`

### `public.email_suppression`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `email` | character varying(320) | no |  |
| `reason` | character varying(32) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (email)`
- **Index:** `UNIQUE email_suppression_email_key — btree (email)`

### `public.eval_dataset`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `name` | character varying(255) | no |  |
| `description` | text | yes |  |
| `pass_threshold` | numeric | no | `0.700` |
| `tool_execution_enabled` | boolean | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `content_hash` | character varying(64) | yes |  |
| `auto_approve_tools` | boolean | no | `false` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_eval_dataset_agent — btree (agent_id, created_at DESC)`

### `public.eval_dataset_case`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `dataset_id` | uuid | no |  |
| `user_message` | text | no |  |
| `expected_output` | text | yes |  |
| `expected_tools` | text | yes |  |
| `position` | integer | no | `0` |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `grader_spec` | text | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (dataset_id) REFERENCES eval_dataset(id) ON DELETE CASCADE`
- **Index:** `idx_eval_dataset_case_dataset — btree (dataset_id, "position")`

### `public.eval_run`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `dataset_id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `tenant_id` | character varying(255) | no |  |
| `requested_by` | character varying(255) | no |  |
| `requested_by_roles` | character varying(512) | yes |  |
| `status` | character varying(16) | no |  |
| `verdict` | character varying(16) | yes |  |
| `pass_threshold` | numeric | no |  |
| `overall_score` | numeric | yes |  |
| `case_count` | integer | no | `0` |
| `scored_case_count` | integer | no | `0` |
| `failed_assertion_count` | integer | no | `0` |
| `errored_case_count` | integer | no | `0` |
| `judge_model` | character varying(255) | yes |  |
| `error_message` | text | yes |  |
| `cancel_requested` | boolean | no | `false` |
| `cancel_requested_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `started_at` | timestamp with time zone | yes |  |
| `finished_at` | timestamp with time zone | yes |  |
| `agent_version_id` | uuid | yes |  |
| `dataset_hash` | character varying(64) | yes |  |
| `requested_judge_model` | character varying(255) | yes |  |
| `strict_judge` | boolean | no | `false` |
| `repeats` | integer | no | `1` |
| `budget_usd` | numeric | no | `5.00` |
| `cost_usd` | numeric | yes |  |
| `idempotency_key` | character varying(128) | yes |  |
| `agent_model` | character varying(128) | yes |  |
| `agent_temperature` | double precision | yes |  |
| `agent_seed` | integer | yes |  |
| `agent_config_hash` | character varying(64) | yes |  |
| `agent_config_json` | text | yes |  |
| `auto_approved` | boolean | no | `false` |
| `eval_mode` | character varying(8) | no | `'QUICK'::character varying` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (dataset_id) REFERENCES eval_dataset(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_eval_run_idempotency_key — btree (dataset_id, idempotency_key) WHERE (idempotency_key IS NOT NULL)`
- **Index:** `UNIQUE uq_eval_run_live_per_dataset — btree (dataset_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying])::text[]))`
- **Index:** `idx_eval_run_dataset_version_status — btree (dataset_id, agent_version_id, status) WHERE (agent_version_id IS NOT NULL)`
- **Index:** `idx_eval_run_dataset_created — btree (dataset_id, created_at DESC)`

### `public.eval_run_case_result`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `run_id` | uuid | no |  |
| `case_id` | uuid | yes |  |
| `position` | integer | no | `0` |
| `user_message` | text | no |  |
| `expected_output` | text | yes |  |
| `expected_tools` | text | yes |  |
| `agent_response` | text | yes |  |
| `tool_calls_json` | text | yes |  |
| `scores_json` | text | yes |  |
| `overall_score` | numeric | yes |  |
| `expected_tools_satisfied` | boolean | yes |  |
| `status` | character varying(16) | no |  |
| `error_message` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `finished_at` | timestamp with time zone | yes |  |
| `passed` | boolean | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (run_id) REFERENCES eval_run(id) ON DELETE CASCADE`
- **Index:** `idx_eval_run_case_result_run — btree (run_id, "position")`

### `public.flyway_schema_history`

| Column | Type | Null | Default |
|---|---|---|---|
| `installed_rank` | integer | no |  |
| `version` | character varying(50) | yes |  |
| `description` | character varying(200) | no |  |
| `type` | character varying(20) | no |  |
| `script` | character varying(1000) | no |  |
| `checksum` | integer | yes |  |
| `installed_by` | character varying(100) | no |  |
| `installed_on` | timestamp without time zone | no | `now()` |
| `execution_time` | integer | no |  |
| `success` | boolean | no |  |

- **Primary key:** `PRIMARY KEY (installed_rank)`
- **Index:** `flyway_schema_history_s_idx — btree (success)`

### `public.generated_image`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `filename` | character varying(255) | no |  |
| `content_type` | character varying(100) | no |  |
| `object_key` | character varying(512) | no |  |
| `prompt` | text | no |  |
| `model` | character varying(64) | no |  |
| `size` | character varying(32) | yes |  |
| `provider` | character varying(32) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_generated_image_created_at — btree (created_at)`

### `public.generated_music`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `filename` | character varying(255) | no |  |
| `content_type` | character varying(100) | no |  |
| `object_key` | character varying(512) | no |  |
| `prompt` | text | no |  |
| `model` | character varying(128) | no |  |
| `provider` | character varying(64) | no |  |
| `billed_to` | character varying(16) | no |  |
| `duration_seconds` | integer | no |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_generated_music_billed_created — btree (billed_to, created_at)`

### `public.google_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `connected_email` | character varying(255) | no |  |
| `encrypted_refresh_token` | text | no |  |
| `granted_scopes` | text | no |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `subject_key` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `UNIQUE uq_google_credential_tenant_default — btree ((true)) WHERE (subject_key IS NULL)`
- **Index:** `UNIQUE uq_google_credential_subject_key — btree (subject_key) WHERE (subject_key IS NOT NULL)`

### `public.guardrail_rule`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | character varying(255) | yes |  |
| `rule_type` | character varying(32) | no |  |
| `name` | character varying(255) | no |  |
| `description` | text | yes |  |
| `pattern` | text | no |  |
| `action` | character varying(32) | no |  |
| `enabled` | boolean | no | `true` |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_guardrail_rule_type — btree (rule_type, created_at)`

### `public.imessage_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `webhook_id` | character varying(48) | no |  |
| `server_url` | character varying(512) | no |  |
| `encrypted_password` | text | no |  |
| `encrypted_webhook_secret` | text | no |  |
| `status` | character varying(16) | no |  |
| `inbound_mode` | character varying(16) | no | `'WEBHOOK'::character varying` |
| `allowed_senders` | text | no | `''::text` |
| `allow_groups` | boolean | no | `false` |
| `unknown_sender_policy` | character varying(16) | no | `'PAIR'::character varying` |
| `poll_cursor_ms` | bigint | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `last_validated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.imported_chat`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `owner_subject` | character varying(255) | no |  |
| `source` | character varying(20) | no |  |
| `source_id` | character varying(128) | yes |  |
| `title` | character varying(300) | no |  |
| `source_created_at` | timestamp with time zone | yes |  |
| `source_updated_at` | timestamp with time zone | yes |  |
| `imported_at` | timestamp with time zone | no |  |
| `message_count` | integer | no | `0` |
| `truncated` | boolean | no | `false` |
| `messages` | jsonb | no | `'[]'::jsonb` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_imported_chat_owner — btree (owner_subject, imported_at DESC)`
- **Index:** `UNIQUE uq_imported_chat_source — btree (owner_subject, source, source_id) WHERE (source_id IS NOT NULL)`

### `public.ip_blacklist`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('ip_blacklist_id_seq'::regclass)` |
| `tenant_id` | uuid | no |  |
| `ip_address` | inet | no |  |
| `reason` | character varying(100) | no |  |
| `blocked_by` | character varying(50) | no |  |
| `expires_at` | timestamp without time zone | no |  |
| `created_at` | timestamp without time zone | yes | `CURRENT_TIMESTAMP` |
| `created_by` | uuid | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_ip_blacklist_expires — btree (expires_at)`
- **Index:** `idx_ip_blacklist_lookup — btree (tenant_id, ip_address, expires_at)`
- **Index:** `UNIQUE idx_ip_blacklist_tenant_ip_active — btree (tenant_id, ip_address)`

### `public.jev_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `encrypted_api_key` | text | no |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.jev_policy`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `cost_routing` | boolean | yes |  |
| `agent_routing` | boolean | yes |  |
| `simple_threshold` | double precision | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.knowledge_chunk_reembed_shadow`

| Column | Type | Null | Default |
|---|---|---|---|
| `chunk_id` | uuid | no |  |
| `embedding_model` | character varying(64) | no |  |
| `collection_id` | uuid | no |  |
| `embedding` | vector(768) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (chunk_id, embedding_model)`
- **Foreign keys:** `FOREIGN KEY (chunk_id) REFERENCES knowledge_chunks(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (collection_id) REFERENCES knowledge_collection(id) ON DELETE CASCADE`
- **Index:** `idx_knowledge_chunk_reembed_shadow_collection — btree (collection_id, embedding_model)`

### `public.knowledge_chunks`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `source` | character varying(500) | yes |  |
| `chunk_index` | integer | no |  |
| `content` | text | no |  |
| `embedding` | vector(768) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `collection_id` | uuid | no |  |
| `embedding_model` | character varying(64) | no | `'minilm-l6-v2'::character varying` |
| `document_id` | uuid | yes |  |
| `content_tsv` | tsvector | yes |  |
| `metadata` | jsonb | no | `'{}'::jsonb` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (collection_id) REFERENCES knowledge_collection(id)`
- **Foreign keys:** `FOREIGN KEY (document_id) REFERENCES knowledge_document(id)`
- **Index:** `idx_knowledge_chunks_content_tsv — gin (content_tsv)`
- **Index:** `idx_knowledge_chunks_metadata — gin (metadata jsonb_path_ops)`
- **Index:** `idx_knowledge_chunks_document — btree (document_id)`

### `public.knowledge_collection`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(255) | no |  |
| `description` | text | yes |  |
| `parent_collection_id` | uuid | yes |  |
| `is_default` | boolean | no | `false` |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `embedding_model` | character varying(64) | no | `'minilm-l6-v2'::character varying` |
| `text_search_config` | character varying(64) | no | `'simple'::character varying` |
| `chunk_size` | integer | yes |  |
| `chunk_overlap` | integer | yes |  |
| `embedding_status` | character varying(32) | no | `'READY'::character varying` |
| `parent_child_enabled` | boolean | no | `false` |
| `embedding_fingerprint` | character varying(190) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (parent_collection_id) REFERENCES knowledge_collection(id) ON DELETE CASCADE`

### `public.knowledge_connector`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `collection_id` | uuid | no |  |
| `type` | character varying(24) | no |  |
| `name` | character varying(255) | no |  |
| `config_jsonb` | jsonb | no | `'{}'::jsonb` |
| `enabled` | boolean | no | `true` |
| `sync_cursor` | character varying(1024) | yes |  |
| `last_synced_at` | timestamp with time zone | yes |  |
| `last_error` | text | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (collection_id) REFERENCES knowledge_collection(id) ON DELETE CASCADE`
- **Index:** `idx_knowledge_connector_collection — btree (collection_id)`

### `public.knowledge_document`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `collection_id` | uuid | no |  |
| `filename` | character varying(500) | no |  |
| `file_extension` | character varying(16) | yes |  |
| `file_size_bytes` | bigint | no |  |
| `status` | character varying(32) | no |  |
| `chunk_count` | integer | yes |  |
| `error_message` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `object_key` | character varying(512) | yes |  |
| `content_type` | character varying(128) | yes |  |
| `content_hash` | character varying(64) | yes |  |
| `source_url` | character varying(2048) | yes |  |
| `external_ref` | character varying(1024) | yes |  |
| `connector_id` | uuid | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (collection_id) REFERENCES knowledge_collection(id)`
- **Index:** `idx_knowledge_document_content_hash — btree (collection_id, content_hash)`
- **Index:** `idx_knowledge_document_connector_ref — btree (connector_id, external_ref)`
- **Index:** `idx_knowledge_document_collection — btree (collection_id, created_at DESC)`

### `public.knowledge_face_enrollment`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `collection_id` | uuid | no |  |
| `document_id` | uuid | yes |  |
| `person_name` | character varying(200) | no |  |
| `embedding` | vector(512) | no |  |
| `consented_at` | timestamp with time zone | no |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (document_id) REFERENCES knowledge_document(id) ON DELETE SET NULL`
- **Index:** `idx_face_enrollment_collection — btree (collection_id)`

### `public.knowledge_ingestion_job`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `filename` | character varying(500) | no |  |
| `collection_id` | uuid | no |  |
| `chunk_size` | integer | no |  |
| `chunk_overlap` | integer | no |  |
| `status` | character varying(32) | no |  |
| `error_message` | text | yes |  |
| `result_chunk_count` | integer | yes |  |
| `audio_content` | bytea | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `document_id` | uuid | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (collection_id) REFERENCES knowledge_collection(id)`
- **Foreign keys:** `FOREIGN KEY (document_id) REFERENCES knowledge_document(id)`
- **Index:** `idx_knowledge_ingestion_job_status — btree (status)`

### `public.knowledge_parent_chunk`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `collection_id` | uuid | no |  |
| `document_id` | uuid | yes |  |
| `parent_index` | integer | no |  |
| `content` | text | no |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (collection_id) REFERENCES knowledge_collection(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (document_id) REFERENCES knowledge_document(id) ON DELETE CASCADE`
- **Index:** `idx_knowledge_parent_chunk_document — btree (document_id)`
- **Index:** `idx_knowledge_parent_chunk_collection — btree (collection_id)`

### `public.knowledge_reembed_job`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `collection_id` | uuid | no |  |
| `source_model` | character varying(64) | no |  |
| `target_model` | character varying(64) | no |  |
| `status` | character varying(32) | no |  |
| `total_chunks` | bigint | no | `0` |
| `processed_chunks` | bigint | no | `0` |
| `error_message` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `completed_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (collection_id) REFERENCES knowledge_collection(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_knowledge_reembed_job_active — btree (collection_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'EMBEDDING'::character varying, 'SWEEPING'::character varying])::text[]))`
- **Index:** `idx_knowledge_reembed_job_collection — btree (collection_id, created_at DESC)`
- **Index:** `idx_knowledge_reembed_job_status — btree (status)`

### `public.lead`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(255) | yes |  |
| `email` | character varying(320) | yes |  |
| `phone` | character varying(64) | yes |  |
| `company` | character varying(255) | yes |  |
| `title` | character varying(255) | yes |  |
| `source` | character varying(64) | yes |  |
| `stage` | character varying(16) | no | `'NEW'::character varying` |
| `score` | integer | no | `0` |
| `owner` | character varying(255) | yes |  |
| `value` | numeric | yes |  |
| `tags` | text | yes |  |
| `notes` | text | yes |  |
| `last_contacted_at` | timestamp with time zone | yes |  |
| `last_replied_at` | timestamp with time zone | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_lead_stage — btree (stage)`
- **Index:** `UNIQUE uq_lead_email_lower — btree (lower((email)::text)) WHERE (email IS NOT NULL)`
- **Index:** `idx_lead_created_at — btree (created_at DESC)`

### `public.lead_activity`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `lead_id` | uuid | no |  |
| `type` | character varying(16) | no |  |
| `body` | text | yes |  |
| `meta` | jsonb | yes |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (lead_id) REFERENCES lead(id) ON DELETE CASCADE`
- **Index:** `idx_lead_activity_lead — btree (lead_id, created_at DESC)`

### `public.learning_proposals`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `subject` | character varying(255) | no |  |
| `agent_id` | uuid | yes |  |
| `kind` | character varying(24) | no |  |
| `title` | character varying(200) | no |  |
| `body` | text | no |  |
| `body_hash` | character varying(64) | no |  |
| `payload` | jsonb | no | `'{}'::jsonb` |
| `evidence` | jsonb | no | `'{}'::jsonb` |
| `status` | character varying(12) | no | `'PENDING'::character varying` |
| `created_at` | timestamp with time zone | no |  |
| `decided_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `UNIQUE uq_learning_proposals_pending — btree (subject, kind, body_hash) WHERE ((status)::text = 'PENDING'::text)`
- **Index:** `idx_learning_proposals_subject — btree (subject, status, created_at DESC)`

### `public.learning_review_runs`

| Column | Type | Null | Default |
|---|---|---|---|
| `subject` | character varying(255) | no |  |
| `run_date` | date | no |  |
| `filed` | integer | no | `0` |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (subject, run_date)`

### `public.llm_call_log`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | yes |  |
| `session_id` | character varying(128) | yes |  |
| `provider_name` | character varying(32) | no |  |
| `model` | character varying(128) | no |  |
| `prompt_tokens` | bigint | yes |  |
| `completion_tokens` | bigint | yes |  |
| `total_tokens` | bigint | yes |  |
| `estimated_cost_usd` | numeric | yes |  |
| `success` | boolean | no |  |
| `error_message` | text | yes |  |
| `latency_ms` | bigint | no |  |
| `started_at` | timestamp with time zone | no |  |
| `request_id` | character varying(64) | yes |  |
| `exception_type` | character varying(255) | yes |  |
| `stack_trace` | text | yes |  |
| `experiment_variants` | character varying(500) | yes |  |
| `trace_id` | character varying(32) | yes |  |
| `span_id` | character varying(16) | yes |  |
| `cache_creation_tokens` | bigint | yes |  |
| `cache_read_tokens` | bigint | yes |  |
| `agent_version_id` | uuid | yes |  |
| `reasoning_tokens` | bigint | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_llm_call_log_exception_type — btree (exception_type) WHERE (exception_type IS NOT NULL)`
- **Index:** `idx_llm_call_log_request_id — btree (request_id) WHERE (request_id IS NOT NULL)`
- **Index:** `idx_llm_call_log_trace_id — btree (trace_id) WHERE (trace_id IS NOT NULL)`
- **Index:** `idx_llm_call_log_session_id — btree (session_id)`
- **Index:** `idx_llm_call_log_provider_name — btree (provider_name)`
- **Index:** `idx_llm_call_log_agent_started — btree (agent_id, started_at DESC)`
- **Index:** `idx_llm_call_log_started_at — btree (started_at DESC)`
- **Index:** `idx_llm_call_log_cache_read_tokens — btree (started_at) WHERE (cache_read_tokens IS NOT NULL)`

### `public.llm_daily_usage_rollup`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `usage_date` | date | no |  |
| `provider_name` | character varying(32) | no |  |
| `model` | character varying(128) | no |  |
| `agent_id` | uuid | yes |  |
| `modality` | character varying(16) | no |  |
| `total_calls` | bigint | no |  |
| `error_count` | bigint | no |  |
| `prompt_tokens` | bigint | no |  |
| `completion_tokens` | bigint | no |  |
| `total_tokens` | bigint | no |  |
| `estimated_cost_usd` | numeric | no |  |
| `latency_sum_ms` | bigint | no |  |
| `latency_p50_ms` | double precision | yes |  |
| `latency_p95_ms` | double precision | yes |  |
| `distinct_sessions` | bigint | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `UNIQUE ux_llm_daily_usage_rollup_grain — btree (usage_date, provider_name, model, COALESCE(agent_id, '00000000-0000-0000-0000-000000000000'::uuid), modality)`
- **Index:** `idx_llm_daily_usage_rollup_date — btree (usage_date DESC)`
- **Index:** `idx_llm_daily_usage_rollup_agent — btree (agent_id)`

### `public.llm_price`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `provider_name` | character varying(32) | no |  |
| `model_prefix` | character varying(128) | no |  |
| `input_per_million` | numeric | yes |  |
| `output_per_million` | numeric | yes |  |
| `cache_write_per_million` | numeric | yes |  |
| `cache_read_per_million` | numeric | yes |  |
| `effective_from` | date | no |  |
| `set_by` | character varying(255) | yes |  |
| `set_at` | timestamp with time zone | no | `now()` |
| `note` | character varying(300) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (provider_name, model_prefix, effective_from)`
- **Index:** `UNIQUE llm_price_version_uq — btree (provider_name, model_prefix, effective_from)`

### `public.llm_provider_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `provider_name` | character varying(32) | no |  |
| `status` | character varying(16) | no |  |
| `encrypted_api_key` | text | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_validated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (provider_name)`
- **Index:** `UNIQUE uq_llm_provider_credential_provider_name — btree (provider_name)`

### `public.llm_response_cache`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `question` | text | no |  |
| `answer` | text | no |  |
| `embedding` | vector(384) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `expires_at` | timestamp with time zone | no |  |
| `subject_hash` | character varying(64) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_llm_response_cache_expires — btree (expires_at)`
- **Index:** `idx_llm_response_cache_agent — btree (agent_id)`
- **Index:** `idx_llm_response_cache_agent_subject — btree (agent_id, subject_hash)`

### `public.llm_spend_month`

| Column | Type | Null | Default |
|---|---|---|---|
| `month_start` | date | no |  |
| `cost_usd` | numeric | no | `0` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (month_start)`

### `public.mandate_ledger`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('mandate_ledger_id_seq'::regclass)` |
| `mandate_id` | uuid | no |  |
| `transaction_id` | uuid | yes |  |
| `entry_kind` | character varying(12) | no |  |
| `amount_paise` | bigint | no |  |
| `day_key` | date | no |  |
| `daily_spend_paise` | bigint | no |  |
| `month_key` | character varying(7) | no |  |
| `monthly_spend_paise` | bigint | no |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (transaction_id, entry_kind)`
- **Foreign keys:** `FOREIGN KEY (mandate_id) REFERENCES payment_mandate(id)`
- **Foreign keys:** `FOREIGN KEY (transaction_id) REFERENCES payment_transaction(id)`
- **Index:** `idx_mandate_ledger_mandate — btree (mandate_id, id DESC)`
- **Index:** `UNIQUE mandate_ledger_transaction_id_entry_kind_key — btree (transaction_id, entry_kind)`

### `public.matrix_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `homeserver_url` | character varying(512) | no |  |
| `bot_user_id` | character varying(255) | no |  |
| `device_id` | character varying(128) | yes |  |
| `encrypted_access_token` | text | no |  |
| `status` | character varying(16) | no |  |
| `join_policy` | character varying(16) | no | `'OFF'::character varying` |
| `sender_mode` | character varying(16) | no | `'PAIRING'::character varying` |
| `allowed_senders` | text | no | `''::text` |
| `since_token` | text | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_validated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.matrix_room_notice`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `room_id` | character varying(255) | no |  |
| `kind` | character varying(32) | no |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (room_id, kind)`
- **Index:** `UNIQUE uq_matrix_room_notice — btree (room_id, kind)`

### `public.mcp_oauth_connection`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `resource_url` | character varying(1024) | no |  |
| `issuer` | character varying(512) | no |  |
| `authorization_endpoint` | text | no |  |
| `token_endpoint` | text | no |  |
| `client_id` | character varying(512) | no |  |
| `encrypted_client_secret` | text | yes |  |
| `token_endpoint_auth_method` | character varying(64) | yes |  |
| `redirect_uri` | text | yes |  |
| `scope` | text | yes |  |
| `encrypted_refresh_token` | text | yes |  |
| `encrypted_access_token` | text | yes |  |
| `access_token_expires_at` | timestamp with time zone | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (resource_url)`
- **Index:** `UNIQUE uq_mcp_oauth_connection_resource — btree (resource_url)`

### `public.mcp_server_access_grant`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(128) | no |  |
| `api_key_hash` | character varying(64) | no |  |
| `api_key_display_prefix` | character varying(16) | no |  |
| `enabled` | boolean | no | `true` |
| `exposed_agent_ids` | text | no | `'[]'::text` |
| `exposed_workflow_ids` | text | no | `'[]'::text` |
| `exposed_knowledge_collection_ids` | text | no | `'[]'::text` |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `last_used_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (api_key_hash)`
- **Index:** `UNIQUE mcp_server_access_grant_api_key_hash_key — btree (api_key_hash)`

### `public.mcp_server_registration`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(128) | no |  |
| `type` | character varying(32) | no |  |
| `description` | text | yes |  |
| `enabled` | boolean | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `command` | character varying(255) | yes |  |
| `args` | text | yes |  |
| `encrypted_env` | text | yes |  |
| `connection_status` | character varying(32) | no | `'NOT_CONFIGURED'::character varying` |
| `connection_error` | text | yes |  |
| `connected_at` | timestamp with time zone | yes |  |
| `url` | character varying(2048) | yes |  |
| `connection_phase` | character varying(32) | yes |  |
| `connection_hint` | text | yes |  |
| `usage_status` | character varying(32) | no | `'UNTESTED'::character varying` |
| `suggested_fix_command` | character varying(255) | yes |  |
| `suggested_fix_args` | text | yes |  |
| `suggested_fix_reason` | text | yes |  |
| `review_status` | character varying(32) | no | `'NOT_REQUIRED'::character varying` |
| `review_rejection_reason` | text | yes |  |
| `requested_by` | character varying(255) | yes |  |
| `credentials_corrupted` | boolean | no | `false` |
| `retry_attempt_count` | integer | no | `0` |
| `next_retry_at` | timestamp with time zone | yes |  |
| `retry_exhausted` | boolean | no | `false` |
| `last_attempted_at` | timestamp with time zone | yes |  |
| `edge_eligible` | boolean | no | `false` |
| `oauth_provider` | character varying(32) | yes |  |
| `tools_pinned_hash` | character varying(64) | yes |  |
| `tools_pinned_manifest` | text | yes |  |
| `tools_pinned_at` | timestamp with time zone | yes |  |
| `tools_pinned_by` | character varying(255) | yes |  |
| `tools_observed_hash` | character varying(64) | yes |  |
| `tools_observed_manifest` | text | yes |  |
| `tools_changed_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (name)`
- **Index:** `UNIQUE uq_mcp_server_registration_name — btree (name)`

### `public.mcp_starter_seed`

| Column | Type | Null | Default |
|---|---|---|---|
| `starter_key` | character varying(64) | no |  |
| `seeded_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (starter_key)`

### `public.mcp_tool_exposure`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `resource_type` | character varying(16) | no |  |
| `resource_id` | uuid | no |  |
| `tool_name` | character varying(64) | no |  |
| `exposed` | boolean | no | `false` |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (resource_type, resource_id)`
- **Unique:** `UNIQUE (tool_name)`
- **Index:** `UNIQUE uq_mcp_tool_exposure_resource — btree (resource_type, resource_id)`
- **Index:** `UNIQUE uq_mcp_tool_exposure_tool_name — btree (tool_name)`

### `public.meta_social_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `page_id` | character varying(64) | no |  |
| `page_name` | character varying(255) | yes |  |
| `encrypted_page_access_token` | text | no |  |
| `encrypted_app_secret` | text | no |  |
| `instagram_business_account_id` | character varying(64) | yes |  |
| `instagram_username` | character varying(255) | yes |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_validated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.meta_whatsapp_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `phone_number_id` | character varying(64) | no |  |
| `waba_id` | character varying(64) | no |  |
| `encrypted_access_token` | text | no |  |
| `encrypted_app_secret` | text | no |  |
| `display_phone_number` | character varying(32) | yes |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_validated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.mobile_device`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `device_name` | character varying(120) | no |  |
| `platform` | character varying(16) | no |  |
| `device_token` | character varying(64) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `last_seen_at` | timestamp with time zone | yes |  |
| `revoked` | boolean | no | `false` |
| `capabilities` | character varying(512) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (device_token)`
- **Index:** `UNIQUE mobile_device_device_token_key — btree (device_token)`

### `public.mobile_sentinel_event`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `user_id` | character varying(255) | no |  |
| `client_event_id` | character varying(64) | no |  |
| `sentinel_id` | character varying(64) | yes |  |
| `sentinel_name` | character varying(160) | yes |  |
| `template` | character varying(64) | yes |  |
| `severity` | character varying(16) | no |  |
| `title` | character varying(200) | yes |  |
| `summary` | text | yes |  |
| `kinds` | text | yes |  |
| `findings` | text | yes |  |
| `readings` | text | yes |  |
| `device_label` | character varying(120) | yes |  |
| `occurred_at` | timestamp with time zone | no |  |
| `received_at` | timestamp with time zone | no | `now()` |
| `escalated_at` | timestamp with time zone | yes |  |
| `escalated_to` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (user_id, client_event_id)`
- **Index:** `UNIQUE uq_mobile_sentinel_event_client — btree (user_id, client_event_id)`
- **Index:** `idx_mobile_sentinel_event_user_time — btree (user_id, occurred_at DESC)`
- **Index:** `idx_mobile_sentinel_event_time — btree (occurred_at)`

### `public.mobile_sentinel_policy`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | smallint | no | `1` |
| `enabled` | boolean | no | `true` |
| `sync_enabled` | boolean | no | `false` |
| `allow_raw_readings` | boolean | no | `false` |
| `allowed_kinds` | text | yes |  |
| `caregiver_escalation_enabled` | boolean | no | `false` |
| `retention_days` | integer | no | `30` |
| `updated_by` | character varying(255) | yes |  |
| `updated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.moderation_restricted_audit`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | character varying(255) | yes |  |
| `agent_id` | uuid | yes |  |
| `session_id` | character varying(255) | yes |  |
| `category` | character varying(64) | yes |  |
| `confidence` | integer | no |  |
| `classifier_reference` | character varying(255) | yes |  |
| `content_hash` | character varying(64) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`

### `public.moderation_review_item`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | character varying(255) | yes |  |
| `agent_id` | uuid | yes |  |
| `session_id` | character varying(255) | yes |  |
| `content` | text | yes |  |
| `policy_pack` | character varying(64) | no |  |
| `deciding_layer` | character varying(32) | no |  |
| `confidence` | integer | no |  |
| `status` | character varying(32) | no |  |
| `reason` | text | yes |  |
| `direction` | character varying(64) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `reviewed_by` | character varying(255) | yes |  |
| `reviewed_at` | timestamp with time zone | yes |  |
| `review_notes` | text | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_moderation_review_item_status — btree (status, created_at DESC)`

### `public.notification`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `recipient_user_id` | character varying(255) | yes |  |
| `category` | character varying(32) | no |  |
| `title` | character varying(255) | no |  |
| `body` | text | yes |  |
| `severity` | character varying(16) | no |  |
| `is_read` | boolean | no | `false` |
| `created_at` | timestamp with time zone | no |  |
| `link` | character varying(512) | yes |  |
| `related_issue_id` | uuid | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_notification_recipient — btree (recipient_user_id, is_read, created_at DESC)`
- **Index:** `idx_notification_broadcast — btree (is_read, created_at DESC) WHERE (recipient_user_id IS NULL)`

### `public.ocr_provider_config`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `provider` | character varying(32) | no |  |
| `encrypted_api_key` | text | no |  |
| `active` | boolean | no | `true` |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`

### `public.openapi_tool`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `source_id` | uuid | no |  |
| `tool_name` | character varying(64) | no |  |
| `operation_key` | character varying(600) | no |  |
| `operation_id` | character varying(256) | yes |  |
| `http_method` | character varying(12) | no |  |
| `path_template` | text | no |  |
| `summary` | text | yes |  |
| `description` | text | yes |  |
| `parameters_json` | text | no |  |
| `body_mode` | character varying(16) | no | `'NONE'::character varying` |
| `body_content_type` | character varying(128) | yes |  |
| `input_schema_json` | text | no |  |
| `enabled` | boolean | no | `true` |
| `deprecated` | boolean | no | `false` |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (source_id, operation_key)`
- **Unique:** `UNIQUE (tool_name)`
- **Foreign keys:** `FOREIGN KEY (source_id) REFERENCES openapi_tool_source(id) ON DELETE CASCADE`
- **Index:** `UNIQUE openapi_tool_tool_name_key — btree (tool_name)`
- **Index:** `idx_openapi_tool_source — btree (source_id)`
- **Index:** `idx_openapi_tool_runnable — btree (enabled) WHERE enabled`
- **Index:** `UNIQUE uq_openapi_tool_source_operation — btree (source_id, operation_key)`

### `public.openapi_tool_source`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(128) | no |  |
| `tool_prefix` | character varying(32) | no |  |
| `spec_url` | text | yes |  |
| `spec_text` | text | no |  |
| `spec_title` | character varying(256) | yes |  |
| `spec_version` | character varying(64) | yes |  |
| `base_url` | text | no |  |
| `auth_type` | character varying(32) | no | `'NONE'::character varying` |
| `auth_header_name` | character varying(128) | yes |  |
| `encrypted_secret` | text | yes |  |
| `secret_preview` | character varying(16) | yes |  |
| `enabled` | boolean | no | `true` |
| `last_refreshed_at` | timestamp with time zone | yes |  |
| `last_error` | text | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `oauth2_token_url` | text | yes |  |
| `oauth2_client_id` | character varying(512) | yes |  |
| `oauth2_scope` | character varying(512) | yes |  |
| `oauth2_token_style` | character varying(8) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (tool_prefix)`
- **Index:** `UNIQUE openapi_tool_source_tool_prefix_key — btree (tool_prefix)`

### `public.payment_mandate`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `agent_id` | uuid | no |  |
| `granted_by_user_id` | character varying(128) | no |  |
| `scope` | character varying(8) | no |  |
| `max_per_txn_paise` | bigint | no |  |
| `max_daily_paise` | bigint | no |  |
| `max_monthly_paise` | bigint | no |  |
| `allowed_vpas` | text | yes |  |
| `allowed_merchant_categories` | text | yes |  |
| `approval_policy` | character varying(20) | no | `'ALWAYS'::character varying` |
| `approval_threshold_paise` | bigint | yes |  |
| `valid_from` | timestamp with time zone | no |  |
| `valid_until` | timestamp with time zone | no |  |
| `status` | character varying(16) | no | `'ACTIVE'::character varying` |
| `canonical_json` | text | no |  |
| `signature` | bytea | no |  |
| `key_id` | character varying(64) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `revoked_at` | timestamp with time zone | yes |  |
| `revoked_by` | character varying(128) | yes |  |
| `revoke_reason` | text | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (key_id) REFERENCES payment_signing_key(key_id)`
- **Index:** `idx_payment_mandate_agent_status — btree (agent_id, status)`
- **Index:** `idx_payment_mandate_agent — btree (agent_id)`

### `public.payment_signing_key`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `key_id` | character varying(64) | no |  |
| `public_key` | bytea | no |  |
| `private_key_encrypted` | text | no |  |
| `status` | character varying(16) | no | `'ACTIVE'::character varying` |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (key_id)`
- **Index:** `UNIQUE payment_signing_key_key_id_key — btree (key_id)`
- **Index:** `UNIQUE uq_payment_signing_key_active — btree (status) WHERE ((status)::text = 'ACTIVE'::text)`

### `public.payment_transaction`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `mandate_id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `session_id` | character varying(128) | yes |  |
| `type` | character varying(20) | no |  |
| `amount_paise` | bigint | no |  |
| `currency` | character varying(3) | no | `'INR'::bpchar` |
| `vpa` | character varying(128) | yes |  |
| `description` | text | yes |  |
| `merchant_category` | character varying(64) | yes |  |
| `gateway` | character varying(16) | no | `'RAZORPAY'::character varying` |
| `razorpay_entity_id` | character varying(64) | yes |  |
| `gateway_payment_id` | character varying(64) | yes |  |
| `status` | character varying(24) | no | `'INITIATED'::character varying` |
| `approval_id` | uuid | yes |  |
| `idempotency_key` | character varying(160) | no |  |
| `short_url` | character varying(512) | yes |  |
| `receipt_url` | character varying(512) | yes |  |
| `failure_reason` | text | yes |  |
| `expires_at` | timestamp with time zone | yes |  |
| `next_poll_at` | timestamp with time zone | yes |  |
| `poll_count` | integer | no | `0` |
| `paid_at` | timestamp with time zone | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `version` | bigint | no | `0` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (idempotency_key)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (mandate_id) REFERENCES payment_mandate(id)`
- **Index:** `idx_payment_transaction_mandate — btree (mandate_id)`
- **Index:** `UNIQUE payment_transaction_idempotency_key_key — btree (idempotency_key)`
- **Index:** `idx_payment_transaction_poll — btree (status, next_poll_at) WHERE ((status)::text = 'AWAITING_PAYMENT'::text)`
- **Index:** `idx_payment_transaction_razorpay — btree (razorpay_entity_id)`
- **Index:** `idx_payment_transaction_agent — btree (agent_id, created_at DESC)`

### `public.platform_issue`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `fingerprint` | character varying(128) | no |  |
| `source` | character varying(32) | no |  |
| `source_id` | character varying(128) | yes |  |
| `severity` | character varying(16) | no |  |
| `status` | character varying(16) | no |  |
| `title` | character varying(255) | no |  |
| `message` | text | yes |  |
| `occurrence_count` | integer | no | `1` |
| `first_seen_at` | timestamp with time zone | no |  |
| `last_seen_at` | timestamp with time zone | no |  |
| `acknowledged_by` | character varying(255) | yes |  |
| `acknowledged_at` | timestamp with time zone | yes |  |
| `resolved_at` | timestamp with time zone | yes |  |
| `category` | character varying(32) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (fingerprint)`
- **Index:** `UNIQUE uq_platform_issue_fingerprint — btree (fingerprint)`
- **Index:** `idx_platform_issue_status — btree (status)`
- **Index:** `idx_platform_issue_last_seen_at — btree (last_seen_at)`
- **Index:** `idx_platform_issue_source — btree (source)`

### `public.policy_arm`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `policy_id` | uuid | no |  |
| `arm_key` | character varying(128) | no |  |
| `arm_config_json` | text | yes |  |
| `alpha` | double precision | no | `1.0` |
| `beta` | double precision | no | `1.0` |
| `pulls` | bigint | no | `0` |
| `reward_sum` | double precision | no | `0.0` |
| `context_bucket` | character varying(64) | yes |  |
| `enabled` | boolean | no | `true` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_policy_arm_lookup — btree (policy_id, arm_key, context_bucket)`
- **Index:** `idx_policy_arm_policy — btree (policy_id)`

### `public.preference_signals`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `subject` | character varying(255) | no |  |
| `agent_id` | uuid | yes |  |
| `signal_key` | character varying(64) | no |  |
| `source` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_preference_signals_count — btree (subject, signal_key, created_at DESC)`

### `public.privacy_consent_policy`

| Column | Type | Null | Default |
|---|---|---|---|
| `agent_id` | uuid | no |  |
| `enabled` | boolean | no | `false` |
| `purpose` | character varying(64) | no | `'support'::character varying` |
| `notice_version` | integer | no | `1` |
| `default_language` | character varying(16) | no | `'en'::character varying` |
| `notices` | jsonb | no | `'{}'::jsonb` |
| `instructions` | jsonb | no | `'{}'::jsonb` |
| `keywords` | jsonb | no | `'{}'::jsonb` |
| `erase_on_withdrawal` | boolean | no | `true` |
| `updated_by` | character varying(255) | yes |  |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (agent_id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`

### `public.privacy_consent_record`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `seq` | bigint | no |  |
| `channel` | character varying(16) | no |  |
| `external_id` | character varying(255) | no |  |
| `agent_id` | uuid | yes |  |
| `purpose` | character varying(64) | no |  |
| `notice_version` | integer | no |  |
| `notice_language` | character varying(16) | no |  |
| `notice_sha256` | character varying(64) | yes |  |
| `decision` | character varying(16) | no |  |
| `decided_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_privacy_consent_record_subject — btree (channel, external_id, purpose, seq DESC)`

### `public.privacy_purpose_tag`

| Column | Type | Null | Default |
|---|---|---|---|
| `resource_type` | character varying(24) | no |  |
| `resource_id` | uuid | no |  |
| `purpose` | character varying(64) | no |  |
| `tagged_by` | character varying(255) | yes |  |
| `tagged_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (resource_type, resource_id, purpose)`
- **Index:** `idx_privacy_purpose_tag_resource — btree (resource_type, resource_id)`

### `public.privacy_retention_policy`

| Column | Type | Null | Default |
|---|---|---|---|
| `channel` | character varying(16) | no |  |
| `data_class` | character varying(24) | no |  |
| `enabled` | boolean | no | `false` |
| `retention_days` | integer | no |  |
| `updated_by` | character varying(255) | yes |  |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (channel, data_class)`

### `public.procedure_rule`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `procedure_id` | uuid | no |  |
| `rule_order` | integer | no |  |
| `instruction` | text | no |  |
| `guard_kind` | character varying(40) | yes |  |
| `guard_tool` | character varying(255) | yes |  |
| `guard_config` | jsonb | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (procedure_id) REFERENCES procedures(id) ON DELETE CASCADE`
- **Index:** `idx_procedure_rule_procedure — btree (procedure_id, rule_order)`

### `public.procedures`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tenant_id` | character varying(255) | yes |  |
| `name` | character varying(255) | no |  |
| `description` | text | yes |  |
| `status` | character varying(16) | no | `'DRAFT'::character varying` |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.prompt_directive_setting`

| Column | Type | Null | Default |
|---|---|---|---|
| `directive_key` | character varying(64) | no |  |
| `enabled` | boolean | no | `true` |
| `text_override` | text | yes |  |
| `updated_at` | timestamp with time zone | no | `now()` |
| `updated_by` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (directive_key)`

### `public.prompt_override`

| Column | Type | Null | Default |
|---|---|---|---|
| `scope` | character varying(16) | no |  |
| `scope_id` | character varying(64) | no | `''::character varying` |
| `prompt_key` | character varying(128) | no |  |
| `locale` | character varying(16) | no | `'en'::character varying` |
| `prompt_text` | text | yes |  |
| `enabled` | boolean | no | `true` |
| `version` | integer | no |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (scope, scope_id, prompt_key, locale)`

### `public.prompt_override_history`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('prompt_override_history_id_seq'::regclass)` |
| `scope` | character varying(16) | no |  |
| `scope_id` | character varying(64) | no | `''::character varying` |
| `prompt_key` | character varying(128) | no |  |
| `locale` | character varying(16) | no | `'en'::character varying` |
| `version` | integer | no |  |
| `action` | character varying(16) | no |  |
| `prompt_text` | text | yes |  |
| `enabled` | boolean | no |  |
| `previous_text` | text | yes |  |
| `previous_enabled` | boolean | no | `true` |
| `reason` | character varying(500) | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (scope, scope_id, prompt_key, locale, version)`
- **Index:** `UNIQUE prompt_override_history_version_uq — btree (scope, scope_id, prompt_key, locale, version)`
- **Index:** `idx_prompt_override_history_lookup — btree (scope, scope_id, prompt_key, locale, created_at DESC)`

### `public.prompt_version`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `system_prompt` | text | no |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `idx_prompt_version_agent_id_created_at — btree (agent_id, created_at DESC)`

### `public.provider_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `provider_key` | character varying(32) | no |  |
| `capabilities` | ARRAY | no |  |
| `encrypted_secret` | text | no |  |
| `status` | character varying(16) | no |  |
| `extra_config` | jsonb | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_provider_credential_capabilities — gin (capabilities)`
- **Index:** `UNIQUE idx_provider_credential_provider_key — btree (provider_key)`
- **Index:** `idx_provider_credential_status — btree (status)`

### `public.quick_task_run`

| Column | Type | Null | Default |
|---|---|---|---|
| `task_id` | character varying(128) | no |  |
| `agent_id` | uuid | no |  |
| `requested_by_keycloak_user_id` | character varying(128) | no |  |
| `channel` | character varying(32) | no |  |
| `request_message` | text | no |  |
| `result_text` | text | yes |  |
| `delivery_note` | text | yes |  |
| `status` | character varying(16) | no |  |
| `error_message` | text | yes |  |
| `started_at` | timestamp with time zone | no |  |
| `completed_at` | timestamp with time zone | yes |  |
| `agent_version_id` | uuid | yes |  |

- **Primary key:** `PRIMARY KEY (task_id)`
- **Index:** `idx_quick_task_run_requested_by_started_at — btree (requested_by_keycloak_user_id, started_at DESC)`

### `public.resolution_event`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `conversation_key` | character varying(160) | no |  |
| `agent_id` | uuid | no |  |
| `channel` | character varying(32) | yes |  |
| `outcome` | character varying(24) | no |  |
| `evidence` | character varying(32) | no |  |
| `confidence` | numeric | no | `1.0` |
| `billable` | boolean | no |  |
| `billable_reason` | character varying(300) | yes |  |
| `unit_price_minor` | bigint | yes |  |
| `currency` | character varying(3) | yes |  |
| `resolved_at` | timestamp with time zone | no |  |
| `dedupe_key` | character varying(200) | no |  |
| `created_by` | character varying(255) | yes |  |
| `disputed_at` | timestamp with time zone | yes |  |
| `disputed_by` | character varying(255) | yes |  |
| `dispute_reason` | character varying(500) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (dedupe_key)`
- **Index:** `idx_resolution_event_conversation — btree (conversation_key)`
- **Index:** `idx_resolution_event_billable_period — btree (billable, resolved_at)`
- **Index:** `UNIQUE resolution_event_dedupe_key_key — btree (dedupe_key)`
- **Index:** `idx_resolution_event_agent_period — btree (agent_id, resolved_at)`

### `public.resource_grant`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `resource_type` | character varying(32) | no |  |
| `resource_id` | uuid | no |  |
| `grantee_user_id` | character varying(255) | no |  |
| `permission_level` | character varying(16) | no |  |
| `granted_by` | character varying(255) | no |  |
| `granted_at` | timestamp with time zone | no | `now()` |
| `revoked_at` | timestamp with time zone | yes |  |
| `revoked_by` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_resource_grant_grantee — btree (grantee_user_id) WHERE (revoked_at IS NULL)`
- **Index:** `UNIQUE uq_resource_grant_active — btree (resource_type, resource_id, grantee_user_id) WHERE (revoked_at IS NULL)`
- **Index:** `idx_resource_grant_resource — btree (resource_type, resource_id) WHERE (revoked_at IS NULL)`

### `public.rl_episode`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `session_id` | character varying(128) | no |  |
| `turn_ref` | character varying(128) | no |  |
| `started_at` | timestamp with time zone | no |  |
| `ended_at` | timestamp with time zone | yes |  |
| `context_json` | text | yes |  |
| `policy_arm_id` | uuid | yes |  |
| `reward` | numeric | yes |  |
| `reward_breakdown_json` | text | yes |  |
| `terminal` | boolean | no | `false` |
| `subject` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_rl_episode_agent_id — btree (agent_id, started_at DESC)`
- **Index:** `idx_rl_episode_session_started — btree (session_id, started_at DESC)`
- **Index:** `idx_rl_episode_turn — btree (agent_id, session_id, turn_ref)`
- **Index:** `idx_rl_episode_policy_arm_id — btree (policy_arm_id)`

### `public.rl_episode_policy_arm`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `episode_id` | uuid | no |  |
| `policy_arm_id` | uuid | no |  |
| `dimension` | character varying(32) | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (episode_id, dimension)`
- **Foreign keys:** `FOREIGN KEY (episode_id) REFERENCES rl_episode(id)`
- **Foreign keys:** `FOREIGN KEY (policy_arm_id) REFERENCES policy_arm(id)`
- **Index:** `idx_rl_episode_policy_arm_episode_id — btree (episode_id)`
- **Index:** `idx_rl_episode_policy_arm_arm_id — btree (policy_arm_id)`
- **Index:** `UNIQUE uq_rl_episode_policy_arm_episode_dimension — btree (episode_id, dimension)`

### `public.rl_reward_record`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `episode_id` | uuid | no |  |
| `source` | character varying(24) | no |  |
| `value` | numeric | no |  |
| `weight` | numeric | no |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_rl_reward_record_episode — btree (episode_id)`

### `public.rl_trajectory_step`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `episode_id` | uuid | no |  |
| `step_index` | integer | no |  |
| `role` | character varying(32) | yes |  |
| `action_type` | character varying(64) | yes |  |
| `content` | text | yes |  |
| `tool_name` | character varying(256) | yes |  |
| `tokens` | bigint | yes |  |
| `latency_ms` | bigint | yes |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_rl_trajectory_step_episode — btree (episode_id, step_index)`

### `public.roi_budget_alert`

| Column | Type | Null | Default |
|---|---|---|---|
| `month_start` | date | no |  |
| `threshold` | smallint | no |  |
| `spend_usd` | numeric | no |  |
| `cap_usd` | numeric | no |  |
| `fired_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (month_start, threshold)`

### `public.routine_observations`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `subject` | character varying(255) | no |  |
| `agent_id` | uuid | no |  |
| `source` | character varying(8) | no |  |
| `norm_text` | character varying(600) | no |  |
| `raw_text` | character varying(500) | no |  |
| `observed_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_routine_observations_age — btree (observed_at)`
- **Index:** `idx_routine_observations_subject — btree (subject, observed_at DESC)`

### `public.sarvam_call_log`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `capability` | character varying(32) | no |  |
| `model` | character varying(64) | yes |  |
| `success` | boolean | no |  |
| `error_message` | text | yes |  |
| `latency_ms` | bigint | no |  |
| `started_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_sarvam_call_log_started_at — btree (started_at DESC)`

### `public.sarvam_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `encrypted_api_key` | text | no |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.sarvam_generated_audio`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `filename` | character varying(255) | no |  |
| `content_type` | character varying(64) | no |  |
| `audio_data` | text | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `object_key` | character varying(512) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.scheduled_task`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `name` | character varying(120) | no |  |
| `prompt` | text | no |  |
| `schedule_kind` | character varying(12) | no |  |
| `schedule_time` | character varying(5) | yes |  |
| `schedule_day` | character varying(8) | yes |  |
| `cron` | character varying(64) | no |  |
| `timezone` | character varying(64) | no | `'UTC'::character varying` |
| `delivery` | character varying(12) | no | `'IN_APP'::character varying` |
| `delivery_to` | character varying(255) | yes |  |
| `enabled` | boolean | no | `true` |
| `disabled_reason` | character varying(255) | yes |  |
| `next_run_at` | timestamp with time zone | yes |  |
| `last_run_at` | timestamp with time zone | yes |  |
| `last_status` | character varying(16) | yes |  |
| `last_job_id` | uuid | yes |  |
| `consecutive_failures` | integer | no | `0` |
| `run_count` | bigint | no | `0` |
| `max_runs` | integer | yes |  |
| `expires_at` | timestamp with time zone | yes |  |
| `owner_subject` | character varying(200) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `idx_scheduled_task_due — btree (enabled, next_run_at) WHERE enabled`
- **Index:** `idx_scheduled_task_owner — btree (owner_subject, created_at DESC)`

### `public.scheduled_task_run`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `task_id` | uuid | no |  |
| `trigger_kind` | character varying(12) | no |  |
| `status` | character varying(16) | no |  |
| `attempt` | integer | no | `1` |
| `started_at` | timestamp with time zone | no |  |
| `finished_at` | timestamp with time zone | yes |  |
| `job_id` | uuid | yes |  |
| `cost_usd` | numeric | yes |  |
| `delivery_status` | character varying(20) | yes |  |
| `error` | text | yes |  |
| `settled` | boolean | no | `false` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (task_id) REFERENCES scheduled_task(id) ON DELETE CASCADE`
- **Index:** `idx_scheduled_task_run_unsettled — btree (started_at) WHERE (NOT settled)`
- **Index:** `idx_scheduled_task_run_task — btree (task_id, started_at DESC)`

### `public.setting_override`

| Column | Type | Null | Default |
|---|---|---|---|
| `scope` | character varying(16) | no |  |
| `scope_id` | character varying(64) | no | `''::character varying` |
| `setting_key` | character varying(128) | no |  |
| `value` | jsonb | no |  |
| `version` | integer | no |  |
| `updated_by` | character varying(255) | yes |  |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (scope, scope_id, setting_key)`

### `public.setting_override_history`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('setting_override_history_id_seq'::regclass)` |
| `scope` | character varying(16) | no |  |
| `scope_id` | character varying(64) | no | `''::character varying` |
| `setting_key` | character varying(128) | no |  |
| `version` | integer | no |  |
| `action` | character varying(16) | no |  |
| `value` | jsonb | yes |  |
| `previous_value` | jsonb | yes |  |
| `reason` | character varying(500) | yes |  |
| `actor` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (scope, scope_id, setting_key, version)`
- **Index:** `idx_setting_override_history_lookup — btree (scope, scope_id, setting_key, version DESC)`
- **Index:** `UNIQUE setting_override_history_version_uq — btree (scope, scope_id, setting_key, version)`

### `public.signal_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `base_url` | character varying(512) | no |  |
| `account_number` | character varying(32) | no |  |
| `encrypted_auth_token` | text | yes |  |
| `unknown_senders` | character varying(16) | no | `'PAIR'::character varying` |
| `group_mode` | character varying(16) | no | `'IGNORE'::character varying` |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_validated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.simulation_persona`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `key` | character varying(64) | no |  |
| `name` | character varying(255) | no |  |
| `description` | text | no |  |
| `objective` | text | no |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `UNIQUE uq_simulation_persona_key — btree (key)`

### `public.simulation_run`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `session_id` | uuid | no |  |
| `persona_key` | character varying(64) | no |  |
| `persona_name` | character varying(255) | no |  |
| `status` | character varying(16) | no |  |
| `transcript_json` | text | yes |  |
| `verdict` | character varying(16) | yes |  |
| `verdict_reason` | text | yes |  |
| `turn_count` | integer | no | `0` |
| `error_message` | text | yes |  |
| `started_at` | timestamp with time zone | yes |  |
| `completed_at` | timestamp with time zone | yes |  |
| `cost_usd` | numeric | yes |  |
| `agent_version_id` | uuid | yes |  |
| `termination` | character varying(24) | yes |  |
| `cancel_requested` | boolean | no | `false` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (session_id) REFERENCES simulation_session(id) ON DELETE CASCADE`
- **Index:** `idx_simulation_run_session_id — btree (session_id)`
- **Index:** `idx_simulation_run_live — btree (status) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying])::text[]))`

### `public.simulation_session`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `persona_keys` | text | no |  |
| `max_turns` | integer | no |  |
| `status` | character varying(16) | no |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `completed_at` | timestamp with time zone | yes |  |
| `budget_usd` | numeric | no | `10.00` |
| `cancel_requested` | boolean | no | `false` |
| `cancel_requested_by` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `idx_simulation_session_agent_id — btree (agent_id, created_at DESC)`

### `public.skill`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(64) | no |  |
| `description` | character varying(1024) | no |  |
| `body` | text | no |  |
| `enabled` | boolean | no | `true` |
| `content_sha256` | character varying(64) | yes |  |
| `created_by` | character varying(128) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `pending_description` | character varying(1024) | yes |  |
| `pending_body` | text | yes |  |
| `pending_files` | jsonb | yes |  |
| `pending_rationale` | text | yes |  |
| `pending_proposed_by` | character varying(128) | yes |  |
| `pending_proposed_at` | timestamp with time zone | yes |  |
| `approved_sha256` | character varying(64) | yes |  |
| `approved_by` | character varying(128) | yes |  |
| `approved_at` | timestamp with time zone | yes |  |
| `approved_manifest` | text | yes |  |
| `scan_risk` | character varying(16) | yes |  |
| `status` | character varying(16) | no | `'ACTIVE'::character varying` |
| `origin` | character varying(16) | no | `'AUTHORED'::character varying` |
| `content_version` | integer | no | `1` |
| `uses` | bigint | no | `0` |
| `successes` | bigint | no | `0` |
| `failures` | bigint | no | `0` |
| `last_used_at` | timestamp with time zone | yes |  |
| `review_note` | character varying(1000) | yes |  |
| `learned_for_agent_id` | uuid | yes |  |
| `frontmatter_extra` | text | yes |  |
| `self_check_verdict` | character varying(8) | yes |  |
| `self_check_reason` | character varying(300) | yes |  |
| `self_checked_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (name)`
- **Index:** `UNIQUE skill_name_key — btree (name)`
- **Index:** `idx_skill_pending_improvement — btree (pending_proposed_at) WHERE (pending_proposed_at IS NOT NULL)`
- **Index:** `idx_skill_status — btree (status)`

### `public.skill_builtin_override`

| Column | Type | Null | Default |
|---|---|---|---|
| `name` | character varying(64) | no |  |
| `enabled` | boolean | no |  |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (name)`

### `public.skill_file`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `skill_id` | uuid | no |  |
| `path` | character varying(255) | no |  |
| `content` | text | no |  |
| `size_bytes` | integer | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (skill_id, path)`
- **Foreign keys:** `FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_skill_file_path — btree (skill_id, path)`

### `public.skill_version`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `skill_id` | uuid | no |  |
| `version` | integer | no |  |
| `description` | character varying(1024) | no |  |
| `body` | text | no |  |
| `files` | jsonb | yes |  |
| `content_sha256` | character varying(64) | yes |  |
| `created_by` | character varying(128) | yes |  |
| `note` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (skill_id, version)`
- **Foreign keys:** `FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_skill_version — btree (skill_id, version)`

### `public.slack_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `team_id` | character varying(32) | no |  |
| `team_name` | character varying(255) | yes |  |
| `bot_user_id` | character varying(32) | no |  |
| `encrypted_bot_token` | text | no |  |
| `encrypted_signing_secret` | text | no |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_validated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.studio_app`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `slug` | character varying(24) | no |  |
| `name` | character varying(120) | no |  |
| `html` | text | no |  |
| `status` | character varying(16) | no | `'LIVE'::character varying` |
| `auth_mode` | character varying(16) | no | `'NONE'::character varying` |
| `ai_enabled` | boolean | no | `false` |
| `ai_system_prompt` | text | yes |  |
| `ai_max_tokens` | integer | no | `700` |
| `token_secret` | character varying(64) | no |  |
| `created_by` | character varying(128) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `published_version` | integer | no | `1` |
| `version` | bigint | no | `0` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (slug)`
- **Index:** `UNIQUE uq_studio_app_slug — btree (slug)`

### `public.studio_app_connection`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `app_id` | uuid | no |  |
| `alias` | character varying(40) | no |  |
| `kind` | character varying(16) | no |  |
| `target_id` | uuid | no |  |
| `share_id` | character varying(64) | yes |  |
| `display_name` | character varying(120) | yes |  |
| `request_template` | text | yes |  |
| `response_path` | character varying(160) | yes |  |
| `requires_auth` | boolean | no | `false` |
| `sort_order` | integer | no | `0` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (app_id, alias)`
- **Foreign keys:** `FOREIGN KEY (app_id) REFERENCES studio_app(id) ON DELETE CASCADE`
- **Index:** `ix_studio_app_connection_app — btree (app_id)`
- **Index:** `UNIQUE uq_studio_app_connection_alias — btree (app_id, alias)`

### `public.studio_app_domain`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `app_id` | uuid | no |  |
| `hostname` | character varying(128) | no |  |
| `verification_token` | character varying(64) | no |  |
| `status` | character varying(16) | no | `'PENDING'::character varying` |
| `last_error` | character varying(64) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `verified_at` | timestamp with time zone | yes |  |
| `last_checked_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (hostname)`
- **Foreign keys:** `FOREIGN KEY (app_id) REFERENCES studio_app(id) ON DELETE CASCADE`
- **Index:** `idx_studio_app_domain_app — btree (app_id)`
- **Index:** `UNIQUE uq_studio_app_domain_hostname — btree (hostname)`

### `public.studio_app_user`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `app_id` | uuid | no |  |
| `email` | character varying(254) | no |  |
| `display_name` | character varying(120) | yes |  |
| `password_hash` | character varying(128) | no |  |
| `status` | character varying(16) | no | `'ACTIVE'::character varying` |
| `created_at` | timestamp with time zone | no | `now()` |
| `last_login_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (app_id, email)`
- **Foreign keys:** `FOREIGN KEY (app_id) REFERENCES studio_app(id) ON DELETE CASCADE`
- **Index:** `ix_studio_app_user_app — btree (app_id)`
- **Index:** `UNIQUE uq_studio_app_user_email — btree (app_id, email)`

### `public.studio_project`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | character varying(64) | no |  |
| `owner_subject` | character varying(128) | no |  |
| `name` | character varying(120) | no |  |
| `emoji` | character varying(16) | yes |  |
| `settings` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `ix_studio_project_owner — btree (owner_subject, updated_at DESC)`

### `public.studio_project_comment`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `project_id` | character varying(64) | no |  |
| `version_at_ms` | bigint | yes |  |
| `author_subject` | character varying(128) | no |  |
| `author_name` | character varying(160) | no |  |
| `body` | character varying(2000) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `resolved` | boolean | no | `false` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (project_id) REFERENCES studio_project(id) ON DELETE CASCADE`
- **Index:** `ix_studio_project_comment_project — btree (project_id, created_at)`

### `public.studio_project_member`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `project_id` | character varying(64) | no |  |
| `member_subject` | character varying(128) | no |  |
| `role` | character varying(16) | no |  |
| `invited_by` | character varying(128) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (project_id, member_subject)`
- **Foreign keys:** `FOREIGN KEY (project_id) REFERENCES studio_project(id) ON DELETE CASCADE`
- **Index:** `ix_studio_project_member_subject — btree (member_subject)`
- **Index:** `UNIQUE uq_studio_project_member — btree (project_id, member_subject)`

### `public.studio_project_version`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `project_id` | character varying(64) | no |  |
| `at_ms` | bigint | no |  |
| `note` | character varying(400) | yes |  |
| `source` | character varying(16) | no |  |
| `html` | text | no |  |
| `author_subject` | character varying(128) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (project_id, at_ms)`
- **Foreign keys:** `FOREIGN KEY (project_id) REFERENCES studio_project(id) ON DELETE CASCADE`
- **Index:** `UNIQUE uq_studio_project_version — btree (project_id, at_ms)`

### `public.study_plan`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `owner_subject` | character varying(255) | no |  |
| `topic` | character varying(300) | no |  |
| `language` | character varying(16) | no | `'en'::character varying` |
| `level` | character varying(32) | no | `'beginner'::character varying` |
| `goal` | character varying(500) | yes |  |
| `status` | character varying(16) | no | `'ACTIVE'::character varying` |
| `curriculum` | jsonb | no | `'{"modules": []}'::jsonb` |
| `review` | jsonb | no | `'{"cards": []}'::jsonb` |
| `tutor_agent_id` | uuid | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `version` | bigint | no | `0` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `ix_study_plan_owner — btree (owner_subject, updated_at DESC)`

### `public.subagent_run`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `workflow_run_id` | uuid | no |  |
| `node_id` | character varying(128) | no |  |
| `agent_id` | uuid | no |  |
| `input_json` | text | yes |  |
| `output_json` | text | yes |  |
| `status` | character varying(32) | no |  |
| `latency_ms` | bigint | yes |  |
| `cost_usd` | numeric | yes |  |
| `error_text` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id)`
- **Foreign keys:** `FOREIGN KEY (workflow_run_id) REFERENCES workflow_run(id)`
- **Index:** `idx_subagent_run_workflow_run_id — btree (workflow_run_id)`

### `public.teams_conversation_route`

| Column | Type | Null | Default |
|---|---|---|---|
| `conversation_id` | character varying(64) | no |  |
| `service_url` | character varying(255) | no |  |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (conversation_id)`

### `public.teams_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `app_id` | character varying(64) | no |  |
| `aad_tenant_id` | character varying(64) | no | `'botframework.com'::character varying` |
| `encrypted_app_password` | text | no |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `last_validated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.telegram_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `bot_user_id` | character varying(32) | no |  |
| `bot_username` | character varying(255) | yes |  |
| `encrypted_bot_token` | text | no |  |
| `webhook_secret` | character varying(64) | no |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_validated_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.tenant_api_key`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(128) | no |  |
| `api_key_hash` | character varying(64) | no |  |
| `api_key_display_prefix` | character varying(16) | no |  |
| `permissions_json` | text | no | `'[]'::text` |
| `created_by` | character varying(128) | yes |  |
| `expires_at` | timestamp with time zone | yes |  |
| `last_used_at` | timestamp with time zone | yes |  |
| `enabled` | boolean | no | `true` |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (api_key_hash)`
- **Index:** `UNIQUE tenant_api_key_api_key_hash_key — btree (api_key_hash)`
- **Index:** `tenant_api_key_created_at — btree (created_at)`

### `public.tenant_memory`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `subject_key` | character varying(255) | no |  |
| `content` | text | no |  |
| `embedding` | vector(384) | no |  |
| `created_by_agent_id` | uuid | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `last_used_at` | timestamp with time zone | no | `now()` |
| `use_count` | integer | no | `0` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (created_by_agent_id) REFERENCES agent(id) ON DELETE SET NULL`
- **Index:** `idx_tenant_memory_subject — btree (subject_key)`

### `public.tenant_runtime_limits`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | smallint | no | `1` |
| `max_tool_steps` | integer | yes |  |
| `turn_timeout_seconds` | integer | yes |  |
| `tool_timeout_seconds` | integer | yes |  |
| `tool_output_max_chars` | integer | yes |  |
| `retrieval_top_k` | integer | yes |  |
| `retrieval_min_score` | double precision | yes |  |
| `continuation_passes` | integer | yes |  |
| `updated_at` | timestamp with time zone | no | `now()` |
| `updated_by` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.tenant_secret`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(64) | no |  |
| `scope` | character varying(16) | no |  |
| `owner_user_id` | character varying(255) | yes |  |
| `kind` | character varying(24) | no |  |
| `description` | character varying(512) | yes |  |
| `encrypted_value` | text | no |  |
| `value_preview` | character varying(16) | yes |  |
| `status` | character varying(16) | no |  |
| `expires_at` | timestamp with time zone | yes |  |
| `last_used_at` | timestamp with time zone | yes |  |
| `rotated_at` | timestamp with time zone | yes |  |
| `created_by` | character varying(255) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `code_tool_access` | boolean | no | `false` |
| `allowed_hosts` | text | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_tenant_secret_lookup — btree (name, status)`
- **Index:** `UNIQUE idx_tenant_secret_name_tenant_scope — btree (name) WHERE ((scope)::text = 'TENANT'::text)`
- **Index:** `UNIQUE idx_tenant_secret_name_user_scope — btree (name, owner_user_id) WHERE ((scope)::text = 'USER'::text)`
- **Index:** `idx_tenant_secret_owner — btree (owner_user_id) WHERE (owner_user_id IS NOT NULL)`
- **Index:** `idx_tenant_secret_code_tool_access — btree (name) WHERE code_tool_access`

### `public.tool_call_log`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `session_id` | character varying(128) | no |  |
| `tool_name` | character varying(256) | no |  |
| `tool_input` | text | yes |  |
| `tool_output` | text | yes |  |
| `error_message` | text | yes |  |
| `started_at` | timestamp with time zone | no |  |
| `completed_at` | timestamp with time zone | no |  |
| `validation_failed` | boolean | no | `false` |
| `mcp_grant_id` | uuid | yes |  |
| `agent_version_id` | uuid | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_tool_call_log_agent_id — btree (agent_id)`
- **Index:** `idx_tool_call_log_mcp_grant_id — btree (mcp_grant_id) WHERE (mcp_grant_id IS NOT NULL)`
- **Index:** `idx_tool_call_log_session_id — btree (session_id)`

### `public.tool_classification`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tool_name` | character varying(255) | no |  |
| `trust` | character varying(32) | no |  |
| `sensitivity` | character varying(32) | no |  |
| `determinism` | character varying(32) | no |  |
| `status` | character varying(32) | no |  |
| `required_scope` | character varying(255) | yes |  |
| `irreversible` | boolean | no | `false` |
| `needs_tool_context` | boolean | no | `true` |
| `source` | character varying(32) | no |  |
| `notes` | character varying(1024) | yes |  |
| `created_by` | character varying(255) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (tool_name)`
- **Index:** `idx_tool_classification_patterns — btree (tool_name) WHERE ((tool_name)::text ~~ '%*%'::text)`
- **Index:** `idx_tool_classification_sensitivity — btree (sensitivity, status)`
- **Index:** `UNIQUE uq_tool_classification_tool_name — btree (tool_name)`

### `public.turn_feedback`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `session_id` | character varying(128) | no |  |
| `turn_ref` | character varying(128) | no |  |
| `signal` | character varying(16) | no |  |
| `rating` | smallint | yes |  |
| `comment` | text | yes |  |
| `edited_answer` | text | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_turn_feedback_agent_id — btree (agent_id, created_at DESC)`
- **Index:** `idx_turn_feedback_turn — btree (agent_id, session_id, turn_ref)`

### `public.turn_journal_event`

| Column | Type | Null | Default |
|---|---|---|---|
| `turn_id` | uuid | no |  |
| `seq` | integer | no |  |
| `agent_id` | uuid | no |  |
| `event_type` | character varying(16) | no |  |
| `payload_hash` | character(64) | no |  |
| `summary` | character varying(512) | no |  |
| `prev_hash` | character(64) | no |  |
| `hash` | character(64) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `session_hash` | character(64) | yes |  |

- **Primary key:** `PRIMARY KEY (turn_id, seq)`
- **Index:** `idx_turn_journal_event_agent_created — btree (agent_id, created_at)`
- **Index:** `idx_turn_journal_event_agent_session — btree (agent_id, session_hash, created_at) WHERE (session_hash IS NOT NULL)`

### `public.twilio_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `account_sid` | character varying(64) | no |  |
| `encrypted_auth_token` | text | no |  |
| `whatsapp_from_number` | character varying(32) | no |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_validated_at` | timestamp with time zone | yes |  |
| `api_key_sid` | character varying(64) | yes |  |
| `encrypted_api_key_secret` | text | yes |  |

- **Primary key:** `PRIMARY KEY (id)`

### `public.user_blacklist`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('user_blacklist_id_seq'::regclass)` |
| `tenant_id` | uuid | no |  |
| `user_id` | uuid | no |  |
| `reason` | character varying(100) | no |  |
| `blocked_by` | character varying(50) | no |  |
| `expires_at` | timestamp without time zone | no |  |
| `created_at` | timestamp without time zone | yes | `CURRENT_TIMESTAMP` |
| `created_by` | uuid | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_user_blacklist_lookup — btree (tenant_id, user_id, expires_at)`
- **Index:** `UNIQUE idx_user_blacklist_tenant_user_active — btree (tenant_id, user_id)`
- **Index:** `idx_user_blacklist_expires — btree (expires_at)`

### `public.user_tool_script`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `tool_name` | character varying(64) | no |  |
| `display_name` | character varying(128) | no |  |
| `description` | text | yes |  |
| `expression` | text | no |  |
| `input_schema_json` | text | yes |  |
| `enabled` | boolean | no | `true` |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (tool_name)`
- **Index:** `idx_user_tool_script_enabled — btree (enabled)`
- **Index:** `UNIQUE uq_user_tool_script_tool_name — btree (tool_name)`

### `public.version_label_event`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `entity_type` | character varying(16) | no |  |
| `entity_id` | uuid | no |  |
| `label` | character varying(32) | no |  |
| `version_id` | uuid | no |  |
| `action` | character varying(16) | no |  |
| `applied_by` | character varying(255) | yes |  |
| `detail` | character varying(500) | yes |  |
| `applied_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_version_label_event_entity — btree (entity_type, entity_id, applied_at DESC)`

### `public.video_generation_job`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `prompt` | text | no |  |
| `model` | character varying(128) | no |  |
| `duration_seconds` | integer | no |  |
| `provider_name` | character varying(32) | no |  |
| `status` | character varying(16) | no |  |
| `provider_operation_id` | character varying(512) | yes |  |
| `result_object_key` | character varying(512) | yes |  |
| `result_content_type` | character varying(64) | yes |  |
| `error_message` | text | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_video_generation_job_created_at — btree (created_at)`
- **Index:** `idx_video_generation_job_status — btree (status)`

### `public.voice_call_recording`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `agent_id` | uuid | no |  |
| `room_name` | character varying(255) | no |  |
| `egress_id` | character varying(128) | yes |  |
| `object_key` | character varying(512) | no |  |
| `status` | character varying(16) | no |  |
| `started_at` | timestamp with time zone | no |  |
| `ended_at` | timestamp with time zone | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_voice_call_recording_room_name — btree (room_name)`
- **Index:** `idx_voice_call_recording_agent_id — btree (agent_id)`

### `public.voice_dtmf_mapping`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `digit` | character varying(1) | no |  |
| `agent_id` | uuid | no |  |
| `label` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (digit)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `UNIQUE voice_dtmf_mapping_digit_key — btree (digit)`

### `public.voice_number_agent`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `phone_number` | character varying(16) | no |  |
| `agent_id` | uuid | no |  |
| `label` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (phone_number)`
- **Foreign keys:** `FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE`
- **Index:** `UNIQUE voice_number_agent_phone_number_key — btree (phone_number)`

### `public.wallet_ledger`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint | no | `nextval('wallet_ledger_id_seq'::regclass)` |
| `wallet_id` | uuid | no |  |
| `entry_kind` | character varying(8) | no |  |
| `amount_minor` | bigint | no |  |
| `balance_after` | bigint | no |  |
| `idempotency_key` | character varying(160) | no |  |
| `reference_type` | character varying(32) | yes |  |
| `reference_id` | character varying(128) | yes |  |
| `note` | text | yes |  |
| `created_by` | character varying(128) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (idempotency_key)`
- **Foreign keys:** `FOREIGN KEY (wallet_id) REFERENCES agent_wallet(id) ON DELETE CASCADE`
- **Index:** `idx_wallet_ledger_wallet — btree (wallet_id, id DESC)`
- **Index:** `idx_wallet_ledger_reference — btree (reference_type, reference_id) WHERE (reference_type IS NOT NULL)`
- **Index:** `UNIQUE wallet_ledger_idempotency_key_key — btree (idempotency_key)`

### `public.webhook_delivery`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `subscription_id` | uuid | no |  |
| `event_type` | character varying(200) | no |  |
| `run_id` | character varying(100) | yes |  |
| `payload_json` | text | no |  |
| `status` | character varying(16) | no | `'PENDING'::character varying` |
| `attempt_count` | integer | no | `0` |
| `next_attempt_at` | timestamp with time zone | no | `now()` |
| `response_code` | integer | yes |  |
| `last_error` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `delivered_at` | timestamp with time zone | yes |  |
| `duration_ms` | integer | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (subscription_id) REFERENCES webhook_subscription(id) ON DELETE CASCADE`
- **Index:** `webhook_delivery_pending_idx — btree (next_attempt_at) WHERE ((status)::text = 'PENDING'::text)`
- **Index:** `webhook_delivery_by_subscription_idx — btree (subscription_id, created_at DESC)`

### `public.webhook_subscription`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(120) | no |  |
| `url` | character varying(2048) | no |  |
| `secret_encrypted` | text | no |  |
| `event_types` | text | no |  |
| `enabled` | boolean | no | `true` |
| `consecutive_failures` | integer | no | `0` |
| `auto_disabled_reason` | character varying(300) | yes |  |
| `created_by` | character varying(200) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (id)`

### `public.whatsapp_opt_in`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `phone_digits` | character varying(32) | no |  |
| `source` | character varying(32) | no |  |
| `evidence` | text | yes |  |
| `consented_at` | timestamp with time zone | no |  |
| `revoked_at` | timestamp with time zone | yes |  |
| `recorded_by` | character varying(255) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (phone_digits)`
- **Index:** `UNIQUE uq_whatsapp_opt_in_phone — btree (phone_digits)`

### `public.whatsapp_qr_connection`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `session_key` | character varying(64) | no |  |
| `status` | character varying(16) | no |  |
| `phone_number` | character varying(32) | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (session_key)`
- **Index:** `UNIQUE uq_whatsapp_qr_connection_session_key — btree (session_key)`

### `public.whatsapp_recipient_backoff`

| Column | Type | Null | Default |
|---|---|---|---|
| `phone_digits` | character varying(32) | no |  |
| `blocked_until` | timestamp with time zone | yes |  |
| `permanent` | boolean | no | `false` |
| `meta_code` | integer | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (phone_digits)`

### `public.workflow_comment`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `workflow_id` | uuid | no |  |
| `parent_id` | uuid | yes |  |
| `node_id` | character varying(128) | yes |  |
| `anchor_x` | double precision | yes |  |
| `anchor_y` | double precision | yes |  |
| `body` | text | yes |  |
| `mentions` | text | yes |  |
| `author_id` | character varying(255) | no |  |
| `author_name` | character varying(255) | yes |  |
| `resolved` | boolean | no | `false` |
| `resolved_by` | character varying(255) | yes |  |
| `resolved_at` | timestamp with time zone | yes |  |
| `deleted_at` | timestamp with time zone | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `version` | bigint | no | `0` |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (parent_id) REFERENCES workflow_comment(id) ON DELETE CASCADE`
- **Foreign keys:** `FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id) ON DELETE CASCADE`
- **Index:** `idx_workflow_comment_parent — btree (parent_id)`
- **Index:** `idx_workflow_comment_workflow — btree (workflow_id, created_at)`

### `public.workflow_definition`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `name` | character varying(200) | no |  |
| `description` | text | yes |  |
| `graph_json` | text | no |  |
| `status` | character varying(32) | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `schedule_cron` | character varying(64) | yes |  |
| `schedule_enabled` | boolean | no | `false` |
| `next_run_at` | timestamp with time zone | yes |  |
| `created_by` | character varying(255) | yes |  |
| `reasoning_depth` | character varying(16) | no | `'FAST'::character varying` |
| `tuning_mode` | character varying(16) | no | `'MANUAL'::character varying` |
| `step_caching_enabled` | boolean | no | `false` |
| `cost_routing_enabled` | boolean | no | `false` |
| `economy_provider_name` | character varying(32) | yes |  |
| `economy_model` | character varying(128) | yes |  |
| `max_steps_per_run` | integer | no | `50` |
| `step_timeout_seconds` | integer | no | `30` |
| `max_retry_attempts` | integer | no | `2` |
| `version` | bigint | no | `0` |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_workflow_definition_due_schedule — btree (schedule_enabled, next_run_at) WHERE (schedule_enabled = true)`

### `public.workflow_event_outbox`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `event_type` | character varying(200) | no |  |
| `workflow_id` | uuid | yes |  |
| `run_id` | character varying(100) | no |  |
| `node_id` | character varying(100) | yes |  |
| `payload_json` | text | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `published_at` | timestamp with time zone | yes |  |
| `retry_count` | integer | no | `0` |
| `last_error` | text | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_workflow_event_outbox_unpublished — btree (published_at, retry_count) WHERE (published_at IS NULL)`

### `public.workflow_input_request`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `run_id` | uuid | no |  |
| `workflow_id` | uuid | no |  |
| `workflow_name` | character varying(255) | yes |  |
| `node_id` | character varying(100) | no |  |
| `node_label` | character varying(255) | yes |  |
| `form_schema` | jsonb | yes |  |
| `node_input` | text | yes |  |
| `status` | character varying(16) | no | `'PENDING'::character varying` |
| `requested_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `submitted_values` | jsonb | yes |  |
| `submitted_by` | character varying(255) | yes |  |
| `submitted_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_workflow_input_request_status — btree (status, created_at DESC)`
- **Index:** `idx_workflow_input_request_run — btree (run_id)`

### `public.workflow_node_pin`

| Column | Type | Null | Default |
|---|---|---|---|
| `workflow_id` | uuid | no |  |
| `node_id` | text | no |  |
| `data_text` | text | no |  |
| `size_chars` | integer | no |  |
| `source` | character varying(32) | no |  |
| `source_run_id` | character varying(64) | yes |  |
| `pinned_by` | character varying(200) | yes |  |
| `pinned_at` | timestamp with time zone | no | `now()` |
| `config_hash` | character varying(128) | yes |  |

- **Primary key:** `PRIMARY KEY (workflow_id, node_id)`
- **Foreign keys:** `FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id) ON DELETE CASCADE`

### `public.workflow_poll_trigger`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `workflow_id` | uuid | no |  |
| `node_id` | character varying(64) | no |  |
| `url` | character varying(4096) | no |  |
| `method` | character varying(8) | no | `'GET'::character varying` |
| `headers_encrypted` | text | yes |  |
| `interval_seconds` | integer | no | `300` |
| `items_path` | character varying(256) | no | `''::character varying` |
| `id_path` | character varying(256) | no | `'id'::character varying` |
| `seed_on_enable` | boolean | no | `true` |
| `state_json` | text | yes |  |
| `next_poll_at` | timestamp with time zone | no |  |
| `enabled` | boolean | no | `true` |
| `last_status` | character varying(16) | yes |  |
| `last_polled_at` | timestamp with time zone | yes |  |
| `consecutive_failures` | integer | no | `0` |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (node_id)`
- **Foreign keys:** `FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id) ON DELETE CASCADE`
- **Index:** `UNIQUE workflow_poll_trigger_node_id_key — btree (node_id)`
- **Index:** `workflow_poll_trigger_due — btree (enabled, next_poll_at)`

### `public.workflow_run`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `workflow_id` | uuid | no |  |
| `workflow_name` | character varying(200) | no |  |
| `status` | character varying(32) | no |  |
| `requested_by` | character varying(200) | yes |  |
| `trace_json` | text | yes |  |
| `error_message` | text | yes |  |
| `started_at` | timestamp with time zone | no | `now()` |
| `completed_at` | timestamp with time zone | yes |  |
| `output_file_name` | character varying(255) | yes |  |
| `output_data` | text | yes |  |
| `paused_node_id` | character varying(100) | yes |  |
| `paused_node_input` | text | yes |  |
| `workflow_version` | integer | yes |  |
| `run_mode` | character varying(16) | no | `'PRODUCTION'::character varying` |
| `run_comment` | character varying(2000) | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_workflow_run_status_started — btree (status, started_at)`
- **Index:** `idx_workflow_run_paused — btree (workflow_id) WHERE ((status)::text = 'PAUSED'::text)`
- **Index:** `idx_workflow_run_workflow_id — btree (workflow_id, started_at DESC)`

### `public.workflow_run_node_output`

| Column | Type | Null | Default |
|---|---|---|---|
| `run_id` | uuid | no |  |
| `node_id` | text | no |  |
| `seq` | integer | no | `0` |
| `node_label` | text | yes |  |
| `status` | text | yes |  |
| `output_text` | text | yes |  |
| `truncated` | boolean | no | `false` |
| `created_at` | timestamp with time zone | no | `now()` |

- **Primary key:** `PRIMARY KEY (run_id, node_id, seq)`
- **Foreign keys:** `FOREIGN KEY (run_id) REFERENCES workflow_run(id) ON DELETE CASCADE`
- **Index:** `idx_workflow_run_node_output_label — btree (run_id, node_label)`
- **Index:** `idx_workflow_run_node_output_created — btree (created_at)`

### `public.workflow_share`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `workflow_id` | uuid | no |  |
| `share_id` | character varying(64) | no |  |
| `revoked` | boolean | no | `false` |
| `report_count` | integer | no | `0` |
| `viewer_count` | bigint | no | `0` |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `expires_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (share_id)`
- **Unique:** `UNIQUE (workflow_id)`
- **Index:** `idx_workflow_share_share_id — btree (share_id)`
- **Index:** `UNIQUE uq_workflow_share_share_id — btree (share_id)`
- **Index:** `UNIQUE uq_workflow_share_workflow_id — btree (workflow_id)`

### `public.workflow_trigger_node`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `workflow_id` | uuid | no |  |
| `node_id` | character varying(64) | no |  |
| `channel` | character varying(16) | no |  |
| `webhook_token_encrypted` | character varying(255) | yes |  |
| `webhook_token_hash` | character varying(64) | yes |  |
| `webhook_enabled` | boolean | no | `false` |
| `webhook_auth_mode` | character varying(16) | no | `'SHARED_SECRET'::character varying` |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `webhook_signature_mode` | character varying(16) | no | `'NONE'::character varying` |
| `webhook_signature_secret_encrypted` | text | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Unique:** `UNIQUE (node_id)`
- **Unique:** `UNIQUE (webhook_token_hash)`
- **Foreign keys:** `FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id) ON DELETE CASCADE`
- **Index:** `UNIQUE workflow_trigger_node_node_id_key — btree (node_id)`
- **Index:** `UNIQUE workflow_trigger_node_channel_unique — btree (channel) WHERE ((channel)::text <> 'WEBHOOK'::text)`
- **Index:** `UNIQUE workflow_trigger_node_webhook_token_hash_key — btree (webhook_token_hash)`

### `public.workflow_version`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `workflow_id` | uuid | no |  |
| `version_number` | integer | no |  |
| `graph_json` | text | no |  |
| `workflow_name` | character varying(200) | no |  |
| `description` | text | yes |  |
| `status` | character varying(32) | no |  |
| `change_summary` | character varying(500) | yes |  |
| `created_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `bundle_hash` | character varying(64) | yes |  |
| `label` | character varying(32) | yes |  |
| `details_json` | text | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Foreign keys:** `FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id) ON DELETE CASCADE`
- **Index:** `idx_workflow_version_wf_number — btree (workflow_id, version_number DESC)`
- **Index:** `UNIQUE uq_workflow_version_wf_label — btree (workflow_id, label) WHERE (label IS NOT NULL)`
- **Index:** `idx_workflow_version_wf_created_at — btree (workflow_id, created_at DESC)`

### `public.workflow_voice_menu_request`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `run_id` | uuid | no |  |
| `workflow_id` | uuid | no |  |
| `workflow_name` | character varying(255) | yes |  |
| `node_id` | character varying(100) | no |  |
| `node_label` | character varying(255) | yes |  |
| `prompt_text` | text | yes |  |
| `invalid_prompt_text` | text | yes |  |
| `num_digits` | integer | yes |  |
| `finish_on_key` | character varying(4) | yes |  |
| `timeout_seconds` | integer | no | `5` |
| `max_retries` | integer | no | `2` |
| `attempt` | integer | no | `1` |
| `branches` | jsonb | yes |  |
| `node_input` | text | yes |  |
| `status` | character varying(16) | no | `'PENDING'::character varying` |
| `requested_by` | character varying(255) | yes |  |
| `created_at` | timestamp with time zone | no |  |
| `collected_digits` | character varying(64) | yes |  |
| `resolved_branch` | character varying(64) | yes |  |
| `resolved_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_workflow_voice_menu_request_status — btree (status, created_at DESC)`
- **Index:** `idx_workflow_voice_menu_request_run — btree (run_id, status)`

### `public.workflow_wait`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `run_id` | uuid | no |  |
| `workflow_id` | uuid | no |  |
| `node_id` | character varying(100) | no |  |
| `resume_node_id` | character varying(100) | no |  |
| `mode` | character varying(16) | no |  |
| `wake_at` | timestamp with time zone | no |  |
| `fail_on_timeout` | boolean | no | `true` |
| `status` | character varying(16) | no | `'WAITING'::character varying` |
| `created_at` | timestamp with time zone | no |  |
| `resolved_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
- **Index:** `idx_workflow_wait_run — btree (run_id)`
- **Index:** `UNIQUE uq_workflow_wait_run_live — btree (run_id) WHERE ((status)::text = 'WAITING'::text)`
- **Index:** `idx_workflow_wait_due — btree (wake_at) WHERE ((status)::text = 'WAITING'::text)`

### `public.x_credential`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no |  |
| `client_id` | character varying(255) | no |  |
| `encrypted_client_secret` | text | no |  |
| `x_user_id` | character varying(32) | yes |  |
| `x_username` | character varying(255) | yes |  |
| `encrypted_refresh_token` | text | yes |  |
| `last_dm_event_id` | character varying(32) | yes |  |
| `status` | character varying(16) | no |  |
| `created_at` | timestamp with time zone | no |  |
| `updated_at` | timestamp with time zone | no |  |
| `last_polled_at` | timestamp with time zone | yes |  |

- **Primary key:** `PRIMARY KEY (id)`
