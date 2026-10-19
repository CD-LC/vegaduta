package com.agenticai.core.omnichannel.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.omnichannel.matrix.MatrixSyncParser.Batch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MatrixSyncParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BOT = "@bot:example.org";

    @Test
    void parsesInvitesEventsAndMemberCounts() throws Exception {
        Batch batch =
                MatrixSyncParser.parse(
                        MAPPER.readTree(
                                """
                {"next_batch":"s2",
                 "rooms":{
                  "invite":{
                   "!inv:example.org":{"invite_state":{"events":[
                     {"type":"m.room.name","sender":"@x:example.org","content":{}},
                     {"type":"m.room.member","state_key":"@bot:example.org","sender":"@alice:example.org",
                      "content":{"membership":"invite"}}]}},
                   "!other:example.org":{"invite_state":{"events":[
                     {"type":"m.room.member","state_key":"@someoneelse:example.org","sender":"@alice:example.org",
                      "content":{"membership":"invite"}}]}}},
                  "join":{
                   "!room:example.org":{
                    "summary":{"m.joined_member_count":2},
                    "state":{"events":[{"type":"m.room.encryption","sender":"@alice:example.org","content":{}}]},
                    "timeline":{"events":[
                     {"type":"m.room.message","event_id":"$e1","sender":"@alice:example.org",
                      "origin_server_ts":5,"content":{"msgtype":"m.text","body":"hi"}}]}}}}}
                """),
                        BOT);

        assertThat(batch.nextBatch()).isEqualTo("s2");
        assertThat(batch.invites())
                .containsExactly(new MatrixSyncParser.Invite("!inv:example.org", "@alice:example.org"));
        assertThat(batch.joinedCounts()).containsEntry("!room:example.org", 2);
        assertThat(batch.events()).hasSize(2);
        assertThat(batch.events().get(0).type()).isEqualTo("m.room.encryption");
        assertThat(batch.events().get(1).eventId()).isEqualTo("$e1");
        assertThat(batch.events().get(1).content().path("body").asText()).isEqualTo("hi");
    }

    @Test
    void anEmptyResponseIsAnEmptyBatch() throws Exception {
        Batch batch = MatrixSyncParser.parse(MAPPER.readTree("{\"next_batch\":\"s1\"}"), BOT);
        assertThat(batch.nextBatch()).isEqualTo("s1");
        assertThat(batch.invites()).isEmpty();
        assertThat(batch.events()).isEmpty();
    }
}
