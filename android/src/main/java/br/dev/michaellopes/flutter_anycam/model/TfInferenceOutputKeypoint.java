package br.dev.michaellopes.flutter_anycam.model;

import java.util.HashMap;
import java.util.Map;

public class TfInferenceOutputKeypoint {

    public double x;
    public double y;
    public Double score; // nullable

    public TfInferenceOutputKeypoint(double x, double y, Double score) {
        this.x = x;
        this.y = y;
        this.score = score;
    }

    public static TfInferenceOutputKeypoint fromMap(Map<String, Object> map) {
        double x = ((Number) map.get("x")).doubleValue();
        double y = ((Number) map.get("y")).doubleValue();

        Double score = null;
        if (map.get("score") != null) {
            score = ((Number) map.get("score")).doubleValue();
        }

        return new TfInferenceOutputKeypoint(x, y, score);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();

        map.put("x", x);
        map.put("y", y);
        map.put("score", score);

        return map;
    }
}
