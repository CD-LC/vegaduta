package com.agenticai.core.templates;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.templates.dto.TemplateSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression: GET /api/templates had no mapping (POST only) and answered 500. */
class CommunityTemplateControllerListTest {

    @Test
    void getListDelegatesToApprovedGallery() {
        CommunityTemplateService service = mock(CommunityTemplateService.class);
        List<TemplateSummary> expected = List.of();
        when(service.gallery(null, null, null, 0, 24, null)).thenReturn(expected);

        assertSame(expected, new CommunityTemplateController(service).list(null, null, null, null, 0, 24));
    }
}
