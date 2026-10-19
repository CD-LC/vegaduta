package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.FrameEvent;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.web.server.ResponseStatusException;

/**
 * One live-view stream's frame loop, free of any servlet type so it can be driven synchronously in
 * a test. The controller calls {@link #tick} at the configured frame interval (fixed DELAY, never
 * fixed rate, so a slow page lowers the frame rate instead of queueing frames) and stops when it
 * returns {@code false}.
 *
 * <p>It ends - telling the client why - the moment the episode stops being the person's: they
 * handed back, the idle or hard-duration clock fired, the session closed, or a newer takeover
 * began. It never carries a typed value, only JPEG frames of what is on the page and the
 * countdowns.
 */
final class BrowserTakeoverStreamPump {

    private final BrowserTakeoverService service;
    private final String tenantId;
    private final String userId;
    private final UUID takeoverId;
    private final int epoch;
    private final Consumer<FrameEvent> frames;
    private final Consumer<String> ended;
    private boolean done;

    BrowserTakeoverStreamPump(
            BrowserTakeoverService service,
            String tenantId,
            String userId,
            UUID takeoverId,
            int epoch,
            Consumer<FrameEvent> frames,
            Consumer<String> ended) {
        this.service = service;
        this.tenantId = tenantId;
        this.userId = userId;
        this.takeoverId = takeoverId;
        this.epoch = epoch;
        this.frames = frames;
        this.ended = ended;
    }

    /**
     * Emits one frame; returns false once the stream is over (the {@code ended} callback has then
     * been called once).
     */
    synchronized boolean tick() {
        if (done) {
            return false;
        }
        if (!service.streamStillValid(tenantId, userId, takeoverId, epoch)) {
            return finish("control_returned");
        }
        try {
            frames.accept(service.captureFrame(tenantId, userId, takeoverId, epoch));
            return true;
        } catch (ResponseStatusException e) {
            // A frame that cannot be taken (page mid-navigation) is skipped; a takeover that is
            // gone ends the stream.
            return service.streamStillValid(tenantId, userId, takeoverId, epoch)
                    || finish("control_returned");
        } catch (RuntimeException e) {
            return finish("error");
        }
    }

    /** The client went away. */
    synchronized void cancel() {
        done = true;
    }

    private boolean finish(String reason) {
        done = true;
        ended.accept(reason);
        return false;
    }
}
