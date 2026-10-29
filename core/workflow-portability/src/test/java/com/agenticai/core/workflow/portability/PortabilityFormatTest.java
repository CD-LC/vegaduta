package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Format detection for an uploaded workflow file: only distinctive markers decide, and an
 * ambiguous document returns empty so the caller asks the person to choose rather than
 * mis-converting.
 */
class PortabilityFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String singleQuoted) throws Exception {
        return MAPPER.readTree(singleQuoted.replace('\'', '"'));
    }

    @Test
    void wireNamesAndParse() {
        assertThat(PortabilityFormat.VEGADUTA.wire()).isEqualTo("vegaduta");
        assertThat(PortabilityFormat.N8N.wire()).isEqualTo("n8n");
        assertThat(PortabilityFormat.DIFY.wire()).isEqualTo("dify");
        assertThat(PortabilityFormat.parse("n8n")).contains(PortabilityFormat.N8N);
        assertThat(PortabilityFormat.parse(" DIFY ")).contains(PortabilityFormat.DIFY);
        assertThat(PortabilityFormat.parse("vegaduta")).contains(PortabilityFormat.VEGADUTA);
        assertThat(PortabilityFormat.parse("bogus")).isEmpty();
        assertThat(PortabilityFormat.parse(null)).isEmpty();
    }

    @Test
    void isAutoTreatsBlankAndAutoTheSame() {
        assertThat(PortabilityFormat.isAuto(null)).isTrue();
        assertThat(PortabilityFormat.isAuto("  ")).isTrue();
        assertThat(PortabilityFormat.isAuto("auto")).isTrue();
        assertThat(PortabilityFormat.isAuto(" AUTO ")).isTrue();
        assertThat(PortabilityFormat.isAuto("n8n")).isFalse();
    }

    @Test
    void detectFindsTheVegadutaEnvelope() throws Exception {
        JsonNode doc = json("{'format': 'vegaduta.workflow', 'schemaVersion': 1, 'nodes': []}");
        assertThat(PortabilityFormat.detect(doc)).contains(PortabilityFormat.VEGADUTA);
    }

    @Test
    void detectFindsDifyByItsGraphAndByItsAppKind() throws Exception {
        assertThat(
                        PortabilityFormat.detect(
                                json("{'app': {'name': 'x'}, 'workflow': {'graph': {}}}")))
                .contains(PortabilityFormat.DIFY);
        assertThat(PortabilityFormat.detect(json("{'kind': 'app', 'app': {'name': 'x'}}")))
                .contains(PortabilityFormat.DIFY);
    }

    @Test
    void detectFindsN8nByNodesPlusConnections() throws Exception {
        assertThat(PortabilityFormat.detect(json("{'nodes': [], 'connections': {}}")))
                .contains(PortabilityFormat.N8N);
    }

    @Test
    void detectFallsBackToSchemaVersionPlusNodes() throws Exception {
        assertThat(PortabilityFormat.detect(json("{'schemaVersion': 1, 'nodes': []}")))
                .contains(PortabilityFormat.VEGADUTA);
    }

    @Test
    void detectReturnsEmptyForAnythingElse() throws Exception {
        assertThat(PortabilityFormat.detect(null)).isEmpty();
        assertThat(PortabilityFormat.detect(json("[1, 2]"))).isEmpty();
        assertThat(PortabilityFormat.detect(json("{'name': 'x'}"))).isEmpty();
        assertThat(PortabilityFormat.detect(json("{'nodes': []}"))).isEmpty();
    }
}
