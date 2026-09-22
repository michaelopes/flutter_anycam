package br.dev.michaellopes.flutter_anycam.integration;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import io.flutter.plugin.common.EventChannel;

import java.util.HashMap;
import java.util.Map;

/**
 * Delivers camera events to Flutter on the main thread.
 * <p>
 * Important for low-end devices: we only shallow-copy the event map (keys/values).
 * Large {@code byte[]} frame buffers are shared (not deep-copied) to avoid doubling
 * NV21 allocations. Callers must not mutate/reuse those buffers until the next frame
 * slot is used (see ping-pong buffers in {@code ImageMapperUtil}).
 * <p>
 * Never clears the caller's map — shared maps are used by multi-bridge fan-out and
 * {@code BaseCamera} lastAction replay.
 */
public class FlutterEventChannel implements EventChannel.StreamHandler {
    private static final String TAG = "FlutterEventChannel";

    private volatile EventChannel.EventSink sink;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private FlutterEventChannel() {
    }

    public static FlutterEventChannel getInstance() {
        return InstanceHolder.instance;
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        this.sink = events;
        Log.d(TAG, "onListen");
    }

    @Override
    public void onCancel(Object arguments) {
        Log.d(TAG, "onCancel");
        this.sink = null;
    }

    public void send(int viewId, String method, Map<String, Object> data) {
        final EventChannel.EventSink currentSink = sink;
        if (currentSink == null) {
            return;
        }

        final Map<String, Object> payload = new HashMap<>(4);
        payload.put("viewId", viewId);
        payload.put("method", method);
        // Shallow copy: protects caller map from codec side-effects without cloning byte[].
        payload.put("data", data != null ? new HashMap<>(data) : null);

        uiHandler.post(() -> {
            EventChannel.EventSink s = sink;
            if (s == null) {
                return;
            }
            try {
                s.success(payload);
            } catch (Throwable t) {
                Log.e(TAG, "Error delivering event(s) to Flutter", t);
            }
        });
    }

    public void release() {
        sink = null;
    }

    private static final class InstanceHolder {
        static final FlutterEventChannel instance = new FlutterEventChannel();
    }
}
