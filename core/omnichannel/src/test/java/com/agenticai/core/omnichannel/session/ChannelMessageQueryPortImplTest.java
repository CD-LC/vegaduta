package com.agenticai.core.omnichannel.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.messaging.ChannelMessageQueryPort.ChannelMessageRecord;
import com.agenticai.core.agents.tools.messaging.ChannelMessageQueryPort.ConversationSearchHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Query shaping of the agent's message-history port: filters, caps and LIKE sanitising. */
class ChannelMessageQueryPortImplTest {

    private ChannelMessageLogRepository repository;
    private ChannelMessageQueryPortImpl port;

    @BeforeEach
    void setUp() {
        repository = mock(ChannelMessageLogRepository.class);
        port = new ChannelMessageQueryPortImpl(repository);
    }

    private static ChannelMessageLog log(
            ChannelType channel, String body, ChannelMessageDirection direction) {
        return new ChannelMessageLog(channel, "ext-1", UUID.randomUUID(), body, direction, "op");
    }

    @Test
    void recentMessagesAreCappedByTheLimitAndAnUnknownChannelIsEmpty() {
        when(repository.findTop50ByChannelOrderByCreatedAtDesc(ChannelType.SLACK))
                .thenReturn(
                        List.of(
                                log(ChannelType.SLACK, "a", null),
                                log(ChannelType.SLACK, "b", null),
                                log(ChannelType.SLACK, "c", null)));

        List<ChannelMessageRecord> two = port.recentMessages("slack", 2);

        assertThat(two).extracting(ChannelMessageRecord::body).containsExactly("a", "b");
        assertThat(port.recentMessages("slack", 0)).hasSize(1);
        assertThat(port.recentMessages("slack", 99)).hasSize(3);
        assertThat(port.recentMessages("carrier-pigeon", 5)).isEmpty();
        assertThat(port.recentMessages(null, 5)).isEmpty();
    }

    @Test
    void aBlankOrWildcardOnlyQueryFindsNothing() {
        assertThat(port.searchMessages("slack", null, null, 5)).isEmpty();
        assertThat(port.searchMessages("slack", null, " % _ ", 5)).isEmpty();
        verify(repository, never()).findTop50ByBodyContainingIgnoreCaseOrderByCreatedAtDesc("");
    }

    @Test
    void anUnknownChannelFilterFindsNothingButABlankOneSearchesEverywhere() {
        assertThat(port.searchMessages("carrier-pigeon", null, "refund", 5)).isEmpty();

        when(repository.findTop50ByBodyContainingIgnoreCaseOrderByCreatedAtDesc("refund"))
                .thenReturn(List.of(log(ChannelType.SLACK, "refund please", null)));
        List<ConversationSearchHit> hits = port.searchMessages(" ", " ", "refund", 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).channel()).isEqualTo("slack");
        assertThat(hits.get(0).direction()).isEqualTo("inbound");
    }

    @Test
    void eachFilterCombinationUsesItsOwnFinder() {
        when(repository
                        .findTop50ByChannelAndExternalIdAndBodyContainingIgnoreCaseOrderByCreatedAtDesc(
                                ChannelType.EMAIL, "a@x", "refund"))
                .thenReturn(
                        List.of(log(ChannelType.EMAIL, "both", ChannelMessageDirection.OUTBOUND)));
        when(repository.findTop50ByChannelAndBodyContainingIgnoreCaseOrderByCreatedAtDesc(
                        ChannelType.EMAIL, "refund"))
                .thenReturn(List.of(log(ChannelType.EMAIL, "channel only", null)));
        when(repository.findTop50ByExternalIdAndBodyContainingIgnoreCaseOrderByCreatedAtDesc(
                        "a@x", "refund"))
                .thenReturn(List.of(log(ChannelType.EMAIL, "sender only", null)));

        assertThat(port.searchMessages("email", " a@x ", "re%f_und", 5).get(0).body())
                .isEqualTo("both");
        assertThat(port.searchMessages("email", null, "refund", 5).get(0).body())
                .isEqualTo("channel only");
        assertThat(port.searchMessages(null, "a@x", "refund", 5).get(0).body())
                .isEqualTo("sender only");
        assertThat(port.searchMessages("email", "a@x", "refund", 5).get(0).direction())
                .isEqualTo("outbound");
    }

    @Test
    void anOverlongNeedleIsTruncatedTo200Characters() {
        String needle = "n".repeat(250);
        when(repository.findTop50ByBodyContainingIgnoreCaseOrderByCreatedAtDesc("n".repeat(200)))
                .thenReturn(List.of(log(ChannelType.SLACK, "hit", null)));

        assertThat(port.searchMessages(null, null, needle, 5)).hasSize(1);
    }
}
