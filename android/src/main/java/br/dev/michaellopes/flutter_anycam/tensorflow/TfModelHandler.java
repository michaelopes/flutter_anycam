package br.dev.michaellopes.flutter_anycam.tensorflow;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Delegate;
import org.tensorflow.lite.Interpreter;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import br.dev.michaellopes.flutter_anycam.model.TfInferenceInput;
import br.dev.michaellopes.flutter_anycam.model.TfInferenceOutput;
import br.dev.michaellopes.flutter_anycam.utils.ByteBufferPoolUtil;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import io.flutter.FlutterInjector;
import io.flutter.Log;
import io.flutter.embedding.engine.loader.FlutterLoader;

/**
 * Loads TFLite models and runs inferences one at a time on a dedicated worker.
 *
 * <p>Each inference result pins the model input frame it was computed from, so closing one
 * result never invalidates its siblings; the frame is released with the last result.
 */
public class TfModelHandler {

    private static final String TAG = "TfModelHandler";
    private static final int MAX_QUEUED_INFERENCES = 10;

    private TfModelHandler() {
    }

    private static TfModelHandler instance;

    private final BlockingQueue<QueueItem> queue = new LinkedBlockingQueue<>(MAX_QUEUED_INFERENCES);

    /** Single worker: models reuse their output buffers, so inferences must not overlap. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AnycamTfInference");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, ModelItem> models = new LinkedHashMap<>();
    private final Map<String, InferenceResult> results = new LinkedHashMap<>();
    private final Object modelLock = new Object();

    public static synchronized TfModelHandler getInstance() {
        if (instance == null) instance = new TfModelHandler();
        return instance;
    }

    /** Blocking (reads the asset and builds the interpreter): call off the main thread. */
    public void loadModel(String assetPath, String key, String delegate, Integer threads) {
        if (key == null || assetPath == null) {
            throw new IllegalArgumentException("assetPath and key are required");
        }
        int numThreads = threads != null ? threads : 1;
        String delegateName = delegate != null ? delegate.toLowerCase(Locale.ROOT) : "";

        MappedByteBuffer modelBuffer;
        try {
            modelBuffer = loadModelFromAssets(assetPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read model asset " + assetPath + ": " + e.getMessage(), e);
        }

        Interpreter interpreter = null;
        Delegate gpuDelegate = null;
        if ("gpu".equals(delegateName)) {
            try {
                gpuDelegate = new GpuDelegate();
                interpreter = new Interpreter(modelBuffer, buildOptions(numThreads, gpuDelegate, false, false));
            } catch (Throwable t) {
                Log.w(TAG, "GPU delegate unavailable for " + key + ", falling back to CPU: " + t.getMessage());
                closeDelegate(gpuDelegate);
                gpuDelegate = null;
                interpreter = null;
            }
        }
        if (interpreter == null) {
            boolean xnnpack = "xnnpack".equals(delegateName) || "xxnnpack".equals(delegateName) || "gpu".equals(delegateName);
            boolean nnapi = "nnapi".equals(delegateName);
            interpreter = new Interpreter(modelBuffer, buildOptions(numThreads, null, xnnpack, nnapi));
        }

        ModelItem item;
        try {
            Tensor inTensor = interpreter.getInputTensor(0);
            TensorMeta inputMeta = TensorMeta.from(inTensor);
            int outCount = interpreter.getOutputTensorCount();
            ByteBuffer[] reusableOutputs = new ByteBuffer[outCount];
            TensorMeta[] outputMetas = new TensorMeta[outCount];
            for (int i = 0; i < outCount; i++) {
                Tensor t = interpreter.getOutputTensor(i);
                outputMetas[i] = TensorMeta.from(t);
                reusableOutputs[i] = ByteBuffer.allocateDirect(t.numBytes()).order(ByteOrder.nativeOrder());
            }
            item = new ModelItem(interpreter, gpuDelegate, key, inputMeta, reusableOutputs, outputMetas);
        } catch (Exception e) {
            interpreter.close();
            closeDelegate(gpuDelegate);
            throw new RuntimeException(e);
        }

        ModelItem previous;
        synchronized (modelLock) {
            previous = models.put(key, item);
            if (previous != null) {
                closeResultsOfModel(key);
                previous.close();
            }
        }
        Log.d(TAG, "Model loaded: " + key + " | inputType=" + item.inputMeta.dataType
                + " | delegate=" + (gpuDelegate != null ? "gpu" : delegateName));
    }

    private static Interpreter.Options buildOptions(int threads, Delegate delegate, boolean xnnpack, boolean nnapi) {
        Interpreter.Options options = new Interpreter.Options();
        if (delegate != null) options.addDelegate(delegate);
        if (xnnpack) options.setUseXNNPACK(true);
        if (nnapi) options.setUseNNAPI(true);
        if (threads > 0) options.setNumThreads(threads);
        return options;
    }

    private static void closeDelegate(Delegate delegate) {
        if (delegate instanceof AutoCloseable) {
            try {
                ((AutoCloseable) delegate).close();
            } catch (Exception ignored) {
            }
        }
    }

    public void disposeModelByKey(String modelKey) {
        synchronized (modelLock) {
            cancelQueuedInferences(modelKey, "TfLite model disposed");
            closeResultsOfModel(modelKey);
            ModelItem item = models.remove(modelKey);
            if (item != null) {
                item.close();
            }
        }
    }

    public void disposeAllModels() {
        synchronized (modelLock) {
            cancelQueuedInferences(null, "TfLite engine detached");
            for (InferenceResult it : snapshotResults()) {
                it.close();
            }
            synchronized (results) {
                results.clear();
            }
            for (ModelItem item : models.values()) {
                item.close();
            }
            models.clear();
        }
    }

    private List<InferenceResult> snapshotResults() {
        synchronized (results) {
            return new ArrayList<>(results.values());
        }
    }

    private void closeResultsOfModel(String modelKey) {
        for (InferenceResult it : snapshotResults()) {
            if (it.modelItemId.equals(modelKey)) {
                it.close();
            }
        }
    }

    private void cancelQueuedInferences(String modelKey, String reason) {
        Iterator<QueueItem> iterator = queue.iterator();
        while (iterator.hasNext()) {
            QueueItem pending = iterator.next();
            if (modelKey == null || modelKey.equals(pending.input.modelKey)) {
                iterator.remove();
                pending.tryError(reason);
            }
        }
    }

    public void runInference(TfInferenceInput input, InferenceCallback callback) {
        QueueItem item = new QueueItem(input, callback);
        if (!queue.offer(item)) {
            item.tryError("TfLite inference queue is full");
            return;
        }
        try {
            worker.execute(this::processNext);
        } catch (Exception e) {
            if (queue.remove(item)) item.tryError("TfLite worker unavailable");
        }
    }

    private void processNext() {
        QueueItem item = queue.poll();
        if (item == null) return;
        try {
            processQueueItem(item);
        } catch (Throwable t) {
            Log.e(TAG, "Inference failed: " + t.getMessage());
            item.tryError(String.valueOf(t.getMessage()));
        }
    }

    public void closeTfInferenceResult(String inferenceResultId) {
        InferenceResult inferenceResult = getInferenceResult(inferenceResultId);
        if (inferenceResult != null) {
            inferenceResult.close();
        }
    }

    public InferenceResult getInferenceResult(String inferenceResultId) {
        if (inferenceResultId == null) return null;
        synchronized (results) {
            return results.get(inferenceResultId);
        }
    }

    public TfFrameHandler.TfFrame getScaledCroppedFrame(String inferenceResultId) {
        InferenceResult res = getInferenceResult(inferenceResultId);
        if (res == null || res.output.box == null) return null;
        synchronized (res) {
            TfFrameHandler.TfFrame existing = res.getDerivedFrame("scaled-cropped");
            if (existing != null) return existing;
            try {
                TfFrameHandler.TfFrame frame = TfFrameHandler.getInstance()
                        .newFrameCroppedById(res.mainFrameId, res.output.box, true);
                if (frame != null) res.addDerivedFrame("scaled-cropped", frame);
                return frame;
            } catch (Exception e) {
                Log.e(TAG, "getScaledCroppedFrame failed: " + e.getMessage());
                return null;
            }
        }
    }

    public TfFrameHandler.TfFrame getCroppedFrame(String inferenceResultId) {
        InferenceResult res = getInferenceResult(inferenceResultId);
        if (res == null || res.output.box == null) return null;
        synchronized (res) {
            TfFrameHandler.TfFrame existing = res.getDerivedFrame("cropped");
            if (existing != null) return existing;
            try {
                TfFrameHandler.TfFrame frame = TfFrameHandler.getInstance()
                        .newFrameCroppedById(res.mainFrameId, res.output.box, false);
                if (frame != null) res.addDerivedFrame("cropped", frame);
                return frame;
            } catch (Exception e) {
                Log.e(TAG, "getCroppedFrame failed: " + e.getMessage());
                return null;
            }
        }
    }

    public TfFrameHandler.TfFrame getInferenceFrame(String inferenceResultId) {
        InferenceResult res = getInferenceResult(inferenceResultId);
        return res != null ? res.getMainFrame() : null;
    }

    /** The camera frame the inference started from (skipping the internal letterbox crop). */
    public TfFrameHandler.TfFrame getRawFrame(String inferenceResultId) {
        InferenceResult res = getInferenceResult(inferenceResultId);
        if (res == null) return null;
        TfFrameHandler.TfFrame main = res.getMainFrame();
        if (main == null) return null;
        TfFrameHandler.TfFrame parent = main.getParentFrame();
        if (parent != null && parent.closeWhenChildClosed) {
            parent = parent.getParentFrame();
        }
        return parent;
    }

    private void processQueueItem(QueueItem queueItem) throws Exception {
        long start = System.currentTimeMillis();
        TfInferenceInput input = queueItem.input;
        String modelKey = input.modelKey;
        Map<Integer, Object> rawOutputs;
        ByteBufferPoolUtil.PoolItem inputItem = null;
        TfFrameHandler.TfFrame targetFrame = null;

        try {
            synchronized (modelLock) {
                ModelItem item = models.get(modelKey);
                if (item == null || item.isClosed()) {
                    queueItem.tryError("TfLite model is not loaded");
                    return;
                }

                targetFrame = TfFrameHandler.getInstance().getTfFrameToInference(input);
                TensorMeta inMeta = item.inputMeta;
                if (inMeta.dataType == DataType.UINT8) {
                    inputItem = TfFrameNormalizer.getInstance().normalizeUint8(targetFrame.buffer, input.normalize);
                } else if (inMeta.dataType == DataType.INT8) {
                    inputItem = TfFrameNormalizer.getInstance().normalizeInt8(targetFrame.buffer, input.normalize, inMeta.invScale, inMeta.zeroPoint);
                } else {
                    inputItem = TfFrameNormalizer.getInstance().normalizeFloat32(targetFrame.buffer, input.normalize);
                }

                ByteBuffer[] outputs = item.reusableOutputs;
                Map<Integer, Object> outputMap = new HashMap<>();
                for (int i = 0; i < outputs.length; i++) {
                    outputs[i].rewind();
                    outputMap.put(i, outputs[i]);
                }

                item.interpreter.runForMultipleInputsOutputs(new Object[]{inputItem.buffer}, outputMap);

                rawOutputs = new HashMap<>();
                for (int i = 0; i < outputs.length; i++) {
                    int[] shape = item.interpreter.getOutputTensor(i).shape();
                    ByteBuffer b = outputs[i];
                    b.rewind();
                    rawOutputs.put(i, reshape(readTensor(b, item.outputMetas[i], shape), shape, 0));
                }
            }

            // processOutput round-trips to Dart on the main thread via MethodChannel.
            // Must not hold modelLock here or disposeModelByKey deadlocks with the worker.
            List<TfInferenceOutput> processedOutputs = input.processOutput(rawOutputs);

            List<Map<String, Object>> response = new ArrayList<>();
            synchronized (modelLock) {
                ModelItem item = models.get(modelKey);
                if (item != null && !item.isClosed()) {
                    for (TfInferenceOutput it : processedOutputs) {
                        if (!targetFrame.pin()) break;
                        InferenceResult res = new InferenceResult(item.key, targetFrame, it);
                        synchronized (results) {
                            results.put(res.id, res);
                        }
                        response.add(res.toMap());
                    }
                }
            }

            queueItem.trySuccess(response);
            Log.d("TFLite_PERF", "inference=" + (System.currentTimeMillis() - start) + "ms | queue=" + queue.size());
        } finally {
            // Results hold their own pins; the creator's ownership ends here.
            if (targetFrame != null) {
                targetFrame.close();
            }
            if (inputItem != null) {
                inputItem.release();
            }
        }
    }

    /** Dequantized float view of one output tensor. */
    static float[] readTensor(ByteBuffer b, TensorMeta meta, int[] shape) {
        int length = 1;
        for (int dim : shape) length *= dim;
        float[] out = new float[length];
        switch (meta.dataType) {
            case FLOAT32:
                b.asFloatBuffer().get(out, 0, length);
                break;
            case INT32:
                for (int j = 0; j < length; j++) out[j] = b.getInt();
                break;
            case INT64:
                for (int j = 0; j < length; j++) out[j] = b.getLong();
                break;
            case UINT8:
                for (int j = 0; j < length; j++) out[j] = ((b.get() & 0xFF) - meta.zeroPoint) * meta.scale;
                break;
            case INT8:
                for (int j = 0; j < length; j++) out[j] = (b.get() - meta.zeroPoint) * meta.scale;
                break;
            default:
                for (int j = 0; j < length; j++) out[j] = b.get();
                break;
        }
        return out;
    }

    static Object reshape(float[] flat, int[] shape, int offset) {
        if (shape.length == 0) {
            return new float[]{flat.length > 0 ? flat[0] : 0f};
        }
        if (shape.length == 1) {
            float[] arr = new float[shape[0]];
            System.arraycopy(flat, offset, arr, 0, shape[0]);
            return arr;
        }

        int step = 1;
        for (int i = 1; i < shape.length; i++) step *= shape[i];

        // List<Object> instead of Object[]: StandardMessageCodec encodes List directly.
        List<Object> list = new ArrayList<>(shape[0]);
        int[] subShape = Arrays.copyOfRange(shape, 1, shape.length);
        for (int i = 0; i < shape[0]; i++) {
            list.add(reshape(flat, subShape, offset + i * step));
        }
        return list;
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
            return fc.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength());
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
    // TensorMeta — quantization metadata read once at load time
    // -------------------------------------------------------------------------

    static final class TensorMeta {
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

        static TensorMeta from(Tensor tensor) {
            DataType dt = tensor.dataType();
            float scale = 0f;
            int zeroPoint = 0;
            try {
                Tensor.QuantizationParams qp = tensor.quantizationParams();
                scale = qp.getScale();
                zeroPoint = qp.getZeroPoint();
            } catch (Throwable ignored) {
            }
            return new TensorMeta(dt, scale, zeroPoint);
        }
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    private static final class ModelItem {
        private final Interpreter interpreter;
        private final Delegate delegate;
        private final String key;
        private final TensorMeta inputMeta;
        private final ByteBuffer[] reusableOutputs;
        private final TensorMeta[] outputMetas;
        private volatile boolean closed = false;

        private ModelItem(Interpreter interpreter, Delegate delegate, String key,
                          TensorMeta inputMeta,
                          ByteBuffer[] reusableOutputs, TensorMeta[] outputMetas) {
            this.interpreter = interpreter;
            this.delegate = delegate;
            this.key = key;
            this.inputMeta = inputMeta;
            this.reusableOutputs = reusableOutputs;
            this.outputMetas = outputMetas;
        }

        /** Called under modelLock, which the worker also holds while running the model. */
        private void close() {
            if (closed) return;
            closed = true;
            try {
                interpreter.close();
            } catch (Exception ignored) {
            }
            closeDelegate(delegate);
        }

        boolean isClosed() {
            return closed;
        }
    }

    private static class QueueItem {
        final TfInferenceInput input;
        final InferenceCallback callback;
        private final AtomicBoolean completed = new AtomicBoolean(false);

        private QueueItem(final TfInferenceInput input, InferenceCallback callback) {
            this.input = input;
            this.callback = callback;
        }

        void trySuccess(List<Map<String, Object>> data) {
            if (completed.compareAndSet(false, true)) {
                callback.success(data);
            }
        }

        void tryError(String e) {
            if (completed.compareAndSet(false, true)) {
                callback.error(e);
            }
        }
    }

    public interface InferenceCallback {
        void success(List<Map<String, Object>> data);

        void error(String e);
    }

    public class InferenceResult {
        public final String id;
        public final String modelItemId;
        public final TfInferenceOutput output;
        final String mainFrameId;

        private final TfFrameHandler.TfFrame mainFrame;
        private final Map<String, TfFrameHandler.TfFrame> derivedFrames = new HashMap<>();
        private boolean closed = false;

        /** {@code pinnedFrame} must already be pinned for this result. */
        InferenceResult(String modelItemId, TfFrameHandler.TfFrame pinnedFrame, TfInferenceOutput output) {
            this.id = UUID.randomUUID().toString();
            this.modelItemId = modelItemId;
            this.output = output;
            this.mainFrame = pinnedFrame;
            this.mainFrameId = pinnedFrame.id;
        }

        public TfFrameHandler.TfFrame getMainFrame() {
            return mainFrame.isReleased() ? null : mainFrame;
        }

        synchronized TfFrameHandler.TfFrame getDerivedFrame(String key) {
            TfFrameHandler.TfFrame frame = derivedFrames.get(key);
            return frame != null && !frame.isReleased() ? frame : null;
        }

        synchronized void addDerivedFrame(String key, TfFrameHandler.TfFrame frame) {
            if (closed) {
                frame.close();
                return;
            }
            derivedFrames.put(key, frame);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>(2);
            map.put("id", id);
            map.put("output", output.toMap());
            return map;
        }

        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
                for (TfFrameHandler.TfFrame frame : derivedFrames.values()) {
                    frame.close();
                }
                derivedFrames.clear();
            }
            synchronized (results) {
                results.remove(id);
            }
            mainFrame.unpin();
        }
    }
}
