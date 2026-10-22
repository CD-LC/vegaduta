package com.agenticai.core.google;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Google Sheets v4, scoped to {@code spreadsheets} - full read/write, since Sheets has no narrower
 * "only sheets this app created" scope the way Drive does.
 */
@Component
public class GoogleSheetsClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String BASE = "https://sheets.googleapis.com/v4/spreadsheets";

    private final HttpClient httpClient = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;
    private final GoogleOAuthService oauthService;

    public GoogleSheetsClient(ObjectMapper objectMapper, GoogleOAuthService oauthService) {
        this.objectMapper = objectMapper;
        this.oauthService = oauthService;
    }

    /**
     * @param range A1 notation, e.g. "Sheet1!A1:C10".
     */
    public List<List<String>> readRange(String spreadsheetId, String range) {
        String uri = BASE + "/" + urlEncode(spreadsheetId) + "/values/" + urlEncode(range);
        HttpRequest request = authedRequest(uri).GET().build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= 300) {
            throw new GoogleApiException(
                    "Could not read spreadsheet range ("
                            + response.statusCode()
                            + "): "
                            + response.body());
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not parse Google Sheets API response: " + e.getMessage(), e);
        }
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : body.path("values")) {
            List<String> cells = new ArrayList<>();
            for (JsonNode cell : row) {
                cells.add(cell.asString(""));
            }
            rows.add(cells);
        }
        return rows;
    }

    /**
     * @param values row-major - values.get(0) is the first row written starting at the top-left
     *     cell of range.
     */
    public void writeRange(String spreadsheetId, String range, List<List<String>> values) {
        String uri =
                BASE
                        + "/"
                        + urlEncode(spreadsheetId)
                        + "/values/"
                        + urlEncode(range)
                        + "?valueInputOption=USER_ENTERED";
        try {
            String payload =
                    objectMapper.writeValueAsString(Map.of("range", range, "values", values));
            HttpRequest request =
                    authedRequest(uri)
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(payload))
                            .build();
            HttpResponse<String> response = send(request);
            if (response.statusCode() >= 300) {
                throw new GoogleApiException(
                        "Could not write spreadsheet range ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
        } catch (GoogleApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GoogleApiException("Could not write spreadsheet range: " + e.getMessage(), e);
        }
    }

    /**
     * Appends rows after the last row of the table found in {@code range} ({@code values:append},
     * {@code insertDataOption=INSERT_ROWS}, so existing rows below are pushed down, never
     * overwritten).
     *
     * <p>{@code valueInputOption=RAW}, deliberately unlike {@link #writeRange}: appended rows
     * usually carry text a customer typed (a name, a message, a phone number), and in {@code
     * USER_ENTERED} mode a cell such as {@code =IMPORTXML(...)} or {@code =HYPERLINK(...)} would
     * run as a formula in the owner's spreadsheet (CSV/formula injection). RAW stores every value
     * as literal text.
     *
     * @param range A1 notation naming the table, e.g. "Leads!A:F" or just "Leads"
     */
    public AppendResult appendRows(String spreadsheetId, String range, List<List<String>> values) {
        String uri =
                BASE
                        + "/"
                        + urlEncode(spreadsheetId)
                        + "/values/"
                        + urlEncode(range)
                        + ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS";
        try {
            String payload = objectMapper.writeValueAsString(Map.of("values", values));
            HttpRequest request =
                    authedRequest(uri)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build();
            HttpResponse<String> response = send(request);
            if (response.statusCode() >= 300) {
                throw new GoogleApiException(
                        "Could not append spreadsheet rows ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            JsonNode updates = objectMapper.readTree(response.body()).path("updates");
            return new AppendResult(
                    updates.path("updatedRange").asString(null),
                    updates.path("updatedRows").asInt(0));
        } catch (GoogleApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GoogleApiException("Could not append spreadsheet rows: " + e.getMessage(), e);
        }
    }

    /** Where the rows landed, e.g. {@code "Leads!A12:F13"}, and how many were written. */
    public record AppendResult(String updatedRange, int updatedRows) {}

    private HttpRequest.Builder authedRequest(String uri) {
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + oauthService.currentAccessToken());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not reach the Google Sheets API: " + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
