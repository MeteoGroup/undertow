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

package io.undertow.conduits;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.ConduitFactory;
import org.xnio.conduits.StreamSinkConduit;
import org.xnio.conduits.WriteReadyHandler;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author Martin W. Kirst
 */
abstract class AbstractStreamSinkConduit {

    protected final ConduitFactory<StreamSinkConduit> conduitFactory;
    protected final HttpServerExchange exchange;
    protected StreamSinkConduit next;
    protected WriteReadyHandler writeReadyHandler;

    protected AbstractStreamSinkConduit(final ConduitFactory<StreamSinkConduit> conduitFactory, final HttpServerExchange exchange) {
        this.conduitFactory = conduitFactory;
        this.exchange = exchange;
    }

    public long write(final ByteBuffer[] srcs, final int offset, final int length) throws IOException {
        try {
            int totalLength = 0;
            for (int i = offset, len = offset + length; i < len; ++i) {
                if (srcs[i].hasRemaining()) {
                    int written = write(srcs[i]);
                    totalLength += written;
                    if (written == 0) break;
                }
            }
            return totalLength;
        } catch (IOException e) {
            freeBuffer();
            throw e;
        }
    }

    public abstract int write(ByteBuffer src) throws IOException;

    public void setWriteReadyHandler(final WriteReadyHandler handler) {
        this.writeReadyHandler = handler;
    }

    protected void queueWriteListener() {
        exchange.getConnection().getIoThread().execute(new Runnable() {
            @Override
            public void run() {
                if (writeReadyHandler != null) {
                    try {
                        writeReadyHandler.writeReady();
                    } finally {
                        //if writes are still resumed queue up another one
                        if (next == null && isWriteResumed()) {
                            queueWriteListener();
                        }
                    }
                }
            }
        });
    }

    protected abstract void freeBuffer();

    public abstract boolean isWriteResumed();

}
