package com.agenticai.core.agents.tools.browseruse;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Bounds one live-view frame: the long side is capped at {@code maxDimension} and the JPEG quality
 * at the configured ceiling, so the stream's bandwidth is a property of the settings rather than of
 * whatever page the person is on. Pure (bytes in, bytes out), so it is unit-tested without a
 * browser.
 */
final class BrowserTakeoverFrames {

    private BrowserTakeoverFrames() {}

    /**
     * The frame size for a viewport: unchanged when its long side fits, else scaled down
     * proportionally.
     */
    static int[] fittedSize(int width, int height, int maxDimension) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int longSide = Math.max(w, h);
        if (longSide <= maxDimension) {
            return new int[] {w, h};
        }
        double scale = (double) maxDimension / longSide;
        return new int[] {
            Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))
        };
    }

    /**
     * Returns {@code jpeg} unchanged when the viewport already fits, otherwise a re-encoded copy at
     * the fitted size. Throws {@link BrowserUseException} (generic message) when the bytes are not
     * a decodable image.
     */
    static BrowserTakeoverDriver.Frame fit(
            byte[] jpeg, int viewportWidth, int viewportHeight, int maxDimension, int quality) {
        int[] size = fittedSize(viewportWidth, viewportHeight, maxDimension);
        if (size[0] == viewportWidth && size[1] == viewportHeight) {
            return new BrowserTakeoverDriver.Frame(
                    jpeg, size[0], size[1], viewportWidth, viewportHeight);
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (source == null) {
                throw new BrowserUseException("the browser produced an unreadable frame");
            }
            BufferedImage scaled = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            try {
                g.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(source, 0, 0, size[0], size[1], null);
            } finally {
                g.dispose();
            }
            return new BrowserTakeoverDriver.Frame(
                    encode(scaled, quality), size[0], size[1], viewportWidth, viewportHeight);
        } catch (IOException e) {
            throw new BrowserUseException("the browser produced an unreadable frame");
        }
    }

    static byte[] encode(BufferedImage image, int quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("no jpeg writer");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0.05f, Math.min(1.0f, quality / 100f)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
