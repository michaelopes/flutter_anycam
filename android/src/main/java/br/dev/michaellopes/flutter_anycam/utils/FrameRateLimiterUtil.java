package br.dev.michaellopes.flutter_anycam.utils;

import android.os.SystemClock;

public abstract class FrameRateLimiterUtil<T> {

    private final long frameIntervalMillis;
    private long lastAnalyzedTime = 0;

    public FrameRateLimiterUtil(int targetFps) {
        if (targetFps <= 0)
            throw new IllegalArgumentException("FPS precisa ser > 0");

        this.frameIntervalMillis = 1000L / targetFps;
    }

    /**
     * Time-gate without owning the frame. Useful for Camera2 {@code Image} callbacks
     * where the buffer is closed when the callback returns.
     */
    public final boolean shouldProcessFrame() {
        long currentTime = SystemClock.elapsedRealtime();
        if (lastAnalyzedTime == 0) {
            lastAnalyzedTime = currentTime;
            return true;
        }
        if (currentTime >= lastAnalyzedTime + frameIntervalMillis) {
            lastAnalyzedTime += frameIntervalMillis;
            return true;
        }
        return false;
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
