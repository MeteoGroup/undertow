/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.server.handlers.encoding;

import com.meteogroup.jbrotli.BrotliDeCompressor;
import io.undertow.io.IoCallback;
import io.undertow.predicate.Predicates;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpClientUtils;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Random;

/**
 * @author Martin W. Kirst
 */
@RunWith(DefaultServer.class)
public class BrotliContentEncodingTestCase {

    private static final int MAX_BUFFER_SIZE_FOR_THIS_TEST = 691963;
    private static final int MULTIPLIER_UPPER_BOUND = 10;

    private static volatile String message;

    @BeforeClass
    public static void setup() {
        final EncodingHandler handler = new EncodingHandler(new ContentEncodingRepository()
                .addEncodingHandler("br", new BrotliEncodingProvider(), 50, Predicates.parse("max-content-size[5]")))
                .setNext(new HttpHandler() {
                    @Override
                    public void handleRequest(HttpServerExchange exchange) throws Exception {
                        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, message.length() + "");
                        exchange.getResponseSender().send(message, IoCallback.END_EXCHANGE);
                    }
                });
        DefaultServer.setRootHandler(handler);
    }

    /**
     * Tests the use of the brotli contentent encoding
     *
     * @throws IOException
     */
    @Test
    public void testBrotliEncoding() throws IOException {
        runTest("Hello World");
    }


    /**
     * This message should not be compressed as it is too small
     *
     * @throws IOException
     */
    @Test
    public void testSmallMessagePredicateDoesNotCompress() throws IOException {
        DefaultHttpClient client = new DefaultHttpClient();
        try {
            message = "Hi";
            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/path");
            get.setHeader(Headers.ACCEPT_ENCODING_STRING, "br");
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            Header[] header = result.getHeaders(Headers.CONTENT_ENCODING_STRING);
            Assert.assertEquals(0, header.length);
            final String body = HttpClientUtils.readResponse(result);
            Assert.assertEquals("Hi", body);
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testBrotliEncodingLargeResponse() throws IOException {
        final StringBuilder messageBuilder = new StringBuilder(MAX_BUFFER_SIZE_FOR_THIS_TEST);
        for (int i = 0; i < MAX_BUFFER_SIZE_FOR_THIS_TEST; ++i) {
            messageBuilder.append("*");
        }
        runTest(messageBuilder.toString());
    }

    @Test
    public void testGzipEncodingRandomSizeResponse() throws IOException {
        int seed = new Random().nextInt();
        System.out.println("Using seed " + seed);
        try {
            final Random random = new Random(seed);
            int size = 1 + random.nextInt(MAX_BUFFER_SIZE_FOR_THIS_TEST);
            final StringBuilder messageBuilder = new StringBuilder(size);
            for (int i = 0; i < size; ++i) {
                messageBuilder.append('*' + random.nextInt(MULTIPLIER_UPPER_BOUND));
            }
            runTest(messageBuilder.toString());
        } catch (Exception e) {
            throw new RuntimeException("Test failed with seed " + seed, e);
        }
    }

    public void runTest(final String expectedMessage) throws IOException {
        DefaultHttpClient client = new DefaultHttpClient();
        try {
            message = expectedMessage;
            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/path");
            get.setHeader(Headers.ACCEPT_ENCODING_STRING, "br");
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            Header[] header = result.getHeaders(Headers.CONTENT_ENCODING_STRING);
            Assert.assertEquals("br", header[0].getValue());
            byte[] rawBody = HttpClientUtils.readRawResponse(result);
            String body = decompressBrotli(rawBody);
            Assert.assertEquals(expectedMessage, body);
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    private String decompressBrotli(byte[] compressedBytes) {
        BrotliDeCompressor brotliDeCompressor = new BrotliDeCompressor();
        byte[] buf = new byte[MAX_BUFFER_SIZE_FOR_THIS_TEST * MULTIPLIER_UPPER_BOUND];
        int length = brotliDeCompressor.deCompress(compressedBytes, buf);
        return new String(buf, 0, length);
    }
}
