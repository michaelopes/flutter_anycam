package br.dev.michaellopes.flutter_anycam.integration;

import io.flutter.plugin.common.EventChannel;

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

public class FlutterEventChannel implements  EventChannel.StreamHandler {


    private  static FlutterEventChannel INSTANCE;
    private EventChannel.EventSink attachEvent;

    private Handler uiHandler = new Handler(Looper.getMainLooper());

    private  FlutterEventChannel () {}

    public static FlutterEventChannel getINSTANCE() {
        if(INSTANCE == null) {
            INSTANCE = new FlutterEventChannel();
        }
        return INSTANCE;
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        attachEvent = events;
        System.out.println("StreamHandler - onCreated: ");
    }

    @Override
    public void onCancel(Object arguments) {
        attachEvent = null;
    }

    public void send(int viewId, String method, Map data) {
        HashMap result = new HashMap();
        result.put("viewId", viewId);
        result.put("method", method);
        result.put("data", data);
        uiHandler.post(() -> {
            if(attachEvent != null) {
                try {
                    attachEvent.success(result);
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        });
    }


}
