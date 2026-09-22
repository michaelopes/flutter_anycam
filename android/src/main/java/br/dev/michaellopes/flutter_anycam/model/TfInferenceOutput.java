package br.dev.michaellopes.flutter_anycam.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TfInferenceOutput {

    public TfInferenceOutputBox box;
    public TfInferenceOutputKeypoint keypoint;
    public List<Double> embedding;
    public List<List<Double>> segmentation;
    public String text;

    public double score;

    public TfInferenceOutput() {}

    @SuppressWarnings("unchecked")
    public static TfInferenceOutput fromMap(Map<String, Object> map) {
        TfInferenceOutput result = new TfInferenceOutput();
        result.score = ((Number) map.get("score")).doubleValue();
        if (map.get("box") != null) {
            result.box = TfInferenceOutputBox.fromMap(
                    (Map<String, Object>) map.get("box")
            );
        }

        if (map.get("keypoint") != null) {
            result.keypoint = TfInferenceOutputKeypoint.fromMap(
                    (Map<String, Object>) map.get("keypoint")
            );
        }

        if (map.get("embedding") != null) {
            List<Object> list = (List<Object>) map.get("embedding");
            result.embedding = new ArrayList<>();

            for (Object o : list) {
                result.embedding.add(((Number) o).doubleValue());
            }
        }

        if (map.get("segmentation") != null) {
            List<Object> outer = (List<Object>) map.get("segmentation");
            result.segmentation = new ArrayList<>();

            for (Object rowObj : outer) {
                List<Object> inner = (List<Object>) rowObj;
                List<Double> row = new ArrayList<>();

                for (Object o : inner) {
                    row.add(((Number) o).doubleValue());
                }

                result.segmentation.add(row);
            }
        }

        if (map.get("text") != null) {
            result.text = (String) map.get("text");
        }

        return result;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("score", score);
        if (box != null) map.put("box", box.toMap());
        if (keypoint != null) map.put("keypoint", keypoint.toMap());
        if (embedding != null) map.put("embedding", embedding);
        if (segmentation != null) map.put("segmentation", segmentation);
        if (text != null) map.put("text", text);

        return map;
    }
}