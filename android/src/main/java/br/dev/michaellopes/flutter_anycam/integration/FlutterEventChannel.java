

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
    private static final int QUEUE_CAPACITY = 64;
    private static final int DRAIN_BATCH_MAX = 32;

    private volatile EventChannel.EventSink sink;

    private final BlockingQueue<Map<String, Object>> queue =
            new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean dispatcherStarted = new AtomicBoolean(false);
    private ExecutorService dispatcher;

    private FlutterEventChannel() {}


    public static FlutterEventChannel getInstance() {
        return InstanceHolder.instance;
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        this.sink = events;
        startDispatcherIfNeeded();
        Log.d(TAG, "onListen");
    }

    @Override
    public void onCancel(Object arguments) {
        Log.d(TAG, "onCancel: stopping dispatcher and clearing queue");
        this.sink = null;
        clearQueue();
        stopDispatcher();
    }

    public void send(int viewId, String method, Map<String, Object> data) {
        Map<String, Object> result = new HashMap<>(4);
        result.put("viewId", viewId);
        result.put("method", method);
        result.put("data", data);

        if (!queue.offer(result)) {
            queue.poll();
            if (!queue.offer(result)) {
                Log.w(TAG, "Queue saturatedQueue saturat; dropping newest event: " + method);
                return;
            }
        }
        startDispatcherIfNeeded();
    }

    private void startDispatcherIfNeeded() {
        if (dispatcherStarted.compareAndSet(false, true)) {
            dispatcher = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "FlutterEventDispatcher");
                t.setDaemon(true);
                return t;
            });
            dispatcher.execute(this::dispatchLoop);
        }
    }

    private void dispatchLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {

                final Map<String, Object> first = queue.take();

                final List<Map<String, Object>> batch = new ArrayList<>();
                batch.add(first);
                queue.drainTo(batch, DRAIN_BATCH_MAX - 1);

                final EventChannel.EventSink currentSink = this.sink;
                if (currentSink != null) {
                    uiHandler.post(() -> {
                        try {
                            for (Map<String, Object> payload : batch) {
                                currentSink.success(payload);
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "Error delivering event(s) to Flutter", t);
                        }
                    });
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.e(TAG, "Dispatcher crashed", t);
        } finally {
            dispatcherStarted.set(false);
        }
    }

    private void stopDispatcher() {
        if (dispatcher != null) {
            dispatcher.shutdownNow();
            try {
                dispatcher.awaitTermination(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            dispatcher = null;
            dispatcherStarted.set(false);
        }
    }

    private void clearQueue() {
        queue.clear();
    }

    public void release() {
        sink = null;
        clearQueue();
        stopDispatcher();
    }

    private static final class InstanceHolder {
        static final FlutterEventChannel instance = new FlutterEventChannel();
    }

}


//package br.dev.michaellopes.flutter_anycam.integration;
//
//import io.flutter.plugin.common.EventChannel;
//
//import android.os.Handler;
//import android.os.Looper;
//
//import java.util.HashMap;
//import java.util.Map;
//import java.util.concurrent.BlockingQueue;
//import java.util.concurrent.LinkedBlockingQueue;
//
//public class FlutterEventChannel implements  EventChannel.StreamHandler {
//
//    private boolean running;
//
//    private final BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(80);
//
//    private  static FlutterEventChannel instance;
//    private EventChannel.EventSink attachEvent;
//
//    private Handler uiHandler = new Handler(Looper.getMainLooper());
//
//    private  FlutterEventChannel () {}
//
//    public static FlutterEventChannel getInstance() {
//        if(instance == null) {
//            instance = new FlutterEventChannel();
//        }
//        return instance;
//    }
//
//    @Override
//    public void onListen(Object arguments, EventChannel.EventSink events) {
//        attachEvent = events;
//        System.out.println("StreamHandler - onCreated: ");
//    }
//
//    @Override
//    public void onCancel(Object arguments) {
//        attachEvent = null;
//    }
//
//    public synchronized void send(int viewId, String method, Map data) {
//        HashMap result = new HashMap();
//        result.put("viewId", viewId);
//        result.put("method", method);
//        result.put("data", data);
//        queue.offer(result);
//        startDispatcher();
//    }
//
//    private void startDispatcher() {
//        if (!running) {
//            running = true;
//            new Thread(() -> {
//                while (running) {
//                    try {
//                        Map<String, Object> payload = queue.take();
//                        uiHandler.post(() -> {
//                            if (attachEvent != null) attachEvent.success(payload);
//                        });
//                    } catch (InterruptedException e) {
//                        Thread.currentThread().interrupt();
//                        running = false;
//                        break;
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                        running = false;
//                        break;
//                    }
//                }
//            }, "FlutterEventDispatcher").start();
//        }
//    }
//
//    public void release() {
//        running = false;
//    }
//
//}
