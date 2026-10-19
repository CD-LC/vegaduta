package com.agenticai.core.agents.tools.browseruse;

import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.AGENT;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.CHAT_SESSION;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.TENANT;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.Begin;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.FrameEvent;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.Rig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The live-view frame loop: one frame per tick while the person drives, and a clean end the moment
 * they do not.
 */
class BrowserTakeoverStreamPumpTest {

    private static Begin takeControl(Rig rig) {
        rig.service.agentRequest(rig.key, "captcha", "x");
        return rig.service.begin(
                TENANT,
                USER,
                UUID.fromString(
                        rig.service.status(TENANT, USER, AGENT, CHAT_SESSION).takeoverId()));
    }

    @Test
    void emitsOneFramePerTickThenEndsWhenControlIsHandedBack() {
        Rig rig = new Rig(BrowserTakeoverFixtures.on());
        Begin begin = takeControl(rig);
        UUID id = UUID.fromString(begin.view().takeoverId());
        int epoch = rig.service.openStream(TENANT, USER, id, begin.token());
        List<FrameEvent> frames = new ArrayList<>();
        List<String> ended = new ArrayList<>();
        BrowserTakeoverStreamPump pump =
                new BrowserTakeoverStreamPump(
                        rig.service, TENANT, USER, id, epoch, frames::add, ended::add);

        assertThat(pump.tick()).isTrue();
        assertThat(pump.tick()).isTrue();
        assertThat(frames).hasSize(2);
        assertThat(frames.get(1).seq()).isGreaterThan(frames.get(0).seq());
        assertThat(ended).isEmpty();

        rig.service.handBack(TENANT, USER, id);
        assertThat(pump.tick()).isFalse();
        assertThat(ended).containsExactly("control_returned");
        assertThat(frames).as("no frame after control returned").hasSize(2);
        assertThat(pump.tick()).as("a finished pump stays finished and ends only once").isFalse();
        assertThat(ended).hasSize(1);
    }

    @Test
    void aFrameThePageCannotProduceIsSkippedNotFatal() {
        Rig rig = new Rig(BrowserTakeoverFixtures.on());
        Begin begin = takeControl(rig);
        UUID id = UUID.fromString(begin.view().takeoverId());
        int epoch = rig.service.openStream(TENANT, USER, id, begin.token());
        List<FrameEvent> frames = new ArrayList<>();
        List<String> ended = new ArrayList<>();
        BrowserTakeoverStreamPump pump =
                new BrowserTakeoverStreamPump(
                        rig.service, TENANT, USER, id, epoch, frames::add, ended::add);
        rig.driver.failFrames = true;
        assertThat(pump.tick()).isTrue();
        rig.driver.failFrames = false;
        assertThat(pump.tick()).isTrue();
        assertThat(frames).hasSize(1);
        assertThat(ended).isEmpty();
    }

    @Test
    void aStreamBoundToAnEarlierEpisodeEndsWhenANewOneStarts() {
        Rig rig = new Rig(BrowserTakeoverFixtures.on());
        Begin first = takeControl(rig);
        UUID id = UUID.fromString(first.view().takeoverId());
        int epoch = rig.service.openStream(TENANT, USER, id, first.token());
        List<String> ended = new ArrayList<>();
        BrowserTakeoverStreamPump pump =
                new BrowserTakeoverStreamPump(
                        rig.service, TENANT, USER, id, epoch, f -> {}, ended::add);
        rig.service.handBack(TENANT, USER, id);
        rig.service.pause(TENANT, USER, AGENT, CHAT_SESSION, "login", "again");
        rig.service.begin(TENANT, USER, id);
        assertThat(pump.tick()).isFalse();
        assertThat(ended).containsExactly("control_returned");
    }

    @Test
    void anotherUsersPumpNeverGetsAFrame() {
        Rig rig = new Rig(BrowserTakeoverFixtures.on());
        Begin begin = takeControl(rig);
        UUID id = UUID.fromString(begin.view().takeoverId());
        int epoch = rig.service.openStream(TENANT, USER, id, begin.token());
        List<FrameEvent> frames = new ArrayList<>();
        BrowserTakeoverStreamPump pump =
                new BrowserTakeoverStreamPump(
                        rig.service, TENANT, "user-2", id, epoch, frames::add, r -> {});
        assertThat(pump.tick()).isFalse();
        assertThat(frames).isEmpty();
    }
}
