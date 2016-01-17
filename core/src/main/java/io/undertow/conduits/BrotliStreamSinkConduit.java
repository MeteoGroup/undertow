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

import com.meteogroup.jbrotli.Brotli;
import com.meteogroup.jbrotli.BrotliException;
import com.meteogroup.jbrotli.BrotliStreamCompressor;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.ConduitFactory;
import io.undertow.util.Headers;
import org.xnio.IoUtils;
import org.xnio.XnioIoThread;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSourceChannel;
import org.xnio.conduits.ConduitWritableByteChannel;
import org.xnio.conduits.Conduits;
import org.xnio.conduits.StreamSinkConduit;
import org.xnio.conduits.WriteReadyHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.min;
import static java.nio.ByteBuffer.allocate;
import static org.xnio.Bits.anyAreSet;

/**
 * @author Martin W. Kirst
 */
public class BrotliStreamSinkConduit extends AbstractStreamSinkConduit implements StreamSinkConduit {

    private static final int SHUTDOWN = 1;
    private static final int NEXT_SHUTDOWN = 1 << 1;
    private static final int FLUSHING_BUFFER = 1 << 2;
    private static final int WRITES_RESUMED = 1 << 3;
    private static final int CLOSED = 1 << 4;

    private final BrotliStreamCompressor brotliStreamCompressor;

    private ByteBuffer currentBuffer;
    private int state = 0;

    public BrotliStreamSinkConduit(ConduitFactory<StreamSinkConduit> factory, HttpServerExchange exchange) {
        super(factory, exchange);
        Brotli.Parameter brotliParameter = new Brotli.Parameter();
        brotliParameter.setQuality(5);
        brotliStreamCompressor =  new BrotliStreamCompressor(brotliParameter);
    }

    @Override
    public long transferFrom(final FileChannel src, final long position, final long count) throws IOException {
        if (anyAreSet(state, SHUTDOWN | CLOSED)) {
            throw new ClosedChannelException();
        }
        if (!performFlushIfRequired()) {
            return 0;
        }
        return src.transferTo(position, count, new ConduitWritableByteChannel(this));
    }

    @Override
    public long transferFrom(StreamSourceChannel source, long count, ByteBuffer throughBuffer) throws IOException {
        if (anyAreSet(state, SHUTDOWN | CLOSED)) {
            throw new ClosedChannelException();
        }
        if (!performFlushIfRequired()) {
            return 0;
        }
        return IoUtils.transfer(source, count, throughBuffer, new ConduitWritableByteChannel(this));
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (anyAreSet(state, SHUTDOWN | CLOSED)) {
            throw new ClosedChannelException();
        }
        final int originLimit = src.limit();
        try {
            if (!performFlushIfRequired()) {
                return 0;
            }
            int remaining = src.remaining();
            if (remaining == 0) {
                return 0;
            }
            boolean nextCreated = false;
            final int maxInputBufferSize = brotliStreamCompressor.getMaxInputBufferSize();
            try {
                for (int i = src.position(); i < originLimit; i += maxInputBufferSize) {
                    src.limit(min(originLimit, src.position() + maxInputBufferSize));
                    currentBuffer = brotliStreamCompressor.compress(src, false);
                    if (currentBuffer.hasRemaining()) {
                        this.state |= FLUSHING_BUFFER;
                        if (next == null) {
                            nextCreated = true;
                            this.next = createNextChannel();
                        }
                        performFlushIfRequired();
                    }
                }
            } finally {
                src.limit(originLimit);
                if (nextCreated) {
                    if (anyAreSet(state, WRITES_RESUMED)) {
                        next.resumeWrites();
                    }
                }
            }
            return remaining;
        } catch (IOException | BrotliException e) {
            freeBuffer();
            throw e;
        }
    }

    @Override
    public long write(final ByteBuffer[] srcs, final int offset, final int length) throws IOException {
        if (anyAreSet(state, SHUTDOWN | CLOSED)) {
            throw new ClosedChannelException();
        }
        return super.write(srcs, offset, length);
    }

    @Override
    public int writeFinal(ByteBuffer src) throws IOException {
        return Conduits.writeFinalBasic(this, src);
    }

    @Override
    public long writeFinal(ByteBuffer[] srcs, int offset, int length) throws IOException {
        return Conduits.writeFinalBasic(this, srcs, offset, length);
    }

    @Override
    public void terminateWrites() throws IOException {
        state |= SHUTDOWN;
    }

    @Override
    public boolean isWriteShutdown() {
        return anyAreSet(state, SHUTDOWN);
    }

    @Override
    public void resumeWrites() {
        if (next == null) {
            state |= WRITES_RESUMED;
            queueWriteListener();
        } else {
            next.resumeWrites();
        }
    }

    @Override
    public void suspendWrites() {
        if (next == null) {
            state = state & ~WRITES_RESUMED;
        } else {
            next.suspendWrites();
        }
    }

    @Override
    public void wakeupWrites() {
        if (next == null) {
            resumeWrites();
        } else {
            next.wakeupWrites();
        }
    }

    @Override
    public boolean isWriteResumed() {
        if (next == null) {
            return anyAreSet(state, WRITES_RESUMED);
        } else {
            return next.isWriteResumed();
        }
    }

    @Override
    public void awaitWritable() throws IOException {
        if (next != null) {
            next.awaitWritable();
        }
    }

    @Override
    public void awaitWritable(long l, TimeUnit timeUnit) throws IOException {
        if (next != null) {
            next.awaitWritable();
        }
    }

    @Override
    public XnioIoThread getWriteThread() {
        return exchange.getConnection().getIoThread();
    }

    @Override
    public void setWriteReadyHandler(final WriteReadyHandler handler) {
        this.writeReadyHandler = handler;
    }

    @Override
    public void truncateWrites() throws IOException {
        freeBuffer();
        state |= CLOSED;
        next.truncateWrites();
    }

    @Override
    public boolean flush() throws IOException {
        if (currentBuffer == null) {
            return !anyAreSet(state, NEXT_SHUTDOWN) || next.flush();
        } else {
            currentBuffer = brotliStreamCompressor.compress(allocate(0), true);
            if (currentBuffer.hasRemaining()) {
                this.state |= FLUSHING_BUFFER;
                if (next == null) {
                    this.next = createNextChannel();
                }
                performFlushIfRequired();
                next.flush();
            }
            freeBuffer();
        }
        return true;
    }

    @Override
    public XnioWorker getWorker() {
        return exchange.getConnection().getWorker();
    }

    /**
     * The we are in the flushing state then we flush to the underlying stream, otherwise just return true
     *
     * @return false if there is still more to flush
     */
    private boolean performFlushIfRequired() throws IOException {
        if (anyAreSet(state, FLUSHING_BUFFER)) {
            long res = next.write(currentBuffer);
            if (res == 0) {
                return false;
            }
            currentBuffer = null;
            state = state & ~FLUSHING_BUFFER;
        }
        return true;
    }

    @Override
    protected void freeBuffer() {
        if (currentBuffer != null) {
            currentBuffer = null;
        }
    }

    private StreamSinkConduit createNextChannel() {
        if (currentBuffer != null) {
            //the deflater was fully flushed before we created the channel. This means that what is in the buffer is
            //all there is
            int remaining = currentBuffer.remaining();
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Integer.toString(remaining));
        } else {
            exchange.getResponseHeaders().remove(Headers.CONTENT_LENGTH);
        }
        return conduitFactory.create();
    }
}
