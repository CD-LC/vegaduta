package com.agenticai.core.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link GoogleSheetsClient} request-mapping and error-translation tests on the injected stub
 * transport (see {@link HttpStubs}). The subtle mapping risk here is the A1-notation range living
 * in the URL *path*: "Sheet1!A1:C10" contains '!' and ':' which must be percent-encoded, and the
 * write path must keep {@code valueInputOption=USER_ENTERED} (dropping it flips Google to RAW mode
 * and formulas/dates stop being interpreted - a silent behavior change, not an error).
 */
class GoogleSheetsClientTest {

    private static final String TOKEN = "at-123";

    private GoogleSheetsClient client;
    private HttpStubs.RecordingHttpClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        GoogleOAuthService oauthService = mock(GoogleOAuthService.class);
        when(oauthService.currentAccessToken()).thenReturn(TOKEN);
        client = new GoogleSheetsClient(objectMapper, oauthService);
        http = HttpStubs.install(client);
    }

    @Test
    void readRangeEncodesA1NotationAndStringifiesEveryCell() {
        // Numeric and boolean cells come back as JSON scalars - the client's
        // contract is all-strings rows, so 42 must become "42".
        http.enqueue(
                200,
                "{\"range\":\"Sheet1!A1:B2\",\"values\":[[\"Name\",\"Score\"],[\"asha\",42]]}");

        List<List<String>> rows = client.readRange("sheet-id-1", "Sheet1!A1:B2");

        HttpRequest request = http.onlyRequest();
        assertEquals(
                "https://sheets.googleapis.com/v4/spreadsheets/sheet-id-1/values/Sheet1%21A1%3AB2",
                request.uri().toString());
        assertEquals("Bearer " + TOKEN, request.headers().firstValue("Authorization").orElse(null));
        assertEquals(List.of(List.of("Name", "Score"), List.of("asha", "42")), rows);
    }

    @Test
    void readRangeOfAnEmptyRangeIsAnEmptyListNotAnError() {
        // Sheets omits "values" entirely for an empty range - path() handles
        // the absence, and callers get a clean empty result.
        http.enqueue(200, "{\"range\":\"Sheet1!A1:B2\"}");

        assertEquals(List.of(), client.readRange("sheet-id-1", "Sheet1!A1:B2"));
    }

    @Test
    void writeRangePutsUserEnteredValuesWithRangeAndRowMajorBody() throws Exception {
        http.enqueue(200, "{\"updatedCells\":4}");

        client.writeRange("sheet-id-1", "Sheet1!A1", List.of(List.of("a", "b"), List.of("c", "d")));

        HttpRequest request = http.onlyRequest();
        assertEquals(
                "https://sheets.googleapis.com/v4/spreadsheets/sheet-id-1/values/Sheet1%21A1?valueInputOption=USER_ENTERED",
                request.uri().toString());
        assertEquals("PUT", request.method());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(null));
        JsonNode body = objectMapper.readTree(HttpStubs.bodyOf(request));
        assertEquals("Sheet1!A1", body.path("range").asText());
        assertEquals("a", body.path("values").get(0).get(0).asText());
        assertEquals("d", body.path("values").get(1).get(1).asText());
    }

    @Test
    void readErrorTranslatesToGoogleApiExceptionWithStatusAndBody() {
        http.enqueue(
                403,
                "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"The caller does not have permission\"}}");

        GoogleApiException ex =
                assertThrows(
                        GoogleApiException.class,
                        () -> client.readRange("sheet-id-1", "Sheet1!A1"));

        assertTrue(ex.getMessage().contains("Could not read spreadsheet range (403)"));
        assertTrue(ex.getMessage().contains("does not have permission"));
    }

    @Test
    void writeErrorTranslatesToGoogleApiExceptionWithStatusAndBody() {
        http.enqueue(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");

        GoogleApiException ex =
                assertThrows(
                        GoogleApiException.class,
                        () -> client.writeRange("sheet-id-1", "Sheet1!A1", List.of(List.of("x"))));

        assertTrue(ex.getMessage().contains("Could not write spreadsheet range (429)"));
        assertTrue(ex.getMessage().contains("RESOURCE_EXHAUSTED"));
    }

    @Test
    void appendRowsInsertsAfterTheTableAsRawTextSoFormulasNeverRun() throws Exception {
        // Response shape:
        // https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets.values/append
        http.enqueue(
                200,
                "{\"spreadsheetId\":\"sheet-id-1\",\"tableRange\":\"Leads!A1:C11\","
                        + "\"updates\":{\"updatedRange\":\"Leads!A12:C12\",\"updatedRows\":1,\"updatedCells\":3}}");

        GoogleSheetsClient.AppendResult result =
                client.appendRows(
                        "sheet-id-1",
                        "Leads!A:C",
                        List.of(List.of("Asha", "+91 90000 00000", "=IMPORTXML(\"x\",\"y\")")));

        HttpRequest request = http.onlyRequest();
        assertEquals("POST", request.method());
        assertEquals(
                "https://sheets.googleapis.com/v4/spreadsheets/sheet-id-1/values/Leads%21A%3AC:append"
                        + "?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
                request.uri().toString());
        JsonNode body = objectMapper.readTree(HttpStubs.bodyOf(request));
        assertEquals("=IMPORTXML(\"x\",\"y\")", body.path("values").get(0).get(2).asText());
        assertEquals(new GoogleSheetsClient.AppendResult("Leads!A12:C12", 1), result);
    }

    @Test
    void appendRowsErrorTranslatesToGoogleApiException() {
        http.enqueue(400, "{\"error\":{\"message\":\"Unable to parse range: Nope\"}}");

        GoogleApiException ex =
                assertThrows(
                        GoogleApiException.class,
                        () -> client.appendRows("sheet-id-1", "Nope", List.of(List.of("a"))));

        assertTrue(ex.getMessage().contains("Could not append spreadsheet rows (400)"));
    }
}
