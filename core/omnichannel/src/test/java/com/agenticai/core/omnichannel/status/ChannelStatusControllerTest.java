package com.agenticai.core.omnichannel.status;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.status.ChannelStatusService.ChannelStatusResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ChannelStatusController}: a pure pass-through to {@link ChannelStatusService}. */
class ChannelStatusControllerTest {

    @Test
    void statusReturnsWhateverTheServiceComputed() {
        ChannelStatusService service = mock(ChannelStatusService.class);
        ChannelStatusResponse expected = new ChannelStatusResponse(1, 2, List.of(), List.of());
        when(service.status()).thenReturn(expected);

        ChannelStatusController controller = new ChannelStatusController(service);
        assertSame(expected, controller.status());
    }
}
