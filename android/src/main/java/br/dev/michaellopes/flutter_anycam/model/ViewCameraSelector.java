package br.dev.michaellopes.flutter_anycam.model;

import java.util.HashMap;
import java.util.Map;

public class ViewCameraSelector {
    private final String id;
    private final String name;
    private final String lensFacing;
    private final int sensorOrientation;

    private ViewCameraSelectorRTSP cameraSelectorRTSP;


    public ViewCameraSelector(String id, String name, String lensFacing, int sensorOrientation) {
        this.id = id;
        this.name = name;
        this.lensFacing = lensFacing;
        this.sensorOrientation = sensorOrientation;
    }


    public ViewCameraSelectorRTSP getCameraSelectorRTSP() {
        return cameraSelectorRTSP;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLensFacing() {
        return lensFacing;
    }

    public int getSensorOrientation() {
        return sensorOrientation;
    }

    public static ViewCameraSelector fromMap(Map<String, Object> map) {
        String id = (String) map.get("id");
        String name = (String) map.get("name");
        String lensFacing = (String) map.get("lensFacing");
        int sensorOrientation = (int) map.get("sensorOrientation");

        ViewCameraSelector cameraSelector = new ViewCameraSelector(id, name, lensFacing, sensorOrientation);

        if(map.get("url") != null && map.get("username") != null &&  map.get("password") != null) {
            String url = (String) map.get("url");
            String username = (String) map.get("username");
            String password = (String) map.get("password");
            cameraSelector.cameraSelectorRTSP = new ViewCameraSelectorRTSP(url, username, password);
        }

        return cameraSelector;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("lensFacing", lensFacing);
        map.put("sensorOrientation", sensorOrientation);
        return map;
    }

    public static class ViewCameraSelectorRTSP {
        public final String url;
        public final String username;
        public final String password;

        public ViewCameraSelectorRTSP(String url, String username, String password) {
            this.url = url;
            this.username = username;
            this.password = password;
        }
    }
}
