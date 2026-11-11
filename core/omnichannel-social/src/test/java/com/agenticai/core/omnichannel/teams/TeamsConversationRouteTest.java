package com.agenticai.core.omnichannel.teams;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The mutable half of Teams routing: the conversation id is the identity (and the primary key), the
 * serviceUrl just records where that conversation was last reachable and is rewritten on every
 * inbound activity.
 */
class TeamsConversationRouteTest {

    @Test
    void aNewRouteRecordsTheServiceUrlAndStampsTheUpdate() {
        TeamsConversationRoute route =
                new TeamsConversationRoute("conv-1", "https://smba.trafficmanager.net/amer/");

        assertThat(route.getConversationId()).isEqualTo("conv-1");
        assertThat(route.getServiceUrl()).isEqualTo("https://smba.trafficmanager.net/amer/");
        assertThat(route.getUpdatedAt()).isNotNull();
    }

    @Test
    void refreshMovesTheRouteToTheFreshestEndpoint() throws Exception {
        TeamsConversationRoute route =
                new TeamsConversationRoute("conv-1", "https://smba.trafficmanager.net/amer/");

        route.refresh("https://smba.trafficmanager.net/emea/");

        assertThat(route.getServiceUrl()).isEqualTo("https://smba.trafficmanager.net/emea/");
        assertThat(route.getUpdatedAt()).isNotNull();
        // The identity never changes - only the routing metadata does.
        assertThat(route.getConversationId()).isEqualTo("conv-1");
    }
}
