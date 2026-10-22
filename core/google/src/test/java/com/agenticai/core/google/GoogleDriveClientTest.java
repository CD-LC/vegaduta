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
 * {@link GoogleDriveClient} request-mapping and error-translation tests on the injected stub
 * transport (see {@link HttpStubs}). The distinctive surface here is the two-step create (metadata
 * POST, then media-upload PATCH against a different host path) and the Drive query-string escaping
 * in {@code listFiles} - a name containing an apostrophe must be escaped before it lands inside
 * Drive's single-quoted query literal, or the query either 400s or (worse) silently changes
 * meaning.
 */
class GoogleDriveClientTest {

    private static final String TOKEN = "at-123";

    private GoogleDriveClient client;
    private HttpStubs.RecordingHttpClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        GoogleOAuthService oauthService = mock(GoogleOAuthService.class);
        when(oauthService.currentAccessToken()).thenReturn(TOKEN);
        client = new GoogleDriveClient(objectMapper, oauthService);
        http = HttpStubs.install(client);
    }

    // ---- create (upload) ----------------------------------------------------

    @Test
    void createTextFilePostsMetadataThenPatchesContentToTheUploadEndpoint() throws Exception {
        http.enqueue(
                200,
                "{\"id\":\"f-1\",\"name\":\"notes.txt\",\"mimeType\":\"text/plain\","
                        + "\"modifiedTime\":\"2026-08-07T10:00:00Z\"}");
        http.enqueue(200, "{}");

        GoogleDriveClient.DriveFile file = client.createTextFile("notes.txt", "hello drive");

        // Step 1: metadata-only create on the normal files endpoint.
        HttpRequest create = http.requests.get(0);
        assertEquals(
                "https://www.googleapis.com/drive/v3/files?fields=id%2Cname%2CmimeType%2CmodifiedTime",
                create.uri().toString());
        assertEquals("POST", create.method());
        assertEquals("Bearer " + TOKEN, create.headers().firstValue("Authorization").orElse(null));
        JsonNode metadata = objectMapper.readTree(HttpStubs.bodyOf(create));
        assertEquals("notes.txt", metadata.path("name").asText());
        assertTrue(
                metadata.path("parents").isMissingNode(),
                "no folderId means no parents field at all");

        // Step 2: content PATCH on the *upload* host path with uploadType=media.
        HttpRequest upload = http.requests.get(1);
        assertEquals(
                "https://www.googleapis.com/upload/drive/v3/files/f-1?uploadType=media",
                upload.uri().toString());
        assertEquals("PATCH", upload.method());
        assertEquals(
                "text/plain; charset=UTF-8",
                upload.headers().firstValue("Content-Type").orElse(null));
        assertEquals("hello drive", HttpStubs.bodyOf(upload));

        assertEquals(
                new GoogleDriveClient.DriveFile(
                        "f-1", "notes.txt", "text/plain", "2026-08-07T10:00:00Z"),
                file);
    }

    @Test
    void createTextFileWithFolderIdAddsAParentsArray() throws Exception {
        http.enqueue(200, "{\"id\":\"f-2\",\"name\":\"report.txt\"}");
        http.enqueue(200, "{}");

        client.createTextFile("report.txt", "content", "folder-9");

        JsonNode metadata = objectMapper.readTree(HttpStubs.bodyOf(http.requests.get(0)));
        assertEquals("folder-9", metadata.path("parents").get(0).asText());
    }

    @Test
    void uploadStepFailureNamesThePartialStateHonestly() {
        // The metadata create succeeded but the content write failed - the
        // message must admit the file exists but is empty, so the agent/user
        // knows what state Drive was left in.
        http.enqueue(200, "{\"id\":\"f-3\",\"name\":\"x.txt\"}");
        http.enqueue(403, "{\"error\":{\"message\":\"quota exceeded\"}}");

        GoogleApiException ex =
                assertThrows(
                        GoogleApiException.class, () -> client.createTextFile("x.txt", "content"));

        assertTrue(
                ex.getMessage().contains("Created the Drive file but could not write its content"));
        assertTrue(ex.getMessage().contains("(403)"));
        assertTrue(ex.getMessage().contains("quota exceeded"));
    }

    // ---- list + read --------------------------------------------------------

    @Test
    void listFilesClampsPageSizeAndEscapesApostrophesInTheNameQuery() {
        http.enqueue(
                200,
                "{\"files\":[{\"id\":\"f-1\",\"name\":\"bob's plan\",\"mimeType\":\"text/plain\","
                        + "\"modifiedTime\":\"2026-08-01T00:00:00Z\"}]}");

        List<GoogleDriveClient.DriveFile> files = client.listFiles("bob's", 100);

        // 100 clamps to 25; the apostrophe is backslash-escaped inside the
        // single-quoted Drive query literal, then the whole q value is
        // percent-encoded (space -> '+', ' -> %27, \ -> %5C).
        assertEquals(
                "https://www.googleapis.com/drive/v3/files?pageSize=25"
                        + "&fields=files%28id%2Cname%2CmimeType%2CmodifiedTime%29"
                        + "&q=name+contains+%27bob%5C%27s%27",
                http.onlyRequest().uri().toString());
        assertEquals(
                List.of(
                        new GoogleDriveClient.DriveFile(
                                "f-1", "bob's plan", "text/plain", "2026-08-01T00:00:00Z")),
                files);
    }

    @Test
    void readFileUsesAltMediaAndReturnsTheRawBody() {
        http.enqueue(200, "raw file contents, not JSON");

        String content = client.readFile("f-1");

        assertEquals(
                "https://www.googleapis.com/drive/v3/files/f-1?alt=media",
                http.onlyRequest().uri().toString());
        assertEquals("raw file contents, not JSON", content);
    }

    @Test
    void driveErrorTranslatesToGoogleApiExceptionWithStatusAndBody() {
        // 404 is the drive.file-scope signature failure: the file exists but
        // this app didn't create/open it, so Drive pretends it doesn't exist.
        http.enqueue(404, "{\"error\":{\"message\":\"File not found: f-9\"}}");

        GoogleApiException ex =
                assertThrows(GoogleApiException.class, () -> client.readFile("f-9"));

        assertTrue(ex.getMessage().contains("Could not read Drive file f-9"));
        assertTrue(ex.getMessage().contains("(404)"));
        assertTrue(ex.getMessage().contains("File not found"));
    }
}
