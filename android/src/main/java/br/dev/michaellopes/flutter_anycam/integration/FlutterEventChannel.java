package br.dev.michaellopes.flutter_anycam.integration;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import io.flutter.plugin.common.EventChannel;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delivers camera events to Flutter on the main thread.
 * <p>
 * Important for low-end devices: we only shallow-copy the event map (keys/values).
 * Large {@code byte[]} frame buffers are shared (not deep-copied) to avoid doubling
 * NV21 allocations. Producers that reuse buffers must pass a {@link DeliveryListener} and
 * not touch them again until it fires (see {@code FrameDeliveryGate}).
 * <p>
 * Never clears the caller's map — shared maps are used by multi-bridge fan-out and
 * {@code BaseCamera} lastAction replay.
 */
public class FlutterEventChannel implements EventChannel.StreamHandler {
    private static final String TAG = "FlutterEventChannel";

    /** Most recently attached sink: target of events whose view has no owning engine. */
    private volatile EventChannel.EventSink sink;
    /** Insertion order = attach order, so detaching an engine falls back to the previous one. */
    private final Map<Object, EventChannel.EventSink> sinksByOwner = new LinkedHashMap<>();
    private final Map<Integer, Object> viewOwners = new HashMap<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private FlutterEventChannel() {
    }

    public static FlutterEventChannel getInstance() {
        return InstanceHolder.instance;
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        attachSink(this, events);
        Log.d(TAG, "onListen");
    }

    @Override
    public void onCancel(Object arguments) {
        Log.d(TAG, "onCancel");
        detachSink(this);
    }

    /** {@code owner} identifies one Flutter engine (its plugin instance). */
    public synchronized void attachSink(Object owner, EventChannel.EventSink events) {
        sinksByOwner.remove(owner);
        sinksByOwner.put(owner, events);
        sink = events;
    }

    public synchronized void detachSink(Object owner) {
        sinksByOwner.remove(owner);
        viewOwners.values().removeIf(o -> o == owner);
        EventChannel.EventSink latest = null;
        for (EventChannel.EventSink s : sinksByOwner.values()) latest = s;
        sink = latest;
    }

    /**
     * Routes the events of {@code viewId} to the engine that created the view, so a second
     * engine (background isolate, add-to-app) never steals the UI's camera events.
     */
    public synchronized void bindView(int viewId, Object owner) {
        viewOwners.put(viewId, owner);
    }

    synchronized EventChannel.EventSink sinkFor(int viewId) {
        Object owner = viewOwners.get(viewId);
        if (owner == null) return sink;
        // The owning engine is not listening: nobody else knows this view.
        return sinksByOwner.get(owner);
    }

    /** Completion of a {@link #send}: {@code delivered} is false when no listener got it. */
    public interface DeliveryListener {
        void onComplete(boolean delivered);
    }

    public void send(int viewId, String method, Map<String, Object> data) {
        send(viewId, method, data, null);
    }

    /**
     * {@code listener} runs exactly once — on the main thread after the codec has encoded the
     * payload (buffers in {@code data} may then be reused), or immediately on the calling
     * thread when there is no sink.
     */
    public void send(int viewId, String method, Map<String, Object> data, DeliveryListener listener) {
        final EventChannel.EventSink currentSink = sinkFor(viewId);
        if (currentSink == null) {
            if (listener != null) listener.onComplete(false);
            return;
        }

        final Map<String, Object> payload = new HashMap<>(4);
        payload.put("viewId", viewId);
        payload.put("method", method);
        // Shallow copy: protects caller map from codec side-effects without cloning byte[].
        payload.put("data", data != null ? new HashMap<>(data) : null);

        boolean posted = uiHandler.post(() -> {
            boolean delivered = false;
            try {
                EventChannel.EventSink s = sinkFor(viewId);
                if (s != null) {
                    s.success(payload);
                    delivered = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error delivering event(s) to Flutter", t);
            } finally {
                if (listener != null) listener.onComplete(delivered);
            }
        });
        if (!posted && listener != null) {
            listener.onComplete(false);
        }
    }

    public synchronized void release() {
        sinksByOwner.clear();
        viewOwners.clear();
        sink = null;
    }

    private static final class InstanceHolder {
        static final FlutterEventChannel instance = new FlutterEventChannel();
    }
}
