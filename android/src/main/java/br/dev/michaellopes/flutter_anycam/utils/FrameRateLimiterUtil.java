package br.dev.michaellopes.flutter_anycam.utils;

import android.os.SystemClock;

public abstract class FrameRateLimiterUtil<T> {

    private final long frameIntervalMillis;
    private long lastAnalyzedTime = 0;

    public FrameRateLimiterUtil(int targetFps) {
        if (targetFps <= 0) throw new IllegalArgumentException("FPS precisa ser > 0");
        this.frameIntervalMillis = 1000L / targetFps;
    }

    public final void onNewFrame(T data) {
        long currentTime = SystemClock.elapsedRealtime();
        if (currentTime - lastAnalyzedTime >= frameIntervalMillis) {
            lastAnalyzedTime = currentTime;
            onFrameLimited(data);
        } else {
            onFrameSkipped(data);
        }
    }

    protected abstract void onFrameLimited(T data);

    protected void onFrameSkipped(T data) {
    }
}
