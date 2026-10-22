package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Zero test coverage existed for {@link TenantDataExportService} before this - the class behind
 * {@code POST /api/tenant/data-export}, which hands a tenant-admin a full JSON dump of "their"
 * data. Two ways it is risk-bearing. First, WHICH tenant gets dumped is derived solely from {@link
 * TenantContext} - there is deliberately no schema/tenant parameter anywhere a request body could
 * smuggle one in through, and these tests pin that a missing context (or a context naming a schema
 * with no Tenant row) is a hard failure, never a fallback to some default schema's data. Second,
 * the {@code EXPORT_TABLES} allowlist IS the security control: the class javadoc documents that
 * auto-discovering tables would ship every {@code *_credential} table's encrypted provider secrets
 * out the door in a customer-downloadable file. The allowlist-invariant test below exists to fail
 * loudly the day someone "helpfully" adds a table - forcing them to re-read that javadoc before
 * widening the export surface.
 *
 * <p>Same idiom as {@link TenantDataErasureServiceTest}: real {@link TenantContext} static holder
 * with an {@code @AfterEach} clear, JDBC mocked at the
 * DataSource-&gt;Connection-&gt;PreparedStatement-&gt;ResultSet boundary, a real {@link
 * ObjectMapper} (serialization is part of the behaviour under test). {@link Tenant} is the one mock
 * that deviates: a real {@code new Tenant(...)} has a null JPA-generated id until persisted, and
 * this service reads {@code tenant.getId()} to build the object-storage key - a mock supplies a
 * stable id without needing a database.
 */
class TenantDataExportServiceTest {

    private static final String SCHEMA_NAME = "acme";
    private static final UUID TENANT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /**
     * The exact allowlist {@link TenantDataExportService} ships today, duplicated here ON PURPOSE
     * (not read via reflection): the duplication is what makes adding a table to the export a
     * two-file change that forces whoever does it past this class's javadoc - and past the main
     * class's own DELIBERATELY EXCLUDED comment - before the suite goes green again.
     */
    private static final List<String> EXPECTED_EXPORT_TABLES =
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

    private TenantRepository tenantRepository;
    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;
    private ResultSetMetaData metaData;
    private FileStorageService fileStorageService;
    private AuditLogService auditLogService;
    private TenantDataExportService service;

    @BeforeEach
    void setUp() throws SQLException {
        tenantRepository = mock(TenantRepository.class);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        preparedStatement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        metaData = mock(ResultSetMetaData.class);
        fileStorageService = mock(FileStorageService.class);
        auditLogService = mock(AuditLogService.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnLabel(1)).thenReturn("id");
        // Empty tables by default - individual tests feed rows where needed.
        when(resultSet.next()).thenReturn(false);

        // Mocked Tenant (see class javadoc): only for the stable non-null id.
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(TENANT_ID);
        when(tenant.getName()).thenReturn("Acme");
        when(tenant.getSchemaName()).thenReturn(SCHEMA_NAME);
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(tenant));

        service =
                new TenantDataExportService(
                        tenantRepository,
                        dataSource,
                        fileStorageService,
                        auditLogService,
                        com.agenticai.core.json.JsonMappers.plain());

        TenantContext.set(TenantContext.of(SCHEMA_NAME, "user-123", Set.of("tenant-admin")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Schema is TenantContext-derived, and only TenantContext-derived
    // ------------------------------------------------------------------

    @Test
    void everyDumpedTableIsQualifiedWithTheTenantContextsResolvedSchemaInAllowlistOrder()
            throws SQLException {
        service.exportCurrentTenantData();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, times(EXPECTED_EXPORT_TABLES.size()))
                .prepareStatement(sqlCaptor.capture());
        // Exact SQL, quoted identifiers, ONLY the context's schema - a bare
        // unqualified "SELECT * FROM agent" would dump whatever schema the
        // connection's search_path happened to point at, i.e. potentially
        // another tenant's rows.
        List<String> expectedSql =
                EXPECTED_EXPORT_TABLES.stream()
                        .map(table -> "SELECT * FROM \"" + SCHEMA_NAME + "\".\"" + table + "\"")
                        .toList();
        assertEquals(expectedSql, sqlCaptor.getAllValues());
    }

    @Test
    void missingTenantContextIsAHardFailureThatTouchesNeitherTheDatabaseNorObjectStorage()
            throws SQLException {
        TenantContext.clear();

        assertThrows(IllegalStateException.class, service::exportCurrentTenantData);

        // Failing closed means NOTHING ran - no fallback-schema dump got
        // built and certainly nothing landed in object storage.
        verify(dataSource, never()).getConnection();
        verify(fileStorageService, never()).put(anyString(), any(), anyString());
    }

    @Test
    void aTenantContextWithNoMatchingTenantRowIsAHardFailureNotAnEmptyExport() {
        // A schema name with no control_plane row behind it is a broken (or
        // forged) context, not a tenant with no data - refuse, don't guess.
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, service::exportCurrentTenantData);

        verify(fileStorageService, never()).put(anyString(), any(), anyString());
    }

    // ------------------------------------------------------------------
    // The allowlist itself is the security control
    // ------------------------------------------------------------------

    @Test
    void theExportAllowlistNeverContainsCredentialTablesOrTheExplicitlyExcludedOnes()
            throws SQLException {
        service.exportCurrentTenantData();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, times(EXPECTED_EXPORT_TABLES.size()))
                .prepareStatement(sqlCaptor.capture());
        // Recover the dumped table names from the observable SQL rather than
        // reflecting on the private constant - what actually reaches the
        // database is the behaviour that matters.
        List<String> dumpedTables =
                sqlCaptor.getAllValues().stream()
                        .map(sql -> sql.substring(sql.lastIndexOf(".\"") + 2, sql.length() - 1))
                        .toList();

        // Exact pin first: ANY addition or removal fails here, forcing a
        // deliberate two-file change (see EXPECTED_EXPORT_TABLES's comment).
        assertEquals(EXPECTED_EXPORT_TABLES, dumpedTables);
        // Then the specific invariants the class javadoc promises, spelled
        // out so a failure names the actual rule broken, not just "lists
        // differ": no *_credential table (encrypted provider/channel
        // secrets) may EVER leave in a customer-downloadable export...
        assertTrue(
                dumpedTables.stream().noneMatch(table -> table.endsWith("_credential")),
                "a *_credential table reached the data export - encrypted provider secrets would leave the system");
        // ...and the three excluded-by-name tables stay excluded.
        assertFalse(dumpedTables.contains("knowledge_chunks"));
        assertFalse(dumpedTables.contains("llm_call_log"));
        assertFalse(dumpedTables.contains("ocr_provider_config"));
    }

    // ------------------------------------------------------------------
    // Partial-failure and output-shape behaviour
    // ------------------------------------------------------------------

    @Test
    void aTableThatThrowsSqlExceptionContributesAnEmptyListInsteadOfAbortingTheWholeExport()
            throws Exception {
        // "agent" (the first table) is missing/broken; every later table
        // must still be dumped - same one-bad-part-doesn't-sink-everything
        // posture TenantSchemaIterator uses per-tenant, here per-table.
        when(connection.prepareStatement(anyString()))
                .thenAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0, String.class);
                            if (sql.equals("SELECT * FROM \"" + SCHEMA_NAME + "\".\"agent\"")) {
                                throw new SQLException("relation does not exist");
                            }
                            return preparedStatement;
                        });
        // One row, consumed by the first table that queries successfully
        // (prompt_version, since agent threw) - the same shared-single-
        // ResultSet trade-off TenantDataErasureServiceTest documents.
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn("row-1");

        TenantDataExportService.ExportResult result = service.exportCurrentTenantData();

        JsonNode tables =
                com.agenticai.core.json.JsonMappers.plain().readTree(result.bytes()).path("tables");
        assertTrue(tables.path("agent").isArray());
        assertEquals(0, tables.path("agent").size());
        // The export carried on past the failure and kept real data.
        assertEquals("row-1", tables.path("prompt_version").get(0).path("id").asString());
        assertEquals("tenant-data-export-" + SCHEMA_NAME + ".json", result.filename());
    }

    @Test
    void theDurableCopyIsNamespacedUnderThisTenantsOwnExportPrefixAndAudited() {
        service.exportCurrentTenantData();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileStorageService).put(keyCaptor.capture(), any(), eq("application/json"));
        // tenant-exports/<tenantId>/ is the namespace contract - a key
        // missing the tenant id would pile every tenant's exports into one
        // shared prefix (and make per-tenant cleanup at erasure time
        // impossible to scope).
        assertTrue(keyCaptor.getValue().startsWith("tenant-exports/" + TENANT_ID + "/"));
        assertTrue(keyCaptor.getValue().endsWith(".json"));

        // A data export is itself a privacy-relevant event - the audit entry
        // must name the acting subject and the tenant it exported.
        verify(auditLogService)
                .record(
                        eq("user-123"),
                        eq("tenant.data_export.request"),
                        eq("tenant"),
                        eq(TENANT_ID.toString()),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
    }
}
