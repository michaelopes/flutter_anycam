package br.dev.michaellopes.flutter_anycam.utils;

import android.os.SystemClock;

import java.util.function.LongSupplier;

public abstract class FrameRateLimiterUtil<T> {

    private final long frameIntervalMillis;
    private final LongSupplier clock;
    private boolean started = false;
    private long lastAnalyzedTime = 0;

    public FrameRateLimiterUtil(int targetFps) {
        this(targetFps, SystemClock::elapsedRealtime);
    }

    FrameRateLimiterUtil(int targetFps, LongSupplier clock) {
        if (targetFps <= 0)
            throw new IllegalArgumentException("FPS precisa ser > 0");

        this.frameIntervalMillis = 1000L / targetFps;
        this.clock = clock;
    }

    /**
     * Time-gate without owning the frame. Useful for Camera2 {@code Image} callbacks
     * where the buffer is closed when the callback returns.
     */
    public final synchronized boolean shouldProcessFrame() {
        long currentTime = clock.getAsLong();
        if (!started) {
            started = true;
            lastAnalyzedTime = currentTime;
            return true;
        }
        if (currentTime >= lastAnalyzedTime + frameIntervalMillis) {
            lastAnalyzedTime += frameIntervalMillis;
            // After a stall (network hiccup, GC, paused decoder) resync instead of
            // releasing a burst of back-to-back frames to catch up.
            if (currentTime - lastAnalyzedTime >= frameIntervalMillis) {
                lastAnalyzedTime = currentTime;
            }
            return true;
        }
        return false;
    }

    /** Same test as {@link #shouldProcessFrame()} without consuming the slot. */
    public final synchronized boolean isFrameDue() {
        return !started || clock.getAsLong() >= lastAnalyzedTime + frameIntervalMillis;
    }

    public final void onNewFrame(T data) {
        if (shouldProcessFrame()) {
            onFrameLimited(data);
        } else {
            onFrameSkipped(data);
        }
    }

    protected abstract void onFrameLimited(T data);

    protected void onFrameSkipped(T data) {
    }
}
