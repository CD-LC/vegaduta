package com.agenticai.core.common.roi;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.common.roi.PilotDtos.BaselineResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PilotBaselineValidationTest {

    @Test
    void jobTypesAreTheFourPilotJobs() {
        assertThat(PilotDtos.JOB_TYPES)
                .containsExactly(
                        "MISSED_CALL_RECOVERY",
                        "APPOINTMENT_REMINDERS",
                        "PAYMENT_FOLLOW_UP",
                        "LEAD_FOLLOW_UP");
    }

    @Test
    void anAllNullBaselineIsEmptyAndStillLabelledTenantEntered() {
        BaselineResponse b =
                new BaselineResponse(
                        UUID.randomUUID(), null, null, null, null, null, null, null, null, null, null,
                        PilotDtos.TENANT_ENTERED);
        assertThat(b.isEmpty()).isTrue();
        assertThat(b.source()).isEqualTo("tenant-entered");
    }
}
