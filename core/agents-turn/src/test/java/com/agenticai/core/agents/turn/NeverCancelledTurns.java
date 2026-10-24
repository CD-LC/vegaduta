package com.agenticai.core.agents.turn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import reactor.core.publisher.Flux;

/** Test fixture: a cancel-watch that never fires. A bare mock returns null and NPEs in {@code takeUntilOther}. */
final class NeverCancelledTurns {

    private NeverCancelledTurns() {}

    static ChatTurnCancelService service() {
        ChatTurnCancelService cancel = mock(ChatTurnCancelService.class);
        when(cancel.watchForCancellation(any(), any())).thenReturn(Flux.never());
        return cancel;
    }
}
