package com.agenticai.core.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.AbstractClientHttpRequestFactoryWrapper;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Buffers the REQUEST body so the transport always sends a real {@code Content-Length}, and hands
 * the delegate's RESPONSE back untouched.
 *
 * <p>Spring's {@code BufferingClientHttpRequestFactory} does both directions. Its response wrapper
 * ({@code BufferingClientHttpResponseWrapper.getBody}) was the only frame seen pinning carrier
 * threads in the 32-user kind benchmark: the raw traces mark the monitor ("<== monitors:1") on that
 * frame. Its bytecode has a {@code monitorenter} in {@code getBody} (a {@code synchronized} block
 * around {@code StreamUtils.copyToByteArray}), so the virtual thread parked in the JDK client's
 * body stream ({@code HttpResponseInputStream.current}, {@code ArrayBlockingQueue.take}) with the
 * monitor held. The JDK stream itself only uses j.u.c locks, so once the wrapper is gone nothing in
 * that read holds a monitor. Only the request side is needed for the Content-Length guarantee
 * described on {@link SidecarClient}, so this factory keeps that and returns the delegate's
 * response untouched. Not yet re-measured under load.
 */
final class RequestBufferingClientHttpRequestFactory
        extends AbstractClientHttpRequestFactoryWrapper {

    RequestBufferingClientHttpRequestFactory(ClientHttpRequestFactory delegate) {
        super(delegate);
    }

    @Override
    protected ClientHttpRequest createRequest(
            URI uri, HttpMethod httpMethod, ClientHttpRequestFactory requestFactory)
            throws IOException {
        return new BufferedRequest(requestFactory.createRequest(uri, httpMethod));
    }

    private static final class BufferedRequest implements ClientHttpRequest {

        private final ClientHttpRequest delegate;
        private final HttpHeaders headers = new HttpHeaders();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);

        BufferedRequest(ClientHttpRequest delegate) {
            this.delegate = delegate;
        }

        @Override
        public HttpMethod getMethod() {
            return delegate.getMethod();
        }

        @Override
        public URI getURI() {
            return delegate.getURI();
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public OutputStream getBody() {
            return buffer;
        }

        @Override
        public ClientHttpResponse execute() throws IOException {
            byte[] bytes = buffer.toByteArray();
            if (headers.getContentLength() < 0) {
                headers.setContentLength(bytes.length);
            }
            delegate.getHeaders().putAll(headers);
            if (bytes.length > 0) {
                delegate.getBody().write(bytes);
            }
            return delegate.execute();
        }
    }
}
