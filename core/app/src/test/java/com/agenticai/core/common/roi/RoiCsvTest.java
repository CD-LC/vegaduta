package com.agenticai.core.common.roi;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RoiCsvTest {

    @Test
    void quotesCommasAndQuotes() {
        assertThat(RoiController.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(RoiController.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }

    @Test
    void neutralisesSpreadsheetFormulasInText() {
        assertThat(RoiController.cell("=HYPERLINK(\"http://x\")")).startsWith("\"'=");
        assertThat(RoiController.cell("+1")).isEqualTo("'+1");
        assertThat(RoiController.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    }

    @Test
    void negativeNumbersAreNotMangled() {
        assertThat(RoiController.cell(new BigDecimal("-12.50"))).isEqualTo("-12.50");
        assertThat(RoiController.cell(-3L)).isEqualTo("-3");
    }

    @Test
    void headerNamesEveryRoiColumnWithTheAssumptionLabel() {
        assertThat(RoiController.CSV_HEADER)
                .contains(
                        "roi_percent_based_on_your_assumptions",
                        "net_value_based_on_your_assumptions");
    }
}
