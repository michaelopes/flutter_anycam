package br.dev.michaellopes.flutter_anycam.camera;

import java.util.HashMap;
import java.util.Map;

import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;

public class CameraBridge {
    final int viewId;

    public CameraBridge(int viewId) {
        this.viewId = viewId;
    }


    public void onConnected(Map<String, Object> data) {
        FlutterEventChannel.getInstance().send(viewId, "onConnected", data);
    }

    public void onDisconnected() {
        FlutterEventChannel.getInstance().send(viewId, "onDisconnected", new HashMap());
    }

    public void onUnauthorized() {
        FlutterEventChannel.getInstance().send(viewId, "onUnauthorized", new HashMap());
    }

    public void onFailed(String message) {
        FlutterEventChannel.getInstance().send(viewId, "onFailed", new HashMap() {{
            put("message", message);
        }});
    }

    public void  onVideoFrameReceived(Map<String, Object> imageData) {
        FlutterEventChannel.getInstance().send(viewId, "onVideoFrameReceived", imageData);
    }
}
