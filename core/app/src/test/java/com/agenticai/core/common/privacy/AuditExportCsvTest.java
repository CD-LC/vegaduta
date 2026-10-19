package com.agenticai.core.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AuditExportCsvTest {

    @Test
    void cellsStartingWithFormulaCharactersAreDefused() {
        assertThat(AuditExportService.cell("=HYPERLINK(\"http://x\")")).startsWith("\"'=");
        assertThat(AuditExportService.cell("+1")).isEqualTo("'+1");
        assertThat(AuditExportService.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(AuditExportService.cell("-2")).isEqualTo("'-2");
    }

    @Test
    void commasQuotesAndNewlinesAreQuoted() {
        assertThat(AuditExportService.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(AuditExportService.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(AuditExportService.cell("a\nb")).isEqualTo("\"a\nb\"");
        assertThat(AuditExportService.cell(null)).isEmpty();
        assertThat(AuditExportService.cell(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .isEqualTo("00000000-0000-0000-0000-000000000001");
    }

    @Test
    void rangeMustBeOrderedAndBounded() {
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        assertThatThrownBy(() -> AuditExportService.validate(now, now))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> AuditExportService.validate(now, now.minusSeconds(1)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> AuditExportService.validate(null, now))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> AuditExportService.validate(now.minus(Duration.ofDays(400)), now))
                .isInstanceOf(ResponseStatusException.class);
        AuditExportService.validate(now.minus(Duration.ofDays(30)), now);
    }
}
