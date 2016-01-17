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

package io.undertow.examples.fileserving;

import io.undertow.Undertow;
import io.undertow.examples.UndertowExample;
import io.undertow.predicate.Predicates;
import io.undertow.server.handlers.encoding.BrotliEncodingProvider;
import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.encoding.GzipEncodingProvider;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;

import java.io.File;

import static io.undertow.Handlers.resource;

/**
 * @author Martin W. Kirst
 */
@UndertowExample("File Serving")
public class FileServerWithActiveCompression {

    public static void main(final String[] args) {
        ResourceHandler resourceHandler = resource(new FileResourceManager(new File(System.getProperty("user.home")), 100))
                .setDirectoryListingEnabled(true);

        final EncodingHandler handler = new EncodingHandler(new ContentEncodingRepository()
                .addEncodingHandler("gzip", new GzipEncodingProvider(), 50, Predicates.parse("max-content-size[5]"))
                .addEncodingHandler("br", new BrotliEncodingProvider(), 100, Predicates.parse("max-content-size[5]")))
                .setNext(resourceHandler);

        Undertow server = Undertow.builder()
                .addHttpListener(8080, "localhost")
                .setHandler(handler)
                .build();
        server.start();
    }

}
