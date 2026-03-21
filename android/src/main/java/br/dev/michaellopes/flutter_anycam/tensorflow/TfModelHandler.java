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
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.flutter.FlutterInjector;

import io.flutter.Log;
import io.flutter.embedding.engine.loader.FlutterLoader;

public class TfModelHandler {
    private TfModelHandler() {
    }

    private volatile boolean processing = false;

    private final BlockingQueue<QueueItem> queue = new LinkedBlockingQueue<>(10);

    private ExecutorService queueExecutor;

    private static TfModelHandler instance;

    private ContextHolder contextHolder;

    private final List<ModelItem> models = new ArrayList<>();
    
    public static synchronized TfModelHandler getInstance() {
        if (instance == null) instance = new TfModelHandler();
        return instance;
    }

    public void init(ContextHolder contextHolder) {
        this.contextHolder = contextHolder;
    }

    public void loadModel(String assetPath, String key, String type, Integer threads) {
        try {
            if (threads == null) {
                threads = 1;
            }

            Interpreter.Options options = new Interpreter.Options();

            switch (type) {
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

            if(threads > 0) {
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
                    reusableInput, inputMeta, inputLength,
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

    public synchronized void runInference(float[] float32List, String modelKey, InferenceCallback callback) {
        boolean added = queue.offer(new QueueItem(float32List, modelKey, callback));
        if (added && !processing) {
            startProcessingWorker();
        }
    }

    private void processQueueItem(QueueItem queueItem) {
        long start = System.currentTimeMillis();
        float[] float32List = queueItem.float32List;
        String modelKey = queueItem.modelKey;
        InferenceCallback callback = queueItem.callback;

        ModelItem item = getModelByKey(modelKey);
        if (item == null) {
            callback.error("TfLite model is not loaded");
            return;
        }

        Interpreter tflite = item.interpreter;
        ByteBuffer inputBuffer = item.reusableInput;
        ByteBuffer[] outputs = item.reusableOutputs;
        TensorMeta inMeta = item.inputMeta;

        try {
            // ---- INPUT ----
            inputBuffer.rewind();

            if (float32List.length != item.inputLength) {
                callback.error("Tamanho do input inválido: esperado " + item.inputLength + " mas veio " + float32List.length);
                return;
            }


            Log.i("TFLite_QUANT", "inputMeta scale=" + inMeta.scale
                    + " invScale=" + inMeta.invScale
                    + " zeroPoint=" + inMeta.zeroPoint);

            if (inMeta.dataType == DataType.UINT8) {
                for (float v : float32List) {
                    int pixel = (int) v;
                    if (pixel < 0) pixel = 0;
                    else if (pixel > 255) pixel = 255;
                    inputBuffer.put((byte) pixel);
                }
            } else if (inMeta.dataType == DataType.FLOAT32) {
                for (float v : float32List) inputBuffer.putFloat(v);
            } else {

                Log.i("TFLite_QUANT_TT", "INT8 quantização manual" );
                // INT8 quantização manual
                final float invScale = inMeta.invScale;
                final int zeroPoint = inMeta.zeroPoint;
                for (float v : float32List) {
                    int q = Math.round(v * invScale) + zeroPoint;
                    if (q < -128) q = -128;
                    else if (q > 127) q = 127;
                    inputBuffer.put((byte) q);
                }
            }
            inputBuffer.rewind();

            Map<Integer, Object> outputMap = new HashMap<>();
            for (int i = 0; i < outputs.length; i++) {
                outputs[i].rewind();
                outputMap.put(i, outputs[i]);
            }

            // RUN
            tflite.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputMap);

            // ---- OUTPUTS ----
            Map<Integer, Object> results = new HashMap<>();
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
                        int rawByte = b.get() ;
                        flatArray[j] = (rawByte - outZp) * outScale;
                    }
                }

                Object structured = reshape(flatArray, shape, 0);
                results.put(i, structured);
            }

            callback.success(results);
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
        AssetManager assetManager = contextHolder.getContext().getAssets();
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
     *
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
                scale     = (float) qp.getClass().getMethod("getScale").invoke(qp);
                zeroPoint = (int)   qp.getClass().getMethod("getZeroPoint").invoke(qp);
            } catch (Exception e1) {
                try {
                    // API legada: quantizationScale() / quantizationZeroPoint()
                    scale     = (float) tensor.getClass()
                            .getMethod("quantizationScale").invoke(tensor);
                    zeroPoint = (int)   tensor.getClass()
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
        private final ByteBuffer reusableInput;
        private final TensorMeta inputMeta;
        private final int inputLength;          // número de elementos (não bytes)
        private final ByteBuffer[] reusableOutputs;
        private final TensorMeta[] outputMetas;

        private ModelItem(Interpreter interpreter, String key,
                          ByteBuffer reusableInput, TensorMeta inputMeta, int inputLength,
                          ByteBuffer[] reusableOutputs, TensorMeta[] outputMetas) {
            this.interpreter = interpreter;
            this.key = key;
            this.reusableInput = reusableInput;
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
        public final float[] float32List;
        public final String modelKey;
        public final InferenceCallback callback;

        private QueueItem(float[] float32List, String modelKey, InferenceCallback callback) {
            this.float32List = float32List;
            this.modelKey = modelKey;
            this.callback = callback;
        }
    }

    public interface ContextHolder {
        Context getContext();
    }

    public interface InferenceCallback {
        void success(Map<Integer, Object> data);

        void error(String e);
    }

}
