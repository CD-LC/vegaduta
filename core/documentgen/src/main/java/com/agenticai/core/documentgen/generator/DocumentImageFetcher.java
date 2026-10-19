package com.agenticai.core.documentgen.generator;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.apache.poi.sl.usermodel.PictureData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fetches an image the model referenced by URL so a generated document can embed it.
 * Every URL is SSRF-validated up front (public http/https only) and redirects are not
 * followed - the destination the guard validated is the only one ever contacted.
 * Failure is reported as {@link Result#ok} false with a short reason rather than thrown:
 * a bad image link must degrade that one slide, never fail the whole document job.
 */
@Component
public class DocumentImageFetcher {

    private static final Logger log = LoggerFactory.getLogger(DocumentImageFetcher.class);

    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public record Image(byte[] bytes, PictureData.PictureType type) {}

    public record Result(Image image, boolean ok, String reason) {}

    private final SsrfGuard ssrfGuard;
    private final HttpClient client;

    public DocumentImageFetcher(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
        this.client = GuardedJdkHttpClient.create(ssrfGuard, TIMEOUT, TIMEOUT, false);
    }

    public Result fetch(String url) {
        if (url == null || url.isBlank()) {
            return new Result(null, false, "no image url");
        }
        try {
            SsrfGuard.Target target = ssrfGuard.validate(url);
            if (!"http".equalsIgnoreCase(target.uri().getScheme())
                    && !"https".equalsIgnoreCase(target.uri().getScheme())) {
                return new Result(null, false, "only http(s) image urls");
            }
            HttpRequest request = HttpRequest.newBuilder(target.uri()).timeout(TIMEOUT).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                return new Result(null, false, "image url answered " + code);
            }
            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0) {
                return new Result(null, false, "image url returned nothing");
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                return new Result(null, false, "image is larger than 8 MB");
            }
            PictureData.PictureType type = sniff(bytes);
            if (type == null) {
                String ct = response.headers().firstValue("content-type").orElse("");
                type = contentTypeTo(ct);
            }
            if (type == null) {
                return new Result(null, false, "not a supported image (png/jpeg)");
            }
            return new Result(new Image(bytes, type), true, null);
        } catch (SsrfBlockedException e) {
            log.info("document image url blocked by ssrf guard: {}", url);
            return new Result(null, false, "image url is not allowed");
        } catch (Exception e) {
            log.info("document image fetch failed for {}: {}", url, e.toString());
            return new Result(null, false, "could not download image");
        }
    }

    private static PictureData.PictureType sniff(byte[] b) {
        if (b.length >= 8
                && b[0] == (byte) 0x89
                && b[1] == 0x50
                && b[2] == 0x4E
                && b[3] == 0x47) {
            return PictureData.PictureType.PNG;
        }
        if (b.length >= 3 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF) {
            return PictureData.PictureType.JPEG;
        }
        return null;
    }

    private static PictureData.PictureType contentTypeTo(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("image/png")) {
            return PictureData.PictureType.PNG;
        }
        if (ct.contains("image/jpeg") || ct.contains("image/jpg")) {
            return PictureData.PictureType.JPEG;
        }
        return null;
    }
}
