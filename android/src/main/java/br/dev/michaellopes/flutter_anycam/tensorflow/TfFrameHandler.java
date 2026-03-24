package br.dev.michaellopes.flutter_anycam.tensorflow;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import android.util.Size;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import br.dev.michaellopes.flutter_anycam.model.TfInferenceInput;
import br.dev.michaellopes.flutter_anycam.model.TfInferenceOutputBox;
import br.dev.michaellopes.flutter_anycam.utils.ByteArrayPoolUtil;
import br.dev.michaellopes.flutter_anycam.utils.NativeUtil;
import io.flutter.Log;
import io.github.crow_misia.libyuv.ArgbBuffer;
import io.github.crow_misia.libyuv.FilterMode;
import io.github.crow_misia.libyuv.Nv21Buffer;
import io.github.crow_misia.libyuv.Rgb565Buffer;
import io.github.crow_misia.libyuv.RotateMode;

public class TfFrameHandler {
    private TfFrameHandler() {
    }

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ByteArrayPoolUtil bytePool = new ByteArrayPoolUtil(6, 10);
    private static TfFrameHandler instance;

    private final Object lock = new Object();

    final private List<TfFrame> frames = new ArrayList<>();

    public static synchronized TfFrameHandler getInstance() {
        if (instance == null) instance = new TfFrameHandler();
        return instance;
    }


    @OptIn(markerClass = ExperimentalGetImage.class)
    public Map<String, Object> addFrame(ImageProxy imageProxy, Size resizeFrame, int filter, Integer customRotationDegrees) {

        long start = System.currentTimeMillis();
        int srcWidth = imageProxy.getWidth();
        int srcHeight = imageProxy.getHeight();
        Image image = imageProxy.getImage();
        int rotation = imageProxy.getImageInfo().getRotationDegrees();

        if (customRotationDegrees != null) {
            rotation = customRotationDegrees;
        }

        RotateMode rotateMode;
        switch (rotation) {
            case 90:
                rotateMode = RotateMode.ROTATE_90;
                break;
            case 180:
                rotateMode = RotateMode.ROTATE_180;
                break;
            case 270:
                rotateMode = RotateMode.ROTATE_270;
                break;
            default:
                rotateMode = RotateMode.ROTATE_0;
                break;
        }

        int srcSize = srcWidth * srcHeight * 3 / 2;
        ByteArrayPoolUtil.PoolItem poolItem = bytePool.acquire(srcSize);
        NativeUtil.yuv420ToNv21(image, poolItem.data);

        ByteBuffer srcNv21ByteBuffer = ByteBuffer.allocateDirect(poolItem.data.length);
        srcNv21ByteBuffer.put(poolItem.data);
        srcNv21ByteBuffer.flip();

        bytePool.release(poolItem);

        Nv21Buffer srcNv21Buffer = Nv21Buffer.Factory.wrap(srcNv21ByteBuffer, srcWidth, srcHeight);
        if (rotation > 0) {
            Nv21Buffer nv21RotateBuffer = Nv21Buffer.Factory.allocate(srcHeight, srcWidth);
            srcNv21Buffer.rotate(nv21RotateBuffer, rotateMode);
            srcNv21Buffer.close();
            srcNv21Buffer = nv21RotateBuffer;
            if (rotation == 270) {
                srcNv21Buffer.mirrorTo(srcNv21Buffer);
            }
        }

        try {
            final Map<String, Object> result = addFrame(srcNv21Buffer, filter);
            long time = System.currentTimeMillis() - start;
            Log.d("addFrame_PERF", "time=" + time + "ms");
            return result;
        } catch (Exception e) {
            return null;
        }

    }

    public int getFramesSize() {
        return frames.size();
    }

    public TfFrame getTfFrameToInference(TfInferenceInput input) throws ExecutionException, InterruptedException {
        return asyncRun((result) -> {
            TfFrame inputFrame = input.inputFrame;
            ArgbBuffer targetBuffer = inputFrame.buffer;

            if (input.inputSize != null && (targetBuffer.getWidth() != input.inputSize.getWidth() || targetBuffer.getHeight() != input.inputSize.getHeight())) {
                ArgbBuffer resizedBuffer = ArgbBuffer.Factory.allocate(input.inputSize.getWidth(), input.inputSize.getHeight());
                targetBuffer.scale(resizedBuffer, FilterMode.BILINEAR);
                targetBuffer = resizedBuffer;
            }

            if (input.filter > 0) {
                targetBuffer.drawGray(0, 0, targetBuffer.getWidth(), targetBuffer.getHeight());
            }

            TfFrame frame = new TfFrame(targetBuffer.getWidth(), targetBuffer.getHeight(), inputFrame.id, targetBuffer);
            synchronized (lock) {
                frames.add(frame);
                result.complete(frame);
            }
        });
    }

    public TfFrame newFrameCroppedById(String frameId, TfInferenceOutputBox box, boolean enableScale) throws ExecutionException, InterruptedException {
        return asyncRun((result) -> {
            TfFrame srcFrame = getFrameById(frameId);
            if (srcFrame != null) {
                if (box != null) {
                    Rect crop;
                    if (srcFrame.getParentFrame() != null && enableScale) {
                        srcFrame = srcFrame.getParentFrame();
                        crop = box.getScaledRect(srcFrame.getSize());
                    } else {
                        crop = box.getRect();
                    }

                    Log.d("CROP_DEBUG", "src=" + srcFrame.getSize().getWidth() + "x" + srcFrame.getSize().getHeight());
                    Log.d("CROP_DEBUG", "crop=" + crop.left + "," + crop.top + " " + crop.right + "x" + crop.bottom);
                    Log.d("CROP_DEBUG", "dst=" + crop.width() + "x" + crop.height());

                    ArgbBuffer copyBuffer = ArgbBuffer.Factory.allocate(srcFrame.width, srcFrame.height);
                    srcFrame.buffer.convertTo(copyBuffer);
                    copyBuffer.setCropRect(crop);

                    ArgbBuffer cropBuffer = ArgbBuffer.Factory.allocate(crop.width(), crop.height());
                    copyBuffer.convertTo(cropBuffer);

                    copyBuffer.close();

                    TfFrame frame = new TfFrame(cropBuffer.getWidth(), cropBuffer.getHeight(), srcFrame.id, cropBuffer);
                    synchronized (lock) {
                        frames.add(frame);
                        result.complete(frame);
                        return;
                    }
                }
                result.complete(srcFrame);
            }
        });

    }

    private Map<String, Object> addFrame(Nv21Buffer nv21Buffer, int filter) throws ExecutionException, InterruptedException {
        return asyncRun((result) -> {
            int srcWidth = nv21Buffer.getWidth();
            int srcHeight = nv21Buffer.getHeight();

            ArgbBuffer rawArgbBuffer = ArgbBuffer.Factory.allocate(srcWidth, srcHeight);
            nv21Buffer.convertTo(rawArgbBuffer);

            if (filter > 0) {
                rawArgbBuffer.drawGray(0, 0, srcWidth, srcHeight);
            }

            TfFrame frame = new TfFrame(srcWidth, srcHeight, rawArgbBuffer);
            synchronized (lock) {
                frames.add(frame);
                result.complete(frame.toMap());
            }
        });
    }

    public TfFrame getFrameById(String id) {
        synchronized (lock) {
            for (TfFrame item : frames) {
                if (item.id.equals(id)) {
                    return item;
                }
            }
            return null;
        }
    }

    public List<TfFrame> getAllFrameChildrenFrameById(String id) {
        synchronized (lock) {
            List<TfFrame> lst = new ArrayList<>();
            for (TfFrame item : frames) {
                if (item.parentId != null && item.parentId.equals(id)) {
                    lst.add(item);
                }
            }
            return lst;
        }
    }

    public void removeFrame(TfFrame frame) {
        synchronized (lock) {
            frames.remove(frame);
        }
    }

    public byte[] getFrameJpeg(String frameId) {
        try {
            return asyncRun((result) -> {
                synchronized (lock) {
                    TfFrame frame = getFrameById(frameId);
                    if (frame != null) {
                        Rgb565Buffer buffer = Rgb565Buffer.Factory.allocate(frame.buffer.getWidth(), frame.buffer.getHeight());
                        frame.buffer.convertTo(buffer);
                        Bitmap bitmap = Bitmap.createBitmap(frame.buffer.getWidth(), frame.buffer.getHeight(), Bitmap.Config.RGB_565);
                        bitmap.copyPixelsFromBuffer(buffer.asBuffer());

                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                        buffer.close();
                        result.complete(out.toByteArray());
                        return;
                    }
                    result.complete(null);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void closeFrame(String frameId) {
        synchronized (lock) {
            TfFrame frame = getFrameById(frameId);
            if (frame != null) {
                frame.close();
            }
        }
    }

    private <T> T asyncRun(AsyncRun<T> runner) throws ExecutionException, InterruptedException {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                runner.run(future);
            } catch (Exception e) {
                if (!future.isCompletedExceptionally()) {
                    future.completeExceptionally(e);
                }
            }
        });
        return future.get();
    }

    public class TfFrame {

        final String id;
        final int width;
        final int height;
        final String parentId;
        final ArgbBuffer buffer;
        private boolean closeCalled = false;
        private boolean closed = false;

        public TfFrame(int width, int height, ArgbBuffer buffer) {
            this.width = width;
            this.height = height;
            this.buffer = buffer;
            this.id = UUID.randomUUID().toString();
            this.parentId = null;
        }

        public TfFrame(int width, int height, String parentId, ArgbBuffer buffer) {
            this.width = width;
            this.height = height;
            this.buffer = buffer;
            this.id = UUID.randomUUID().toString();
            this.parentId = parentId;
        }

        Size getSize() {
            return new Size(width, height);
        }

        public Map<String, Object> toMap() {
            return new HashMap<String, Object>() {{
                put("id", id);
                put("width", width);
                put("height", height);
            }};
        }

        public TfFrame getParentFrame() {
            if (parentId != null) {
                return getFrameById(parentId);
            }
            return null;
        }

        boolean hasChildren() {
            return !getAllFrameChildrenFrameById(id).isEmpty();
        }

        boolean canClose() {
            return closeCalled && !hasChildren() && !closed;
        }

        public void close() {
            closeCalled = true;
            tryClose();
        }

        private void tryClose() {
            if (!canClose()) return;

            if (closed) return;
            closed = true;
            buffer.close();


            removeFrame(this);

            Log.i("TfFrameClosed", "closed: " + id + "frames" + frames.size());
            if (parentId != null) {
                TfFrame parent = getFrameById(parentId);
                if (parent != null) {
                    parent.tryClose();
                }
            }
        }
    }

    private interface AsyncRun<T> {
        void run(CompletableFuture<T> result);
    }
}
