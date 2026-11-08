package br.dev.michaellopes.flutter_anycam.utils;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded pool of direct RGBA-sized buffers for GPU readback. {@link #acquire} returns null
 * once {@code maxInFlight} frames are unreleased, which is the pipeline's backpressure.
 */
public final class ReadbackBufferPool {

    public final class Frame {
        public final ByteBuffer buffer;
        public final int width;
        public final int height;
        public long timestampNs;
        private final int generation;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Frame(ByteBuffer buffer, int width, int height, int generation) {
            this.buffer = buffer;
            this.width = width;
            this.height = height;
            this.generation = generation;
        }

        /** Idempotent; may be called from any thread. */
        public void release() {
            if (released.compareAndSet(false, true)) {
                recycle(this);
            }
        }
    }

    private final int maxInFlight;
    private final ArrayDeque<ByteBuffer> free = new ArrayDeque<>();
    private int inFlight = 0;
    private int width = 0;
    private int height = 0;
    private int generation = 0;

    public ReadbackBufferPool(int maxInFlight) {
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be > 0");
        }
        this.maxInFlight = maxInFlight;
    }

    public synchronized Frame acquire(int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        if (width != this.width || height != this.height) {
            free.clear();
            this.width = width;
            this.height = height;
            generation++;
        }
        if (inFlight >= maxInFlight) {
            return null;
        }
        ByteBuffer buffer = free.poll();
        if (buffer == null) {
            buffer = ByteBuffer.allocateDirect(width * height * 4);
        }
        buffer.clear();
        inFlight++;
        return new Frame(buffer, width, height, generation);
    }

    public synchronized int inFlight() {
        return inFlight;
    }

    public synchronized int freeCount() {
        return free.size();
    }

    private synchronized void recycle(Frame frame) {
        inFlight--;
        if (frame.generation == generation) {
            free.push(frame.buffer);
        }
    }
}
