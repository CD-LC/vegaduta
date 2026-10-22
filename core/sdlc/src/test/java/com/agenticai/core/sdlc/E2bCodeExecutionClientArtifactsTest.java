package com.agenticai.core.sdlc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.sdlc.dto.SandboxArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class E2bCodeExecutionClientArtifactsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void decodesWellFormedArtifactsAndSkipsBrokenOnes() throws Exception {
        String ok = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        List<SandboxArtifact> out =
                E2bCodeExecutionClient.decodeArtifacts(
                        MAPPER.readTree(
                                """
            [{"name":"chart.png","mimeType":"image/png","contentBase64":"%s"},
             {"name":"","mimeType":"image/png","contentBase64":"%s"},
             {"name":"empty.csv","mimeType":"text/csv","contentBase64":""},
             {"name":"bad.png","mimeType":"image/png","contentBase64":"***not base64***"}]
            """
                                        .formatted(ok, ok)));
        assertEquals(1, out.size());
        assertEquals("chart.png", out.get(0).name());
        assertEquals("image/png", out.get(0).mimeType());
        assertArrayEquals(new byte[] {1, 2, 3}, out.get(0).content());
    }

    private static String artifact(String name, int bytes) {
        return "{\"name\":\""
                + name
                + "\",\"mimeType\":\"image/png\",\"contentBase64\":\""
                + Base64.getEncoder().encodeToString(new byte[bytes])
                + "\"}";
    }

    @Test
    void anArtifactOverThePerFileCapIsDroppedButSmallOnesSurvive() throws Exception {
        String json =
                "["
                        + artifact("huge.png", E2bCodeExecutionClient.MAX_ARTIFACT_BYTES + 1)
                        + ","
                        + artifact("ok.png", 10)
                        + "]";
        List<SandboxArtifact> out = E2bCodeExecutionClient.decodeArtifacts(MAPPER.readTree(json));
        assertEquals(1, out.size());
        assertEquals("ok.png", out.get(0).name());
    }

    @Test
    void anArtifactExactlyAtThePerFileCapIsKept() throws Exception {
        List<SandboxArtifact> out =
                E2bCodeExecutionClient.decodeArtifacts(
                        MAPPER.readTree(
                                "["
                                        + artifact(
                                                "edge.png",
                                                E2bCodeExecutionClient.MAX_ARTIFACT_BYTES)
                                        + "]"));
        assertEquals(1, out.size());
    }

    @Test
    void theCountAndTotalCapsAreEnforced() throws Exception {
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 12; i++) {
            many.append(i > 0 ? "," : "").append(artifact("f" + i + ".png", 5));
        }
        many.append("]");
        assertEquals(
                E2bCodeExecutionClient.MAX_ARTIFACTS,
                E2bCodeExecutionClient.decodeArtifacts(MAPPER.readTree(many.toString())).size());

        // three 5 MB files fit under the 12 MB total only twice
        String big = artifact("a.png", E2bCodeExecutionClient.MAX_ARTIFACT_BYTES);
        List<SandboxArtifact> out =
                E2bCodeExecutionClient.decodeArtifacts(
                        MAPPER.readTree("[" + big + "," + big + "," + big + "]"));
        assertEquals(2, out.size());
    }

    @Test
    void anOldTemplateWithNoArtifactsFieldYieldsAnEmptyList() throws Exception {
        assertTrue(
                E2bCodeExecutionClient.decodeArtifacts(
                                MAPPER.readTree("{\"stdout\":\"hi\"}").path("artifacts"))
                        .isEmpty());
        assertTrue(E2bCodeExecutionClient.decodeArtifacts(null).isEmpty());
    }
}
