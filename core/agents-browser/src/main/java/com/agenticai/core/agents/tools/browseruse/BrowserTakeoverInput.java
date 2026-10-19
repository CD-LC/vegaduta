package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.InputEvent;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates and relays one input event of the person to the page (click, key, type, scroll). Split
 * out of {@link BrowserTakeoverService}, which still authorises the caller and rate limits first.
 * What the person types is relayed and noted as an in-memory redaction candidate only - never
 * logged or echoed back (a page error answers a generic 409).
 */
final class BrowserTakeoverInput {

    private static final Set<String> NAMED_KEYS =
            Set.of(
                    "Enter",
                    "Tab",
                    "Backspace",
                    "Delete",
                    "Escape",
                    "ArrowUp",
                    "ArrowDown",
                    "ArrowLeft",
                    "ArrowRight",
                    "Home",
                    "End",
                    "PageUp",
                    "PageDown",
                    "Space");

    private final BrowserTakeoverSettings settings;
    private final Supplier<BrowserTakeoverDriver> driver;

    /** {@code driver} returns the wired driver or throws {@link BrowserUseException}. */
    BrowserTakeoverInput(BrowserTakeoverSettings settings, Supplier<BrowserTakeoverDriver> driver) {
        this.settings = settings;
        this.driver = driver;
    }

    void relay(BrowserTakeoverControl control, InputEvent event) {
        String key = control.sessionKey();
        try {
            switch (event.type().toLowerCase(Locale.ROOT)) {
                case "click" -> relayClick(control, key, event);
                case "key" -> relayKey(control, key, event);
                case "type" -> relayType(control, key, event);
                case "scroll" -> relayScroll(key, event);
                default ->
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Unknown input type.");
            }
        } catch (BrowserUseException e) {
            // Generic on purpose: nothing the person typed or clicked is echoed back or logged.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The browser could not apply that input.");
        }
    }

    private void relayClick(BrowserTakeoverControl control, String key, InputEvent event) {
        int fw = bounded(event.frameWidth(), 1, 4_000, "frameWidth");
        int fh = bounded(event.frameHeight(), 1, 4_000, "frameHeight");
        int x = bounded(event.x(), 0, fw, "x");
        int y = bounded(event.y(), 0, fh, "y");
        int[] viewport = driver.get().viewport(key);
        int px = mapCoordinate(x, fw, viewport[0]);
        int py = mapCoordinate(y, fh, viewport[1]);
        control.endTypedRun();
        driver.get().click(key, px, py);
    }

    private void relayKey(BrowserTakeoverControl control, String key, InputEvent event) {
        String name = event.key();
        if (name == null || name.isEmpty() || name.length() > 16) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported key.");
        }
        boolean named = NAMED_KEYS.contains(name);
        boolean single =
                !named
                        && name.codePointCount(0, name.length()) == 1
                        && !Character.isISOControl(name.codePointAt(0));
        if (!named && !single) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported key.");
        }
        switch (name) {
            case "Backspace" -> control.noteBackspace();
            case "Enter", "Tab" -> control.endTypedRun();
            case "Space" -> control.noteTyped(" ");
            default -> {
                if (single) {
                    control.noteTyped(name);
                }
            }
        }
        driver.get().press(key, name);
    }

    private void relayType(BrowserTakeoverControl control, String key, InputEvent event) {
        String text = event.text();
        if (text == null || text.isEmpty() || text.length() > settings.maxTypeChars()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Text is empty or longer than " + settings.maxTypeChars() + " characters.");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t') {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Text contains control characters.");
            }
        }
        control.noteTyped(text);
        driver.get().typeText(key, text);
    }

    private void relayScroll(String key, InputEvent event) {
        int cap = settings.maxScrollPx();
        int dx = clampSigned(event.dx() == null ? 0 : event.dx(), cap);
        int dy = clampSigned(event.dy() == null ? 0 : event.dy(), cap);
        int x = event.x() == null ? 0 : Math.max(0, Math.min(10_000, event.x()));
        int y = event.y() == null ? 0 : Math.max(0, Math.min(10_000, event.y()));
        driver.get().scroll(key, x, y, dx, dy);
    }

    /** Frame-pixel coordinate to page coordinate: proportional, clamped inside the viewport. */
    static int mapCoordinate(int framePixel, int frameSize, int viewportSize) {
        if (frameSize <= 0 || viewportSize <= 0) {
            return 0;
        }
        long mapped = Math.round((double) framePixel * viewportSize / frameSize);
        return (int) Math.max(0, Math.min(viewportSize - 1, mapped));
    }

    private static int bounded(Integer value, int min, int max, String name) {
        if (value == null || value < min || value > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + name + ".");
        }
        return value;
    }

    private static int clampSigned(int value, int cap) {
        return Math.max(-cap, Math.min(cap, value));
    }
}
