package com.agenticai.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The portability redactor's second pass: URL userinfo, and export-only header / query names. */
class WorkflowPortabilityRedactorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String singleQuoted) throws Exception {
        return MAPPER.readTree(singleQuoted.replace('\'', '"'));
    }

    @Test
    void basicAuthUserinfoInAUrlIsReplacedByAMarker() throws Exception {
        JsonNode in = json("{'url': 'https://admin:hunter2pass@db.example.com/v1?x=1'}");
        for (boolean export : new boolean[] {false, true}) {
            WorkflowPortabilityRedactor.Result r = WorkflowPortabilityRedactor.redact(MAPPER, in, "n1_", export);
            String url = r.json().get("url").asText();
            assertThat(url).doesNotContain("hunter2pass").doesNotContain("admin:");
            assertThat(url).startsWith("https://[[CREDENTIAL_n1_1]]@db.example.com/v1");
            assertThat(r.count()).isEqualTo(1);
        }
        // The input tree is never mutated.
        assertThat(in.get("url").asText()).contains("hunter2pass");
    }

    @Test
    void aVaultReferenceInUserinfoPositionIsKept() throws Exception {
        JsonNode in = json("{'url': 'https://{{secret.USER}}:{{secret.PASS}}@db.example.com/'}");
        WorkflowPortabilityRedactor.Result r = WorkflowPortabilityRedactor.redact(MAPPER, in, "", true);
        assertThat(r.json().get("url").asText()).isEqualTo("https://{{secret.USER}}:{{secret.PASS}}@db.example.com/");
        assertThat(r.count()).isZero();
    }

    @Test
    void subscriptionKeyHeaderIsRedactedOnExportOnly() throws Exception {
        JsonNode in = json("{'headers': [{'name': 'Ocp-Apim-Subscription-Key', 'value': 'abcd1234efgh'},"
            + "{'name': 'X-Api-Auth', 'value': 'zzzz9999yyyy'}, {'name': 'Accept', 'value': 'application/json'}]}");
        WorkflowPortabilityRedactor.Result export = WorkflowPortabilityRedactor.redact(MAPPER, in, "", true);
        String out = export.json().toString();
        assertThat(out).doesNotContain("abcd1234efgh").doesNotContain("zzzz9999yyyy");
        assertThat(out).contains("application/json").contains("Ocp-Apim-Subscription-Key");
        assertThat(export.count()).isEqualTo(2);

        WorkflowPortabilityRedactor.Result plain = WorkflowPortabilityRedactor.redact(MAPPER, in, "", false);
        assertThat(plain.json().toString()).contains("abcd1234efgh");
    }

    @Test
    void headerMapAndQueryParameterNamesAreChecked() throws Exception {
        JsonNode in = json("{'headers': {'Ocp-Apim-Subscription-Key': 'abcd1234efgh', 'Accept': 'text/plain'},"
            + "'url': 'https://api.example.com/v1?subscription-key=qwerty123456&page=2'}");
        String out = WorkflowPortabilityRedactor.redact(MAPPER, in, "", true).json().toString();
        assertThat(out).doesNotContain("abcd1234efgh").doesNotContain("qwerty123456");
        assertThat(out).contains("text/plain").contains("page=2");
    }

    @Test
    void ordinaryFieldsContainingKeyAsPartOfAWordAreLeftAlone() throws Exception {
        JsonNode in = json("{'keyword': 'shoes', 'author': 'Ada Lovelace', 'headers': [{'name': 'Keep-Alive', 'value': 'timeout=5'}]}");
        WorkflowPortabilityRedactor.Result r = WorkflowPortabilityRedactor.redact(MAPPER, in, "", true);
        assertThat(r.json()).isEqualTo(in);
        assertThat(r.count()).isZero();
    }

    @Test
    void existingCredentialPassStillWorksAndNumbersContinue() throws Exception {
        JsonNode in = json("{'headers': [{'name': 'Authorization', 'value': 'Bearer abcdefghijklmnopqrstuvwxyz0123'},"
            + "{'name': 'Subscription-Key', 'value': 'abcd1234efgh'}]}");
        WorkflowPortabilityRedactor.Result r = WorkflowPortabilityRedactor.redact(MAPPER, in, "", true);
        String out = r.json().toString();
        assertThat(out).contains("[[CREDENTIAL_1]]").contains("[[CREDENTIAL_2]]");
        assertThat(out).doesNotContain("abcdefghijklmnopqrstuvwxyz0123").doesNotContain("abcd1234efgh");
        assertThat(r.count()).isEqualTo(2);
    }
}
