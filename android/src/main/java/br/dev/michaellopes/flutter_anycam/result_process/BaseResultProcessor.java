package br.dev.michaellopes.flutter_anycam.result_process;

import java.util.Map;

public abstract class BaseResultProcessor<T> {
    public abstract Map<String, Object> process(T input, int width, int height, Integer customRotationDegrees);
}
