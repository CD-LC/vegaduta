package com.agenticai.core.workflow.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.diagram.DiagramNode;
import com.agenticai.core.workflow.diagram.ResolvedWorkflowGraph;
import com.agenticai.core.workflow.diagram.WorkflowDiagramPdfRenderer;
import java.util.Base64;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** Workflow PDFs carry the brand header (VegaDuta wordmark when no tenant brand is set). */
class WorkflowPdfBrandingTest {

    private static int images(PDDocument doc) {
        int n = 0;
        for (var ignored : doc.getPage(0).getResources().getXObjectNames()) {
            n++;
        }
        return n;
    }

    @Test
    void workflowOutputPdfHasTheBrandLogoAndStillItsTitleAndBody() throws Exception {
        byte[] pdf = Base64.getDecoder().decode(new PdfOutputRenderer().renderToBase64("Weekly digest", "Line one\nLine two"));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals(1, images(doc));
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Weekly digest") && text.contains("Line one"), text);
        }
    }

    @Test
    void workflowDiagramPdfHasTheBrandLogo() throws Exception {
        ResolvedWorkflowGraph graph = new ResolvedWorkflowGraph(
            "Lead intake", List.of(new DiagramNode("a", "Start", false, "trigger")), List.of());
        byte[] pdf = Base64.getDecoder().decode(new WorkflowDiagramPdfRenderer().renderToBase64(graph));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals(1, images(doc));
            assertTrue(new PDFTextStripper().getText(doc).contains("Lead intake"));
        }
    }
}
