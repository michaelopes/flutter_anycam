

package br.dev.michaellopes.flutter_anycam.integration;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.flutter.plugin.common.EventChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class FlutterEventChannel implements EventChannel.StreamHandler {
    private static final String TAG = "FlutterEventChannel";

    private volatile EventChannel.EventSink sink;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private FlutterEventChannel() {}


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
        Log.d(TAG, "onCancel: stopping dispatcher and clearing queue");
        this.sink = null;
    }

    public void send(int viewId, String method, Map<String, Object> data) {
        Map<String, Object> result = new HashMap<>(4);
        result.put("viewId", viewId);
        result.put("method", method);
        result.put("data", data);
        uiHandler.post(() -> {
            try {
                sink.success(result);
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
