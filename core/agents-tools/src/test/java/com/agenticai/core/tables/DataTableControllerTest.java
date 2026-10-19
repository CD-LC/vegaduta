package com.agenticai.core.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.tables.DataTableDtos.RowPage;
import com.agenticai.core.tables.DataTableDtos.RowQuery;
import com.agenticai.core.tables.DataTableFilter.Condition;
import com.agenticai.core.tables.DataTableFilter.Op;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

/** The controller only translates HTTP into service calls; the rules live in {@link DataTableService}. */
class DataTableControllerTest {

    private DataTableService service;
    private DataTableController controller;

    @BeforeEach
    void setUp() {
        service = mock(DataTableService.class);
        controller = new DataTableController(service);
    }

    @Test
    void rowsParsesTheJsonFilterAndTheSortParameter() {
        when(service.queryRows(eq("orders"), any(RowQuery.class))).thenReturn(new RowPage(List.of(), 0, 50, 0));

        controller.rows("orders", "[{\"column\":\"qty\",\"op\":\"gte\",\"value\":2}]", "qty:desc", 50, 0);

        ArgumentCaptor<RowQuery> q = ArgumentCaptor.forClass(RowQuery.class);
        verify(service).queryRows(eq("orders"), q.capture());
        assertThat(q.getValue().filter()).containsExactly(new Condition("qty", Op.GTE, 2));
        assertThat(q.getValue().sort()).isEqualTo(new DataTableFilter.Sort("qty", true));
        assertThat(q.getValue().limit()).isEqualTo(50);
    }

    @Test
    void aMalformedFilterIsAnIllegalArgumentSoItAnswers400() {
        assertThatThrownBy(() -> controller.rows("orders", "{bad", null, 50, 0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    @Test
    void importReadsTheMultipartFileAsUtf8Text() {
        controller.importCsv("orders", new MockMultipartFile("file", "x.csv", "text/csv", "email\na@b.co\n".getBytes(StandardCharsets.UTF_8)));

        verify(service).importCsv("orders", "email\na@b.co\n");
    }

    @Test
    void anEmptyUploadIsRefused() {
        assertThatThrownBy(() -> controller.importCsv("orders", new MockMultipartFile("file", new byte[0])))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Choose a .csv file");
        verifyNoInteractions(service);
    }

    @Test
    void exportIsServedAsAnAttachment() {
        when(service.exportCsv("orders")).thenReturn("email\r\n");

        ResponseEntity<byte[]> response = controller.exportCsv("orders");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo("email\r\n");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("attachment");
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
    }

    @Test
    void configIsReadableEvenWhenTheFeatureIsOff() {
        DataTableDtos.Config off = new DataTableDtos.Config(false, false, 50, 100_000, 50, 65_536, 1000, 5_242_880, 10_000);
        when(service.config()).thenReturn(off);

        assertThat(controller.config().enabled()).isFalse();
    }
}
