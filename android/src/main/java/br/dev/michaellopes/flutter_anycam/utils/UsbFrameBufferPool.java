package br.dev.michaellopes.flutter_anycam.utils;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded pool of direct NV21-sized buffers ({@code width * height * 3 / 2}).
 * {@link #acquire} returns null once {@code maxInFlight} frames are unreleased —
 * that is the pipeline's backpressure (skip the native frame rather than overwrite).
 */
public final class UsbFrameBufferPool {

    public final class Frame {
        public final ByteBuffer buffer;
        public final int width;
        public final int height;
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

    public UsbFrameBufferPool(int maxInFlight) {
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
        int bytes = nv21Size(width, height);
        ByteBuffer buffer = free.poll();
        if (buffer == null || buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes);
        }
        buffer.clear();
        buffer.limit(bytes);
        inFlight++;
        return new Frame(buffer, width, height, generation);
    }

    /**
     * Copy {@code src} into a pooled frame. Returns null under backpressure or when
     * {@code src} is shorter than a full NV21 frame — native consumers read
     * {@code width * height * 3 / 2} bytes unconditionally.
     */
    public Frame copyOf(ByteBuffer src, int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        int needed = nv21Size(width, height);
        if (src.remaining() < needed) {
            return null;
        }
        Frame frame = acquire(width, height);
        if (frame == null) {
            return null;
        }
        ByteBuffer dst = frame.buffer;
        ByteBuffer view = src.duplicate();
        if (view.remaining() > needed) {
            view.limit(view.position() + needed);
        }
        dst.put(view);
        dst.flip();
        return frame;
    }

    public synchronized int inFlight() {
        return inFlight;
    }

    public synchronized int freeCount() {
        return free.size();
    }

    public static int nv21Size(int width, int height) {
        return width * height * 3 / 2;
    }

    private synchronized void recycle(Frame frame) {
        inFlight--;
        if (frame.generation == generation) {
            free.push(frame.buffer);
        }
    }
}
