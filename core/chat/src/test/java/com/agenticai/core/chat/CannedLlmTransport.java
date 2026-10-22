package com.agenticai.core.chat;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import okio.Buffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A stand-in for {@link LlmHttpClientFactory#transport}: the terminal interceptor of an SDK-based
 * model that records the request it would have sent (method, final URL, headers, body) and answers
 * with a canned response, so factories can be driven end to end with no network. Spring AI 1.x
 * tests did this by handing the factories a mock {@code RestClient} request factory; the SDK models
 * sit on OkHttp, whose equivalent seam is an interceptor.
 */
final class CannedLlmTransport implements Interceptor {

    /** One recorded request. */
    record Captured(String method, String url, okhttp3.Headers headers, String body) {}

    private final List<Captured> captured = new ArrayList<>();
    private final Supplier<String> reply;
    private final Supplier<Integer> status;

    CannedLlmTransport(Supplier<String> reply) {
        this(reply, () -> 200);
    }

    CannedLlmTransport(Supplier<String> reply, Supplier<Integer> status) {
        this.reply = reply;
        this.status = status;
    }

    List<Captured> captured() {
        return captured;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String body = "";
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            body = buffer.readString(StandardCharsets.UTF_8);
        }
        okhttp3.Headers headers = request.headers();
        if (request.body() != null
                && request.body().contentType() != null
                && headers.get("Content-Type") == null) {
            // OkHttp's BridgeInterceptor would add this from the body's media type on the real
            // path.
            headers =
                    headers.newBuilder()
                            .add("Content-Type", request.body().contentType().toString())
                            .build();
        }
        captured.add(new Captured(request.method(), request.url().toString(), headers, body));
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status.get())
                .message("canned")
                .header("Content-Type", "application/json")
                .body(ResponseBody.create(reply.get(), MediaType.get("application/json")))
                .build();
    }
}
