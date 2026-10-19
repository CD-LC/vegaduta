package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The live view's bandwidth bound: the long side is capped, the quality is capped, and a bad image
 * fails cleanly.
 */
class BrowserTakeoverFramesTest {

    private static byte[] jpeg(int w, int h, int quality) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, ((x * 7) ^ (y * 13)) & 0xFFFFFF);
            }
        }
        return BrowserTakeoverFrames.encode(image, quality);
    }

    @Test
    void aViewportThatFitsIsPassedThroughUnchanged() throws Exception {
        byte[] original = jpeg(800, 600, 60);
        BrowserTakeoverDriver.Frame frame = BrowserTakeoverFrames.fit(original, 800, 600, 1024, 55);
        assertThat(frame.jpeg()).isSameAs(original);
        assertThat(frame.frameWidth()).isEqualTo(800);
        assertThat(frame.frameHeight()).isEqualTo(600);
    }

    @Test
    void aLargerViewportIsScaledDownProportionallyToTheCap() throws Exception {
        byte[] original = jpeg(1280, 800, 90);
        BrowserTakeoverDriver.Frame frame = BrowserTakeoverFrames.fit(original, 1280, 800, 640, 40);
        assertThat(frame.frameWidth()).isEqualTo(640);
        assertThat(frame.frameHeight()).isEqualTo(400);
        assertThat(frame.viewportWidth()).isEqualTo(1280);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(frame.jpeg()));
        assertThat(decoded.getWidth()).isEqualTo(640);
        assertThat(decoded.getHeight()).isEqualTo(400);
        assertThat(frame.jpeg().length).as("smaller than the source").isLessThan(original.length);
    }

    @Test
    void aTallViewportIsCappedOnItsLongSide() {
        assertThat(BrowserTakeoverFrames.fittedSize(400, 2000, 1000)).containsExactly(200, 1000);
        assertThat(BrowserTakeoverFrames.fittedSize(1, 1, 1000)).containsExactly(1, 1);
        assertThat(BrowserTakeoverFrames.fittedSize(5000, 1, 1000)).containsExactly(1000, 1);
    }

    @Test
    void bytesThatAreNotAnImageFailWithAGenericMessage() {
        assertThatThrownBy(
                        () -> BrowserTakeoverFrames.fit(new byte[] {1, 2, 3}, 2000, 1000, 500, 50))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("unreadable frame");
    }
}
