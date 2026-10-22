package com.agenticai.core.tenancy;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Master Roadmap 2026 item #22 (GDPR/data-residency basics, §G) - {@code POST
 * /api/tenant/data-export}'s implementation: a tenant-admin's own self-service "give me a copy of
 * our data" request.
 *
 * <p><b>Enumeration strategy.</b> {@link TenantSchemaIterator} (Autonomy/ Guardrails Strategy Stage
 * C) iterates TENANTS, not tables within one tenant's schema - it doesn't help here. There is no
 * other existing "list every table in a tenant's schema" utility in this codebase, and building the
 * export by blindly enumerating {@code information_schema.tables} for the current schema and
 * dumping every table found would be actively wrong for this use case: several tenant-schema tables
 * hold encrypted provider/channel credentials (e.g. {@code twilio_credential}, {@code
 * meta_whatsapp_credential}, {@code slack_credential}, {@code e2b_credential}, {@code
 * ocr_provider_config}, and every other {@code *_credential} table) that must never leave this
 * system in a data export. {@link #EXPORT_TABLES} is therefore a deliberately curated allowlist,
 * not an auto-discovered one - see that constant's own comment for exactly what's included/excluded
 * and why.
 *
 * <p><b>Raw JDBC, not each table's own JPA repository.</b> Pulling in a repository per table would
 * mean this module (or a new one) taking a compile-time Maven dependency on {@code core.agents},
 * {@code core.workflow}, {@code core.knowledge}, {@code core.omnichannel}, and {@code core.audit}
 * all at once - a much heavier and more cycle-prone change than this "basics" pass calls for.
 * Instead this reuses the exact technique {@link TenantProvisioningService#compensate} and {@link
 * TenantSchemaResolver}'s own javadoc already establish for schema-level operations: a raw {@link
 * Connection} from the shared {@link DataSource}, schema-qualified table names built from the
 * tenant's own {@code schemaName} (already sanitized at provisioning time - see {@link
 * SchemaNameSanitizer} - so this is not raw, unsanitized user input reaching SQL text the way a
 * request body value would be).
 *
 * <p>Every column value is read via {@link ResultSet#getString} rather than {@link
 * ResultSet#getObject} - this trades exact JSON typing (numbers/ booleans come back as strings) for
 * guaranteed serializability: several exported tables have {@code jsonb} or array columns whose
 * native JDBC object type ({@code org.postgresql.util.PGobject}, {@code java.sql.Array}) Jackson
 * cannot serialize out of the box. For a "here is your data" export this is the right trade - a
 * completely correct dump beats a partially-typed one that throws on the first jsonb column it
 * meets.
 */
@Service
public class TenantDataExportService {

    private static final Logger log = LoggerFactory.getLogger(TenantDataExportService.class);

    /**
     * Genuinely "your personal/business data" tables, in the order they appear in the export.
     *
     * <p>INCLUDED: {@code agent} (configs - no secrets live on this row, provider API keys are on
     * separate {@code *_credential} rows, never exported), {@code prompt_version} (prompt history),
     * {@code user_tool_script} (a tenant's own authored tool code - business content, not platform
     * config), {@code workflow_definition}/{@code workflow_run} (workflow graphs and execution
     * history), {@code knowledge_collection}/{@code knowledge_document} (knowledge-base metadata,
     * including the object-storage key the original uploaded file lives under), {@code
     * channel_session}/{@code channel_message_log} (omnichannel conversation history with real end
     * customers), {@code audit_log_entry} (this tenant's own audit trail), {@code study_plan}
     * (members' tutor study plans: topics, generated lessons, quiz progress and review deck - user
     * content, no secrets), {@code imported_chat} (conversations members brought in from ChatGPT /
     * Claude: read-only transcripts, attachment names only, no secrets).
     *
     * <p>DELIBERATELY EXCLUDED: every {@code *_credential} table (encrypted provider/channel API
     * keys and tokens - twilio, meta- whatsapp, meta-social, slack, discord, telegram, email, x,
     * e2b, sarvam, elevenlabs) and {@code ocr_provider_config} - secrets, never exported, full
     * stop. {@code knowledge_chunks} - a search-index derivative of {@code knowledge_document}
     * (chunked + embedded text), redundant with the original document the export already references
     * by its object-storage key. {@code llm_call_log} - operational/cost- tracking telemetry, not
     * user-authored content. {@code channel_default_agent}/{@code channel_workflow_trigger} -
     * routing config, not data. {@code tool_call_log} - operational debugging detail, judged out of
     * scope for this "basics" pass alongside {@code llm_call_log} for the same reason.
     */
    private static final List<String> EXPORT_TABLES =
            List.of(
                    "agent",
                    "prompt_version",
                    "user_tool_script",
                    "workflow_definition",
                    "workflow_run",
                    "knowledge_collection",
                    "knowledge_document",
                    "channel_session",
                    "channel_message_log",
                    "audit_log_entry",
                    "study_plan",
                    "imported_chat");

    private final TenantRepository tenantRepository;
    private final DataSource dataSource;
    private final FileStorageService fileStorageService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public TenantDataExportService(
            TenantRepository tenantRepository,
            DataSource dataSource,
            FileStorageService fileStorageService,
            AuditLogService auditLogService,
            ObjectMapper objectMapper) {
        this.tenantRepository = tenantRepository;
        this.dataSource = dataSource;
        this.fileStorageService = fileStorageService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the export for whichever tenant {@link TenantContext} names, writes a durable copy to
     * {@link FileStorageService} (same object storage {@code SarvamGeneratedAudioService}/{@code
     * KnowledgeDocumentService} already use, under its own {@code tenant-exports/} key prefix), and
     * returns the same bytes so the caller (the controller) can hand them straight back as an
     * immediate download - no separate "fetch my export" round trip needed for a "basics" pass.
     */
    public ExportResult exportCurrentTenantData() {
        Tenant tenant = requireCurrentTenant();

        Map<String, Object> tables = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String table : EXPORT_TABLES) {
                tables.put(table, dumpTable(connection, tenant.getSchemaName(), table));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not connect to the database to build a data export for tenant '"
                            + tenant.getName()
                            + "'",
                    e);
        }

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exportedAt", Instant.now().toString());
        export.put("tenantId", tenant.getId().toString());
        export.put("organizationName", tenant.getName());
        export.put("tables", tables);

        byte[] json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(export);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not serialize the data export for tenant '" + tenant.getName() + "'", e);
        }

        String objectKey =
                "tenant-exports/" + tenant.getId() + "/" + Instant.now().toEpochMilli() + ".json";
        fileStorageService.put(objectKey, json, "application/json");

        auditLogService.record(
                actorId(),
                "tenant.data_export.request",
                "tenant",
                tenant.getId().toString(),
                AuditOutcome.SUCCESS,
                "Exported "
                        + EXPORT_TABLES.size()
                        + " table(s) to object storage key "
                        + objectKey);

        return new ExportResult(json, "tenant-data-export-" + tenant.getSchemaName() + ".json");
    }

    /**
     * One table's full contents as a list of column-name-to-string-value maps. A table missing
     * entirely from an older/partially-migrated schema (or simply never used by this tenant) logs a
     * warning and contributes an empty list rather than failing the whole export - same "one bad
     * part doesn't sink everything" posture {@link TenantSchemaIterator#forEachActiveTenantSchema}
     * already uses for its per-tenant loop, applied here per-table instead of per-tenant.
     */
    private List<Map<String, String>> dumpTable(
            Connection connection, String schemaName, String table) {
        String sql = "SELECT * FROM " + quoteIdentifier(schemaName) + "." + quoteIdentifier(table);
        List<Map<String, String>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            while (resultSet.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnLabel(i), resultSet.getString(i));
                }
                rows.add(row);
            }
        } catch (SQLException e) {
            log.warn(
                    "Skipping table '{}' in schema '{}' for a data export - {}",
                    table,
                    schemaName,
                    e.getMessage());
            return List.of();
        }
        return rows;
    }

    private Tenant requireCurrentTenant() {
        String schemaName =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No TenantContext for an authenticated /api/tenant/data-export request - SecurityConfig should have blocked this"));
        return tenantRepository
                .findBySchemaName(schemaName)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No Tenant row for schema '" + schemaName + "'"));
    }

    private String actorId() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    /**
     * Schema/table names only, never a value - validated and quoted by {@link
     * TenantSchemaGuard#quoteIdentifier} (the one choke point), so nothing but a plain lowercase
     * identifier (the shape every tenant schema has) reaches SQL text.
     */
    private static String quoteIdentifier(String identifier) {
        return TenantSchemaGuard.quoteIdentifier(identifier);
    }

    /**
     * {@code bytes} is the same JSON payload just written to {@link FileStorageService} - the
     * controller streams it straight back as the download.
     */
    public record ExportResult(byte[] bytes, String filename) {}
}
