package br.dev.michaellopes.flutter_anycam.model;


import android.graphics.Rect;
import android.util.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import br.dev.michaellopes.flutter_anycam.tensorflow.TfFrameHandler;

public class TfInferenceInput {

    public final String modelKey;
    public final String normalize;
    public TfFrameHandler.TfFrame inputFrame;
    public int filter;
    public Size inputSize;
    private final OutputProcessor processor;


    public TfInferenceInput(Map<String, Object> map, OutputProcessor processor) {

        modelKey = (String) map.get("modelKey");
        normalize = (String) map.get("normalize");
        this.processor = processor;

        Object filterObj = map.get("filter");
        if (filterObj instanceof Number) {
            filter = ((Number) filterObj).intValue();
        }

        Object inputFrameMap = map.get("inputFrame");
        if (inputFrameMap instanceof Map) {
            final String frameId = (String) ((Map<?, ?>) inputFrameMap).get("id");
            inputFrame = TfFrameHandler.getInstance().getFrameById(frameId);
        }

        Object inputSizeMap = map.get("inputSize");
        if (inputSizeMap instanceof Map) {
            Integer width = (Integer) ((Map<?, ?>) inputSizeMap).get("width");
            Integer height = (Integer) ((Map<?, ?>) inputSizeMap).get("height");
            this.inputSize = new Size(width, height);
        }

    }

    public List<TfInferenceOutput> processOutput(Map<Integer, Object> data) {
        try {
            List<Map<String, Object>> outs = processor.run(data, modelKey);
            List<TfInferenceOutput> result = new ArrayList<>();
            for (Map<String, Object> map : outs) {
                result.add(TfInferenceOutput.fromMap(map));
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public interface OutputProcessor {
        List<Map<String, Object>> run(Map<Integer, Object> data, String modelKey) throws ExecutionException, InterruptedException;
    }
}