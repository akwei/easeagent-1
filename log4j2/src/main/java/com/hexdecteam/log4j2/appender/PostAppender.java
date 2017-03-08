package com.hexdecteam.log4j2.appender;

import okhttp3.*;
import okio.Buffer;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.apache.logging.log4j.core.config.plugins.*;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;

import java.io.IOException;
import java.net.URL;

@Plugin(name = "Http", category = "Core", elementType = Appender.ELEMENT_TYPE, printObject = true)
public class PostAppender extends AbstractAppender {

    private final OkHttpClient client;
    private final Request.Builder builder;
    private final MediaType contentType;

    public PostAppender(String name, Filter filter, Layout layout, boolean ignore,
                        OkHttpClient client, Request.Builder builder, MediaType contentType) {
        super(name, filter, layout, ignore);
        this.client = client;
        this.builder = builder;
        this.contentType = contentType;
    }

    public void append(LogEvent event) {
        final RequestBody body = RequestBody.create(contentType, event.getMessage().getFormattedMessage());

        try {
            final Response response = client.newCall(builder.post(body).build()).execute();
            final boolean successful = response.isSuccessful();
            response.close();
            if (!successful) {
                throw new AppenderLoggingException("HTTP response: " + response.message());
            }
        } catch (IOException e) {
            throw new AppenderLoggingException(e);
        }
    }

    @PluginFactory
    public static PostAppender createHTTPAppender(
            @Required @PluginAttribute("name") final String name,
            @PluginAttribute(value = "ignoreExceptions") final boolean ignore,
            @PluginElement("Filter") final Filter filter,
            @PluginElement("Layout") final Layout layout,
            @Required @PluginAttribute("uri") final URL uri,
            @Required @PluginAttribute("contentType") final String contentType,
            @Required @PluginAttribute("userAgent") final String userAgent,
            @PluginAttribute("compress") final boolean compress,
            @PluginElement("Headers") final Header[] headers

    ) throws Exception {
        final Request.Builder builder = new Request.Builder().url(uri).header("User-Agent", userAgent);
        for (Header header : headers) {
            builder.header(header.name, header.value);
        }
        return new PostAppender(name, filter, layout, ignore, client(compress), builder, MediaType.parse(contentType));
    }

    private static OkHttpClient client(boolean compress) {
        final OkHttpClient.Builder builder = new OkHttpClient.Builder();
        if (compress) builder.addInterceptor(new GzipRequestInterceptor());
        return builder.build();
    }

    @Plugin(name = "header", category = "Core", printObject = true)
    public static class Header {
        private final String name;
        private final String value;

        private Header(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @PluginFactory
        public static Header createHeader(
                @Required @PluginAttribute("name") String name,
                @Required @PluginValue("value") String value
        ) {
            return new Header(name, value);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Header header = (Header) o;

            return value != null ? value.equals(header.value) : header.value == null;
        }

        @Override
        public int hashCode() {
            return value != null ? value.hashCode() : 0;
        }

        @Override
        public String toString() {
            return "Header{value='" + value + '\'' + '}';
        }
    }

    static class GzipRequestInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request originalRequest = chain.request();
            if (originalRequest.body() == null || originalRequest.header("Content-Encoding") != null) {
                return chain.proceed(originalRequest);
            }

            Request compressedRequest = originalRequest.newBuilder()
                                                       .header("Content-Encoding", "gzip")
                                                       .method(originalRequest.method(), forceContentLength(gzip(originalRequest.body())))
                                                       .build();
            return chain.proceed(compressedRequest);
        }

        /**
         * https://github.com/square/okhttp/issues/350
         */
        private RequestBody forceContentLength(final RequestBody requestBody) throws IOException {
            final Buffer buffer = new Buffer();
            requestBody.writeTo(buffer);
            return new RequestBody() {
                @Override
                public MediaType contentType() {
                    return requestBody.contentType();
                }

                @Override
                public long contentLength() {
                    return buffer.size();
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    sink.write(buffer.snapshot());
                }
            };
        }

        private RequestBody gzip(final RequestBody body) {
            return new RequestBody() {
                @Override
                public MediaType contentType() {
                    return body.contentType();
                }

                @Override
                public long contentLength() {
                    return -1; // We don't know the compressed length in advance!
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    BufferedSink gzipSink = Okio.buffer(new GzipSink(sink));
                    body.writeTo(gzipSink);
                    gzipSink.close();
                }
            };
        }
    }

}
