package com.agenticai.core.workflow.diagram;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import com.agenticai.core.documentgen.brand.DocumentBrand;
import com.agenticai.core.documentgen.brand.DocumentBrandResolver;
import com.agenticai.core.documentgen.brand.PdfBrandHeader;
import com.agenticai.core.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Draws an actual boxes-and-arrows diagram (not just a text export) of a
 * {@link ResolvedWorkflowGraph} using raw PDFBox primitives - there is no
 * headless-browser/mermaid-cli rendering pipeline in this stack to rasterize
 * the Mermaid/PlantUML source server-side, so this lays out and draws the
 * graph directly instead. Layout is a simple longest-path BFS leveling (root
 * nodes = level 0, every other node's level = 1 + max(predecessor levels)),
 * one row per level, nodes within a row centered and evenly spaced - not a
 * general graph-layout algorithm, but sufficient for the mostly-linear-with-
 * occasional-branches shape workflow graphs in this product actually have.
 * Renders onto a single custom-sized page rather than paginating, so the
 * whole diagram is always visible without cross-page splits.
 */
@Component
public class WorkflowDiagramPdfRenderer {

    private static final float NODE_WIDTH = 190f;
    private static final float NODE_HEIGHT = 56f;
    private static final float H_GAP = 36f;
    private static final float V_GAP = 60f;
    private static final float MARGIN = 44f;
    private static final float TITLE_HEIGHT = 40f;
    private static final float FONT_SIZE = 8.5f;
    private static final float MAX_PAGE_DIMENSION = 14000f;
    private static final float BRAND_HEIGHT = 34f;

    /** Optional: absent (tests, minimal contexts) -> the VegaDuta brand. */
    private DocumentBrandResolver brandResolver;

    @Autowired(required = false)
    void setBrandResolver(DocumentBrandResolver brandResolver) {
        this.brandResolver = brandResolver;
    }

    /** Tenant brand when the current tenant set one, VegaDuta's otherwise (2026-10-09). */
    private DocumentBrand brand() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        return brandResolver != null ? brandResolver.resolve(tenantId) : DocumentBrandResolver.platform();
    }

    public String renderToBase64(ResolvedWorkflowGraph graph) {
        Map<String, Integer> levelByNode = computeLevels(graph);
        Map<Integer, List<DiagramNode>> byLevel = new TreeMap<>();
        for (DiagramNode node : graph.nodes()) {
            byLevel.computeIfAbsent(levelByNode.getOrDefault(node.id(), 0), k -> new ArrayList<>()).add(node);
        }
        int maxLevel = byLevel.keySet().stream().max(Integer::compareTo).orElse(0);
        int maxPerLevel = byLevel.values().stream().mapToInt(List::size).max().orElse(1);

        float pageWidth = clamp(Math.max(400f, MARGIN * 2 + maxPerLevel * (NODE_WIDTH + H_GAP) - H_GAP));
        float pageHeight = clamp(Math.max(300f, MARGIN * 2 + BRAND_HEIGHT + TITLE_HEIGHT + (maxLevel + 1) * (NODE_HEIGHT + V_GAP)));

        Map<String, float[]> centerByNode = new HashMap<>();

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setNonStrokingColor(Color.BLACK);
                // Brand strip above the title (tenant brand or VegaDuta's).
                new PdfBrandHeader(document, brand()).draw(stream, font, MARGIN, pageHeight - MARGIN + 14f, pageWidth - 2 * MARGIN);
                stream.setNonStrokingColor(Color.BLACK);
                stream.beginText();
                stream.setFont(boldFont, 14f);
                stream.newLineAtOffset(MARGIN, pageHeight - MARGIN - BRAND_HEIGHT);
                stream.showText(sanitize(truncate(graph.workflowName() == null ? "Workflow Diagram" : graph.workflowName(), 90)));
                stream.endText();

                float top = pageHeight - MARGIN - BRAND_HEIGHT - TITLE_HEIGHT;
                for (Map.Entry<Integer, List<DiagramNode>> entry : byLevel.entrySet()) {
                    List<DiagramNode> levelNodes = entry.getValue();
                    float rowTop = top - entry.getKey() * (NODE_HEIGHT + V_GAP);
                    float totalWidth = levelNodes.size() * (NODE_WIDTH + H_GAP) - H_GAP;
                    float startX = (pageWidth - totalWidth) / 2f;
                    for (int i = 0; i < levelNodes.size(); i++) {
                        DiagramNode node = levelNodes.get(i);
                        float x = startX + i * (NODE_WIDTH + H_GAP);
                        float y = rowTop - NODE_HEIGHT;
                        drawNode(stream, font, node, x, y);
                        centerByNode.put(node.id(), new float[] {x + NODE_WIDTH / 2f, y + NODE_HEIGHT / 2f});
                    }
                }

                for (WorkflowEdgeDto edge : graph.edges()) {
                    float[] from = centerByNode.get(edge.source());
                    float[] to = centerByNode.get(edge.target());
                    if (from == null || to == null) {
                        continue;
                    }
                    drawArrow(stream, font,
                        new float[] {from[0], from[1] - NODE_HEIGHT / 2f},
                        new float[] {to[0], to[1] + NODE_HEIGHT / 2f},
                        edge.sourceHandle());
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not render workflow diagram PDF: " + e.getMessage(), e);
        }
    }

    private void drawNode(PDPageContentStream stream, PDType1Font font, DiagramNode node, float x, float y) throws IOException {
        Color fill = colorFor(node.styleClass());
        if (node.branching()) {
            drawDiamond(stream, x, y, fill);
        } else {
            stream.setNonStrokingColor(fill);
            stream.addRect(x, y, NODE_WIDTH, NODE_HEIGHT);
            stream.fill();
            stream.setStrokingColor(Color.DARK_GRAY);
            stream.setLineWidth(1f);
            stream.addRect(x, y, NODE_WIDTH, NODE_HEIGHT);
            stream.stroke();
        }
        drawWrappedText(stream, font, node.label(), x + 8, y + NODE_HEIGHT - 14, NODE_WIDTH - 16, FONT_SIZE, 12f, 3);
    }

    private void drawDiamond(PDPageContentStream stream, float x, float y, Color fill) throws IOException {
        float cx = x + NODE_WIDTH / 2f;
        float cy = y + NODE_HEIGHT / 2f;
        stream.setNonStrokingColor(fill);
        stream.moveTo(cx, y + NODE_HEIGHT);
        stream.lineTo(x + NODE_WIDTH, cy);
        stream.lineTo(cx, y);
        stream.lineTo(x, cy);
        stream.closePath();
        stream.fill();
        stream.setStrokingColor(Color.DARK_GRAY);
        stream.setLineWidth(1f);
        stream.moveTo(cx, y + NODE_HEIGHT);
        stream.lineTo(x + NODE_WIDTH, cy);
        stream.lineTo(cx, y);
        stream.lineTo(x, cy);
        stream.closePath();
        stream.stroke();
    }

    private void drawArrow(PDPageContentStream stream, PDType1Font font, float[] from, float[] to, String label) throws IOException {
        stream.setStrokingColor(Color.DARK_GRAY);
        stream.setLineWidth(1.2f);
        stream.moveTo(from[0], from[1]);
        stream.lineTo(to[0], to[1]);
        stream.stroke();

        double angle = Math.atan2(to[1] - from[1], to[0] - from[0]);
        float arrowLen = 8f;
        double leftAngle = angle + Math.toRadians(150);
        double rightAngle = angle - Math.toRadians(150);
        stream.setNonStrokingColor(Color.DARK_GRAY);
        stream.moveTo(to[0], to[1]);
        stream.lineTo((float) (to[0] + arrowLen * Math.cos(leftAngle)), (float) (to[1] + arrowLen * Math.sin(leftAngle)));
        stream.lineTo((float) (to[0] + arrowLen * Math.cos(rightAngle)), (float) (to[1] + arrowLen * Math.sin(rightAngle)));
        stream.closePath();
        stream.fill();

        if (label != null && !label.isBlank()) {
            float midX = (from[0] + to[0]) / 2f;
            float midY = (from[1] + to[1]) / 2f;
            stream.setNonStrokingColor(Color.DARK_GRAY);
            stream.beginText();
            stream.setFont(font, 7.5f);
            stream.newLineAtOffset(midX + 3, midY);
            stream.showText(sanitize(truncate(label, 20)));
            stream.endText();
        }
    }

    private void drawWrappedText(
        PDPageContentStream stream, PDType1Font font, String text, float x, float y, float maxWidth,
        float fontSize, float leading, int maxLines
    ) throws IOException {
        stream.setNonStrokingColor(Color.BLACK);
        List<String> lines = wrap(sanitize(text), font, fontSize, maxWidth);
        stream.beginText();
        stream.setFont(font, fontSize);
        stream.newLineAtOffset(x, y);
        int drawn = 0;
        for (String line : lines) {
            if (drawn >= maxLines) {
                break;
            }
            String toShow = drawn == maxLines - 1 && lines.size() > maxLines ? truncate(line, line.length() - 3) + "..." : line;
            stream.showText(toShow);
            stream.newLineAtOffset(0, -leading);
            drawn++;
        }
        stream.endText();
    }

    /** Longest-path leveling: a node's level is 1 + the max level of its predecessors, computed via a topological-ish BFS from every zero-indegree node. Falls back to level 0 for any node left unreached (isolated node, or a cycle this engine's own {@code OrchestratorService} should already reject before a graph gets this far). */
    private Map<String, Integer> computeLevels(ResolvedWorkflowGraph graph) {
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (DiagramNode node : graph.nodes()) {
            outgoing.put(node.id(), new ArrayList<>());
            indegree.put(node.id(), 0);
        }
        for (WorkflowEdgeDto edge : graph.edges()) {
            if (!outgoing.containsKey(edge.source()) || !indegree.containsKey(edge.target())) {
                continue;
            }
            outgoing.get(edge.source()).add(edge.target());
            indegree.merge(edge.target(), 1, Integer::sum);
        }
        Map<String, Integer> remainingIndegree = new HashMap<>(indegree);
        Map<String, Integer> level = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
                level.put(entry.getKey(), 0);
            }
        }
        // Kahn's algorithm: a node is only enqueued once every predecessor has
        // already been leveled (its indegree count reaches zero), so
        // level = 1 + max(predecessor levels) is always computed from
        // complete information - matters for a node with multiple incoming
        // edges (e.g. two branches converging on one downstream node).
        int guard = graph.nodes().size() + graph.edges().size() + 10;
        while (!queue.isEmpty() && guard-- > 0) {
            String current = queue.poll();
            int currentLevel = level.getOrDefault(current, 0);
            for (String next : outgoing.getOrDefault(current, List.of())) {
                level.put(next, Math.max(level.getOrDefault(next, 0), currentLevel + 1));
                if (remainingIndegree.merge(next, -1, Integer::sum) <= 0) {
                    queue.add(next);
                }
            }
        }
        // Any node never reached (a genuine cycle with no zero-indegree entry
        // point - shouldn't normally occur, OrchestratorService rejects the
        // common cases at save time) still gets a level so it renders rather
        // than silently vanishing from the diagram.
        int fallbackLevel = level.values().stream().max(Integer::compareTo).orElse(-1) + 1;
        for (DiagramNode node : graph.nodes()) {
            level.putIfAbsent(node.id(), fallbackLevel);
        }
        return level;
    }

    private Color colorFor(String styleClass) {
        return switch (styleClass) {
            case "trigger" -> new Color(224, 242, 254);
            case "agent" -> new Color(237, 233, 254);
            case "condition" -> new Color(254, 243, 199);
            case "output" -> new Color(209, 250, 229);
            case "tool" -> new Color(252, 231, 243);
            default -> new Color(226, 232, 240);
        };
    }

    private float clamp(float value) {
        return Math.min(value, MAX_PAGE_DIMENSION);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, Math.max(0, max)) + "...";
    }

    /** Same reasoning as {@code PdfOutputRenderer#sanitize} - WinAnsiEncoding rejects characters outside its code page. */
    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(c < 32 ? ' ' : (c > 255 ? '?' : c));
        }
        return sb.toString();
    }

    private List<String> wrap(String text, PDType1Font font, float fontSize, float maxWidth) throws IOException {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            float width = font.getStringWidth(candidate) / 1000 * fontSize;
            if (width > maxWidth && !current.isEmpty()) {
                result.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }
}
