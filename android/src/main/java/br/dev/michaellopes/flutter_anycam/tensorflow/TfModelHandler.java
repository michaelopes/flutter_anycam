package br.dev.michaellopes.flutter_anycam.tensorflow;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;


import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import br.dev.michaellopes.flutter_anycam.model.TfInferenceInput;
import br.dev.michaellopes.flutter_anycam.model.TfInferenceOutput;
import br.dev.michaellopes.flutter_anycam.utils.ByteArrayPoolUtil;
import br.dev.michaellopes.flutter_anycam.utils.ByteBufferPoolUtil;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import io.flutter.FlutterInjector;

import io.flutter.Log;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.github.crow_misia.libyuv.ArgbBuffer;

public class TfModelHandler {
    private TfModelHandler() {
    }

    private volatile boolean processing = false;

    private final BlockingQueue<QueueItem> queue = new LinkedBlockingQueue<>(10);

    private ExecutorService queueExecutor;

    private static TfModelHandler instance;

    private final List<ModelItem> models = new ArrayList<>();
    private final List<InferenceResult> results = new ArrayList<>();

    public static synchronized TfModelHandler getInstance() {
        if (instance == null) instance = new TfModelHandler();
        return instance;
    }

    public void loadModel(String assetPath, String key, String delegate, Integer threads) {
        try {
            if (threads == null) {
                threads = 1;
            }

            Interpreter.Options options = new Interpreter.Options();

            switch (delegate) {
                case "gpu":
                    GpuDelegate gpuDelegate = new GpuDelegate();
                    options.addDelegate(gpuDelegate);
                case "xxnnpack":
                    options.setUseXNNPACK(true);
                    break;
                case "nnapi":
                    options.setUseNNAPI(true);
                    break;
            }

            if (threads > 0) {
                options.setNumThreads(threads);
            }

            Log.i("Selected threads: ", threads.toString());
            Interpreter interpreter = new Interpreter(loadModelFromAssets(assetPath), options);

            ByteBuffer reusableInput = null;
            ByteBuffer[] reusableOutputs = null;
            TensorMeta inputMeta = null;
            TensorMeta[] outputMetas = null;
            int inputLength = 0;

            try {
                // ---- INPUT ----
                Tensor inTensor = interpreter.getInputTensor(0);
                int[] inputShape = inTensor.shape();
                inputLength = 1;
                for (int dim : inputShape) inputLength *= dim;

                inputMeta = TensorMeta.from(inTensor);

                reusableInput = ByteBuffer.allocateDirect(inTensor.numBytes()).order(ByteOrder.nativeOrder());

                // ---- OUTPUTS ----
                int outCount = interpreter.getOutputTensorCount();
                reusableOutputs = new ByteBuffer[outCount];
                outputMetas = new TensorMeta[outCount];

                for (int i = 0; i < outCount; i++) {
                    Tensor t = interpreter.getOutputTensor(i);
                    outputMetas[i] = TensorMeta.from(t);
                    reusableOutputs[i] = ByteBuffer.allocateDirect(t.numBytes()).order(ByteOrder.nativeOrder());
                }
            } catch (Exception e) {
                interpreter.close();
                throw new RuntimeException(e);
            }

            ModelItem item = new ModelItem(
                    interpreter, key,
                    inputMeta, inputLength,
                    reusableOutputs, outputMetas
            );
            models.add(item);
            Log.d("TFLite", "Modelo carregado: " + key
                    + " | inputType=" + inputMeta.dataType);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void disposeModelByKey(String modelKey) {
        ModelItem item = getModelByKey(modelKey);
        if (item != null) {
            item.close();
            models.remove(item);
        }
        if (models.isEmpty()) {
            queueExecutor.shutdown();
            queueExecutor = null;
            processing = false;
        }
    }

    private void startProcessingWorker() {
        processing = true;
        if (queueExecutor == null) {
            queueExecutor = Executors.newFixedThreadPool(2);
        }
        queueExecutor.execute(() -> {
            while (!Thread.currentThread().isInterrupted() && processing) {
                try {
                    QueueItem task = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        processQueueItem(task);
                    } else {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            processing = false;
            if (!queue.isEmpty()) {
                startProcessingWorker();
            }
        });
    }

    public synchronized void runInference(TfInferenceInput input, InferenceCallback callback) {
        boolean added = queue.offer(new QueueItem(input, callback));
        if (added && !processing) {
            startProcessingWorker();
        }
    }


    public void closeTfInferenceResult(String inferenceResultId) {
        InferenceResult inferenceResult = getInferenceResult(inferenceResultId);
        if (inferenceResult != null) {
            inferenceResult.close();
        }
    }

    public InferenceResult getInferenceResult(String inferenceResultId) {
        synchronized (results) {
            InferenceResult res = null;
            for (InferenceResult item : results) {
                if (item.id.equals(inferenceResultId)) {
                    res = item;
                    break;
                }
            }
            return res;
        }
    }

    public TfFrameHandler.TfFrame getScaledCroppedFrame(String inferenceResultId) {
        InferenceResult res = getInferenceResult(inferenceResultId);

        if (res != null) {
            if(res.output.box == null) return  null;
            if(res.getScaledCroppedFrame() == null) {
                try {
                    TfFrameHandler.TfFrame frame = TfFrameHandler.getInstance().newFrameCroppedById(res.getMainFrame().id, res.output.box, true);
                    res.addScaledCroppedFrame(frame.id);
                    return frame;
                } catch (Exception e) {
                    return null;
                }
            } else {
                return res.getScaledCroppedFrame();
            }
        }
        return null;
    }

    public TfFrameHandler.TfFrame getCroppedFrame(String inferenceResultId) {
        InferenceResult res = getInferenceResult(inferenceResultId);
        if (res != null) {
            if(res.getCroppedFrame() == null) {
                try {
                    TfFrameHandler.TfFrame frame = TfFrameHandler.getInstance().newFrameCroppedById(res.getMainFrame().id, res.output.box, false);
                    res.addCroppedFrame(frame.id);
                    return frame;
                } catch (Exception e) {
                    return null;
                }
            } else {
                return res.getCroppedFrame();
            }
        }
        return null;
    }

    public TfFrameHandler.TfFrame getInferenceFrame(String inferenceResultId) {
        InferenceResult res = getInferenceResult(inferenceResultId);
        if (res != null) {
            return res.getMainFrame();
        }
        return null;
    }

    public TfFrameHandler.TfFrame getRawFrame(String inferenceResultId) {
        InferenceResult res = getInferenceResult(inferenceResultId);
        if (res != null) {
            return res.getMainFrame().getParentFrame();
        }
        return null;
    }

    private void processQueueItem(QueueItem queueItem) {
        long start = System.currentTimeMillis();
        TfInferenceInput input = queueItem.input;
        InferenceCallback callback = queueItem.callback;

        ModelItem item = getModelByKey(input.modelKey);
        if (item == null) {
            callback.error("TfLite model is not loaded");
            return;
        }

        Interpreter tflite = item.interpreter;
        ByteBuffer[] outputs = item.reusableOutputs;
        TensorMeta inMeta = item.inputMeta;

        try {


          /*if (float32List.length != item.inputLength) {
                callback.error("Tamanho do input inválido: esperado " + item.inputLength + " mas veio " + float32List.length);
                return;
            }*/

            Log.i("TFLite_QUANT", "inputMeta scale=" + inMeta.scale
                    + " invScale=" + inMeta.invScale
                    + " zeroPoint=" + inMeta.zeroPoint);

            ByteBufferPoolUtil.PoolItem inputItem;
            TfFrameHandler.TfFrame targetFrame = TfFrameHandler.getInstance().getTfFrameToInference(input);

            if (inMeta.dataType == DataType.UINT8) {
                inputItem = TfFrameNormalizer.getInstance().normalizeUint8(targetFrame.buffer, input.normalize);
            } else if (inMeta.dataType == DataType.INT8) {
                inputItem = TfFrameNormalizer.getInstance().normalizeInt8(targetFrame.buffer, input.normalize, inMeta.invScale, inMeta.zeroPoint);
            } else {
                inputItem = TfFrameNormalizer.getInstance().normalizeFloat32(targetFrame.buffer, input.normalize);
            }


            Map<Integer, Object> outputMap = new HashMap<>();
            for (int i = 0; i < outputs.length; i++) {
                outputs[i].rewind();
                outputMap.put(i, outputs[i]);
            }

            // RUN
            tflite.runForMultipleInputsOutputs(new Object[]{inputItem.buffer}, outputMap);

            // ---- OUTPUTS ----
            Map<Integer, Object> outs = new HashMap<>();
            for (int i = 0; i < outputs.length; i++) {
                Tensor outT = tflite.getOutputTensor(i);
                int[] shape = outT.shape();
                int outputLength = 1;
                for (int dim : shape) outputLength *= dim;
                ByteBuffer b = (ByteBuffer) outputMap.get(i);
                b.rewind();

                TensorMeta outMeta = item.outputMetas[i];
                float[] flatArray = new float[outputLength];

                if (outMeta.dataType == DataType.FLOAT32) {
                    for (int j = 0; j < outputLength; j++) {
                        flatArray[j] = b.getFloat();
                    }
                } else {
                    // INT8: dequantiza  f = (q - zeroPoint) * scale
                    final float outScale = outMeta.scale;
                    final int outZp = outMeta.zeroPoint;
                    for (int j = 0; j < outputLength; j++) {
                        // Converte byte signed → unsigned (0–255)
                        int rawByte = b.get();
                        flatArray[j] = (rawByte - outZp) * outScale;
                    }
                }

                Object structured = reshape(flatArray, shape, 0);
                outs.put(i, structured);
            }

            inputItem.release();

            List<TfInferenceOutput> processedOutputs = input.processOutput(outs);

            List<Map<String, Object>> response = new ArrayList<>();
            for (TfInferenceOutput it : processedOutputs) {
                InferenceResult res = new InferenceResult(targetFrame.id, it);
                this.results.add(res);
                response.add(res.toMap());
            }

            if(response.isEmpty()) {
                targetFrame.close();
            }

            callback.success(response);
            long inferenceMs = System.currentTimeMillis() - start;
            Log.d("TFLite_PERF", "inference=" + inferenceMs + "ms | queue=" + queue.size());
        } catch (Exception e) {
            Log.e("TFLite", "Erro na inferência: " + e.getMessage());
            callback.error(e.getMessage());
        }
    }

    private Object reshape(float[] flat, int[] shape, int offset) {
        if (shape.length == 1) {
            float[] arr = new float[shape[0]];
            System.arraycopy(flat, offset, arr, 0, shape[0]);
            return arr;
        }

        int step = 1;
        for (int i = 1; i < shape.length; i++) step *= shape[i];

        // List<Object> em vez de Object[] — o StandardMessageCodec serializa List diretamente
        List<Object> list = new ArrayList<>(shape[0]);
        int[] subShape = Arrays.copyOfRange(shape, 1, shape.length);
        for (int i = 0; i < shape[0]; i++) {
            list.add(reshape(flat, subShape, offset + i * step));
        }

        return list;
    }


    private ModelItem getModelByKey(String key) {
        Object[] filter = models.stream().filter(item -> item.key.equals(key)).toArray();
        if (filter.length >= 1) {
            return ((ModelItem) filter[0]);
        }
        return null;
    }

    private MappedByteBuffer loadModelFromAssets(String assetPath) throws IOException {
        AssetManager assetManager = ContextUtil.get().getAssets();
        FlutterLoader loader = FlutterInjector.instance().flutterLoader();
        String key = loader.getLookupKeyForAsset(assetPath);

        AssetFileDescriptor afd = null;
        FileInputStream fis = null;
        try {
            afd = assetManager.openFd(key);
            fis = new FileInputStream(afd.getFileDescriptor());
            FileChannel fc = fis.getChannel();
            long startOffset = afd.getStartOffset();
            long declaredLength = afd.getDeclaredLength();
            return fc.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        } finally {
            if (fis != null) try {
                fis.close();
            } catch (IOException ignored) {
            }
            if (afd != null) try {
                afd.close();
            } catch (IOException ignored) {
            }
        }
    }

    // -------------------------------------------------------------------------
    // TensorMeta — metadados de quantização lidos uma única vez no loadModel
    // -------------------------------------------------------------------------

    /**
     * Metadados de quantização de um tensor.
     * <p>
     * quantizationScale() e quantizationZeroPoint() foram adicionados no TFLite 2.x.
     * Para versões antigas usamos reflexão com fallback seguro.
     * invScale = 1/scale é pré-computado para trocar divisão por multiplicação no loop.
     */
    private static final class TensorMeta {
        final DataType dataType;
        final float scale;
        final float invScale;
        final int zeroPoint;

        TensorMeta(DataType dataType, float scale, int zeroPoint) {
            this.dataType = dataType;
            this.scale = (scale > 0f) ? scale : 1f;
            this.invScale = (scale > 0f) ? 1f / scale : 1f;
            this.zeroPoint = zeroPoint;
        }

        /**
         * Cria TensorMeta usando reflexão para ser compatível com qualquer versão do TFLite.
         * Tenta primeiro quantizationParams() (API moderna), depois quantizationScale()
         * (API legada), com fallback para FLOAT32 puro se nenhum existir.
         */
        static TensorMeta from(Tensor tensor) {
            DataType dt = tensor.dataType();
            float scale = 0f;
            int zeroPoint = 0;
            try {
                // API moderna: Tensor.quantizationParams() retorna QuantizationParams
                java.lang.reflect.Method qpMethod =
                        tensor.getClass().getMethod("quantizationParams");
                Object qp = qpMethod.invoke(tensor);
                scale = (float) qp.getClass().getMethod("getScale").invoke(qp);
                zeroPoint = (int) qp.getClass().getMethod("getZeroPoint").invoke(qp);
            } catch (Exception e1) {
                try {
                    // API legada: quantizationScale() / quantizationZeroPoint()
                    scale = (float) tensor.getClass()
                            .getMethod("quantizationScale").invoke(tensor);
                    zeroPoint = (int) tensor.getClass()
                            .getMethod("quantizationZeroPoint").invoke(tensor);
                } catch (Exception e2) {
                    // Sem suporte a quantização — trata como FLOAT32 puro
                    scale = 0f;
                    zeroPoint = 0;
                }
            }
            return new TensorMeta(dt, scale, zeroPoint);
        }
    }

    // -------------------------------------------------------------------------
    // Tipos internos — estrutura original preservada + campos de quantização
    // -------------------------------------------------------------------------

    private static final class ModelItem {
        private final Interpreter interpreter;
        private final String key;
        private final TensorMeta inputMeta;
        private final int inputLength;
        private final ByteBuffer[] reusableOutputs;
        private final TensorMeta[] outputMetas;

        private ModelItem(Interpreter interpreter, String key,
                          TensorMeta inputMeta, int inputLength,
                          ByteBuffer[] reusableOutputs, TensorMeta[] outputMetas) {
            this.interpreter = interpreter;
            this.key = key;
            this.inputMeta = inputMeta;
            this.inputLength = inputLength;
            this.reusableOutputs = reusableOutputs;
            this.outputMetas = outputMetas;
        }

        private void close() {
            try {
                interpreter.close();
            } catch (Exception e) { /* ignora */ }
        }
    }

    private static class QueueItem {
        public final TfInferenceInput input;

        public final InferenceCallback callback;

        private QueueItem(final TfInferenceInput input, InferenceCallback callback) {
            this.input = input;
            this.callback = callback;
        }
    }


    public interface InferenceCallback {
        void success(List<Map<String, Object>> data);

        void error(String e);
    }

    public class InferenceResult {
        public final String id;

        public final Map<String, String> frameIds = new HashMap<>();

        public final TfInferenceOutput output;

        public InferenceResult(String frameId, TfInferenceOutput output) {
            this.id = UUID.randomUUID().toString();
            this.output = output;
            this.frameIds.put("main", frameId);
        }

        public TfFrameHandler.TfFrame getMainFrame() {
            String frameId = frameIds.get("main");
            return TfFrameHandler.getInstance().getFrameById(frameId);
        }

        public TfFrameHandler.TfFrame getScaledCroppedFrame() {
            String frameId = frameIds.get("scaled-cropped");
            if(frameId == null) return  null;
            return TfFrameHandler.getInstance().getFrameById(frameId);
        }

        public void addScaledCroppedFrame(String frameId) {
            frameIds.put("scaled-cropped", frameId);
        }

        public TfFrameHandler.TfFrame getCroppedFrame() {
            String frameId = frameIds.get("cropped");
            if(frameId == null) return  null;
            return TfFrameHandler.getInstance().getFrameById(frameId);
        }

        public void addCroppedFrame(String frameId) {
            frameIds.put("cropped", frameId);
        }

        Map<String, Object> toMap() {
            return new HashMap<String, Object>() {{
                put("id", id);
                put("output", output.toMap());
            }};
        }

        public void close() {
            synchronized (results) {
                for (String frameId: frameIds.values()) {
                    TfFrameHandler.TfFrame frame = TfFrameHandler.getInstance().getFrameById(frameId);
                    frame.close();
                }
                results.remove(this);
                Log.i("InferenceResultClose", "I: " + results.size() + " Frames: " + TfFrameHandler.getInstance().getFramesSize());
            }
        }
    }

}
