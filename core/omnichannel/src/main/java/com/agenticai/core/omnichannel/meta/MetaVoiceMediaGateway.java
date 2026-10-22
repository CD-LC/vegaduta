package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.whatsappvoice.VoiceMediaGateway;
import com.agenticai.core.omnichannel.whatsappvoice.VoiceNoteException;
import com.agenticai.core.omnichannel.whatsappvoice.VoiceNoteOutcome;
import com.agenticai.core.security.net.GuardedJdkHttpClient;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link VoiceMediaGateway} over the Meta Graph API.
 *
 * <p><b>Download</b> is Meta's two-step protocol
 * (https://developers.facebook.com/documentation/business-messaging/whatsapp/business-phone-numbers/media.md):
 * {@code GET /{media-id}} returns {@code {url, mime_type, sha256, file_size}}, then the {@code url}
 * is fetched WITH the same {@code Authorization: Bearer} header (omitting it fails) and expires
 * after five minutes. Guards, all on the untrusted side of the wire:
 *
 * <ul>
 *   <li>the {@code url} must be https on a Meta-owned host (or the configured Graph origin, which
 *       is how a local stub server is accepted in tests) - the bearer token is never sent anywhere
 *       else;
 *   <li>redirects are NOT followed (they would carry the token to another host);
 *   <li>{@code file_size} and the actual body are both capped at {@code maxBytes}; the body is read
 *       through a bounded body handler ({@link #bounded}), so an oversized or endless response is
 *       never buffered whole;
 *   <li>one overall deadline covers both requests and the body;
 *   <li>the mime type must be one of the five WhatsApp audio types; bytes live only in memory.
 * </ul>
 *
 * <p><b>Send</b> reuses {@link MetaMediaGraphApiClient#sendWhatsAppMedia} (upload to {@code
 * /{phone-number-id}/media}, then a JSON {@code type=audio} message) - nothing new is built for it.
 *
 * <p>Never run against the real Meta API from this repository; the host allowlist in particular is
 * derived from the documented domains and is the first thing to check if downloads fail in a pilot.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.omnichannel.whatsapp.voice-notes",
        name = "enabled",
        havingValue = "true")
public class MetaVoiceMediaGateway implements VoiceMediaGateway {

    private static final String GRAPH_VERSION = "v21.0";
    private static final long META_JSON_LIMIT_BYTES = 16_384L;

    /**
     * The five audio types WhatsApp documents (OGG is Opus-only), lower-case, parameters stripped.
     */
    static final Set<String> ALLOWED_MIME_TYPES =
            Set.of("audio/ogg", "audio/mpeg", "audio/mp4", "audio/aac", "audio/amr");

    /**
     * Hosts (and their subdomains) Meta serves media from; checked against the url Meta itself
     * returns.
     */
    static final Set<String> ALLOWED_HOST_SUFFIXES =
            Set.of("fbsbx.com", "facebook.com", "whatsapp.net", "whatsapp.com", "fbcdn.net");

    private final ObjectMapper objectMapper;
    private final String graphBaseUrl;
    private final MetaMediaGraphApiClient mediaClient;
    // Guarded transport; defaults() resolves the process-wide SsrfGuard lazily at
    // lookup time, so the operator's allow-list is always in effect.
    private final HttpClient client;

    @Autowired
    public MetaVoiceMediaGateway(
            ObjectMapper objectMapper,
            @Value("${app.omnichannel.meta.graph-base-url:https://graph.facebook.com}")
                    String graphBaseUrl,
            MetaMediaGraphApiClient mediaClient) {
        this(
                objectMapper,
                graphBaseUrl,
                mediaClient,
                GuardedJdkHttpClient.defaults(
                        Duration.ofSeconds(5), Duration.ofSeconds(30), false));
    }

    /**
     * Test seam: a raw transport for loopback fixture servers, which the SSRF guard rightly
     * refuses.
     */
    MetaVoiceMediaGateway(
            ObjectMapper objectMapper,
            String graphBaseUrl,
            MetaMediaGraphApiClient mediaClient,
            HttpClient client) {
        this.objectMapper = objectMapper;
        this.graphBaseUrl = graphBaseUrl.replaceAll("/+$", "");
        this.mediaClient = mediaClient;
        this.client = client;
    }

    @Override
    public DownloadedMedia download(
            String accessToken, String mediaId, long maxBytes, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        HttpRequest infoRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(graphBaseUrl + "/" + GRAPH_VERSION + "/" + mediaId))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
        HttpResponse<byte[]> info =
                send(infoRequest, bounded(META_JSON_LIMIT_BYTES), deadlineNanos);
        if (info.statusCode() != 200) {
            throw new VoiceNoteException(
                    VoiceNoteOutcome.DOWNLOAD_FAILED,
                    "media lookup returned HTTP " + info.statusCode());
        }
        JsonNode meta;
        try {
            meta = objectMapper.readTree(new String(info.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new VoiceNoteException(
                    VoiceNoteOutcome.DOWNLOAD_FAILED, "media lookup was not JSON");
        }

        String mime = baseMime(meta.path("mime_type").asString(null));
        if (mime == null || !ALLOWED_MIME_TYPES.contains(mime)) {
            throw new VoiceNoteException(
                    VoiceNoteOutcome.UNSUPPORTED_MEDIA,
                    "media type not accepted: " + (mime == null ? "none" : mime));
        }
        long announcedSize = meta.path("file_size").asLong(-1L);
        if (announcedSize > maxBytes) {
            throw new VoiceNoteException(
                    VoiceNoteOutcome.TOO_LARGE,
                    "announced size " + announcedSize + " exceeds cap " + maxBytes);
        }

        URI downloadUri;
        try {
            downloadUri = URI.create(meta.path("url").asString(""));
        } catch (IllegalArgumentException e) {
            throw new VoiceNoteException(
                    VoiceNoteOutcome.DOWNLOAD_FAILED, "media url was not a valid URI");
        }
        if (!isAllowedDownloadUri(downloadUri)) {
            throw new VoiceNoteException(
                    VoiceNoteOutcome.DOWNLOAD_FAILED, "media url host is not an allowed Meta host");
        }

        HttpRequest fileRequest =
                HttpRequest.newBuilder()
                        .uri(downloadUri)
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
        HttpResponse<byte[]> file = send(fileRequest, bounded(maxBytes + 1L), deadlineNanos);
        if (file.statusCode() != 200) {
            throw new VoiceNoteException(
                    VoiceNoteOutcome.DOWNLOAD_FAILED,
                    "media download returned HTTP " + file.statusCode());
        }
        byte[] bytes = file.body();
        if (bytes == null || bytes.length == 0) {
            throw new VoiceNoteException(
                    VoiceNoteOutcome.DOWNLOAD_FAILED, "media download was empty");
        }
        if (bytes.length > maxBytes) {
            throw new VoiceNoteException(
                    VoiceNoteOutcome.TOO_LARGE, "media exceeds cap " + maxBytes);
        }
        return new DownloadedMedia(bytes, mime);
    }

    @Override
    public void sendAudio(
            String phoneNumberId,
            String accessToken,
            String to,
            byte[] audio,
            String mimeType,
            String filename) {
        mediaClient.sendWhatsAppMedia(
                phoneNumberId, accessToken, to, audio, mimeType, filename, null);
    }

    boolean isAllowedDownloadUri(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return false;
        }
        URI base = URI.create(graphBaseUrl);
        boolean sameOriginAsGraph =
                host.equalsIgnoreCase(base.getHost())
                        && scheme.equalsIgnoreCase(base.getScheme())
                        && uri.getPort() == base.getPort();
        if (sameOriginAsGraph) {
            return true;
        }
        if (!"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String suffix : ALLOWED_HOST_SUFFIXES) {
            if (lower.equals(suffix) || lower.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    private <T> HttpResponse<T> send(
            HttpRequest request, HttpResponse.BodyHandler<T> handler, long deadlineNanos) {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMillis <= 0) {
            throw new VoiceNoteException(
                    VoiceNoteOutcome.DOWNLOAD_FAILED, "download deadline exceeded");
        }
        CompletableFuture<HttpResponse<T>> future = client.sendAsync(request, handler);
        try {
            return future.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new VoiceNoteException(VoiceNoteOutcome.DOWNLOAD_FAILED, "download timed out");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new VoiceNoteException(VoiceNoteOutcome.DOWNLOAD_FAILED, "download interrupted");
        } catch (ExecutionException e) {
            // Only the exception type: the message can carry the request URL.
            throw new VoiceNoteException(
                    VoiceNoteOutcome.DOWNLOAD_FAILED,
                    "download failed ("
                            + (e.getCause() == null
                                    ? "unknown"
                                    : e.getCause().getClass().getSimpleName())
                            + ")");
        }
    }

    /**
     * A body handler that keeps at most {@code limitBytes} bytes and then cancels the stream (the
     * JDK's own {@code BodyHandlers.limiting} only exists from a later release than the Java 21
     * this project targets). Truncating, not failing, keeps the caller's "one byte over the cap
     * means too large" check working.
     */
    static HttpResponse.BodyHandler<byte[]> bounded(long limitBytes) {
        return responseInfo -> new BoundedBytesSubscriber(limitBytes);
    }

    private static final class BoundedBytesSubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private long total;

        BoundedBytesSubscriber(long limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                int available = item.remaining();
                int take = (int) Math.min(available, limit - total);
                if (take > 0) {
                    byte[] chunk = new byte[take];
                    item.get(chunk);
                    buffer.write(chunk, 0, take);
                    total += take;
                }
                if (total >= limit) {
                    subscription.cancel();
                    result.complete(buffer.toByteArray());
                    return;
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }
    }

    private static String baseMime(String mime) {
        if (mime == null || mime.isBlank()) {
            return null;
        }
        int semicolon = mime.indexOf(';');
        String base = semicolon >= 0 ? mime.substring(0, semicolon) : mime;
        return base.trim().toLowerCase(Locale.ROOT);
    }
}
