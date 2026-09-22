package br.dev.michaellopes.flutter_anycam.tensorflow;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import br.dev.michaellopes.flutter_anycam.model.TfInferenceInput;
import br.dev.michaellopes.flutter_anycam.model.TfInferenceOutputBox;
import br.dev.michaellopes.flutter_anycam.utils.ByteArrayPoolUtil;
import br.dev.michaellopes.flutter_anycam.utils.ByteBufferPoolUtil;
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
    private final ByteBufferPoolUtil byteBuffer = new ByteBufferPoolUtil(2);
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

        ByteBufferPoolUtil.PoolItem byteBufferPoolItem = byteBuffer.acquire(poolItem.data.length);
        byteBufferPoolItem.buffer.put(poolItem.data);
        byteBufferPoolItem.buffer.flip();

        bytePool.release(poolItem);

        Nv21Buffer srcNv21Buffer = Nv21Buffer.Factory.wrap(byteBufferPoolItem.buffer, srcWidth, srcHeight);
        if (rotation > 0) {
            Nv21Buffer nv21RotateBuffer = Nv21Buffer.Factory.allocate(srcHeight, srcWidth);
            srcNv21Buffer.rotate(nv21RotateBuffer, rotateMode);
            srcNv21Buffer.close();
            byteBufferPoolItem.release();
            srcNv21Buffer = nv21RotateBuffer;
            /*if (rotation == 270) {
                srcNv21Buffer.mirrorTo(srcNv21Buffer);
            }*/

            if (rotation == 270) {
                Nv21Buffer mirrored = Nv21Buffer.Factory.allocate(srcNv21Buffer.getWidth(), srcNv21Buffer.getHeight());
                srcNv21Buffer.mirrorTo(mirrored);
                srcNv21Buffer.close();
                srcNv21Buffer = mirrored;
            }
        }

        try {
            final Map<String, Object> result = addFrame(srcNv21Buffer, filter);
            long time = System.currentTimeMillis() - start;
            Log.d("addFrame_PERF", "time=" + time + "ms");
            srcNv21Buffer.close();
            if (rotation == 0) {
                byteBufferPoolItem.release();
            }
            return result;
        } catch (Exception e) {
            srcNv21Buffer.close();
            if (rotation == 0) {
                byteBufferPoolItem.release();
            }
            return null;
        }

    }

    public Map<String, Object> addFrame(byte[] nv21, int width, int height, int filter, Integer customRotationDegrees) {
        int rotation = customRotationDegrees != null ? customRotationDegrees : 0;

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

        ByteBufferPoolUtil.PoolItem byteBufferPoolItem = byteBuffer.acquire(nv21.length);
        byteBufferPoolItem.buffer.put(nv21);
        byteBufferPoolItem.buffer.flip();

        Nv21Buffer srcNv21Buffer = Nv21Buffer.Factory.wrap(byteBufferPoolItem.buffer, width, height);
        if (rotation > 0) {
            Nv21Buffer nv21RotateBuffer = Nv21Buffer.Factory.allocate(height, width);
            srcNv21Buffer.rotate(nv21RotateBuffer, rotateMode);
            srcNv21Buffer.close();
            byteBufferPoolItem.release();
            srcNv21Buffer = nv21RotateBuffer;

            if (rotation == 270) {
                Nv21Buffer mirrored = Nv21Buffer.Factory.allocate(srcNv21Buffer.getWidth(), srcNv21Buffer.getHeight());
                srcNv21Buffer.mirrorTo(mirrored);
                srcNv21Buffer.close();
                srcNv21Buffer = mirrored;
            }
        }

        try {
            final Map<String, Object> result = addFrame(srcNv21Buffer, filter);
            srcNv21Buffer.close();
            if (rotation == 0) {
                byteBufferPoolItem.release();
            }
            return result;
        } catch (Exception e) {
            srcNv21Buffer.close();
            if (rotation == 0) {
                byteBufferPoolItem.release();
            }
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

           /* if (input.inputSize != null && (targetBuffer.getWidth() != input.inputSize.getWidth() || targetBuffer.getHeight() != input.inputSize.getHeight())) {
                ArgbBuffer resizedBuffer = ArgbBuffer.Factory.allocate(input.inputSize.getWidth(), input.inputSize.getHeight());
                targetBuffer.scale(resizedBuffer, FilterMode.BILINEAR);
                targetBuffer = resizedBuffer;
            }*/

            if (input.inputSize != null && (targetBuffer.getWidth() != input.inputSize.getWidth() || targetBuffer.getHeight() != input.inputSize.getHeight())) {

                int srcW = targetBuffer.getWidth();
                int srcH = targetBuffer.getHeight();
                int dstW = input.inputSize.getWidth();
                int dstH = input.inputSize.getHeight();

                // Calcula o crop mantendo proporção do destino
                float srcAspect = (float) srcW / srcH;
                float dstAspect = (float) dstW / dstH;

                int cropW, cropH;
                if (srcAspect > dstAspect) {
                    // Mais largo que o destino → corta as laterais
                    cropH = srcH;
                    cropW = (int) (srcH * dstAspect);
                } else {
                    // Mais alto que o destino → corta em cima/baixo
                    cropW = srcW;
                    cropH = (int) (srcW / dstAspect);
                }

                int offsetX = (srcW - cropW) / 2;
                int offsetY = (srcH - cropH) / 2;


                ArgbBuffer copyBuffer = ArgbBuffer.Factory.allocate(targetBuffer.getWidth(), targetBuffer.getHeight());
                targetBuffer.convertTo(copyBuffer);
                copyBuffer.setCropRect(new Rect(offsetX, offsetY, offsetX + cropW, offsetY + cropH));

                ArgbBuffer croppedBuffer = ArgbBuffer.Factory.allocate(cropW, cropH);
                copyBuffer.convertTo(croppedBuffer);

                TfFrame newInputFrame = new TfFrame(croppedBuffer.getWidth(), croppedBuffer.getHeight(), inputFrame.id, croppedBuffer);
                newInputFrame.closeWhenChildClosed = true;

                synchronized (lock) {
                    frames.add(newInputFrame);
                }
                // Log.i("TfFrameClosed", "inicio");
                // Log.i("TfFrameClosed", "closed: newInputFrame" + newInputFrame.id + "inputFrame" + inputFrame.id);

                inputFrame = newInputFrame;

                ArgbBuffer resizedBuffer = ArgbBuffer.Factory.allocate(dstW, dstH);
                croppedBuffer.scale(resizedBuffer, FilterMode.BILINEAR);
                targetBuffer = resizedBuffer;

                copyBuffer.close();
            }

            if (input.filter > 0) {
                targetBuffer.drawGray(0, 0, targetBuffer.getWidth(), targetBuffer.getHeight());
            }

            TfFrame frame = new TfFrame(targetBuffer.getWidth(), targetBuffer.getHeight(), inputFrame.id, targetBuffer);
            //Log.i("TfFrameClosed", "closed: frame" + frame.id + "newInputFrame" + inputFrame.id);

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
                    try {
                        Rect crop;
                        Log.d("CROP_DEBUG", "src_original=" + srcFrame.getSize().getWidth() + "x" + srcFrame.getSize().getHeight());
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
                        }
                    } catch (Exception e) {
                        result.complete(null);
                    }
                } else {
                    result.complete(srcFrame);
                }
            } else {
                result.complete(null);
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

    public Double computeBlurScore(
            String frameId,
            double xMin,
            double yMin,
            double xMax,
            double yMax,
            int sampleStep
    ) {
        final int step = sampleStep < 1 ? 1 : sampleStep;
        try {
            return asyncRun((result) -> {
                synchronized (lock) {
                    TfFrame frame = getFrameById(frameId);
                    if (frame == null) {
                        result.complete(null);
                        return;
                    }

                    int roiX = 0;
                    int roiY = 0;
                    int roiW = 0;
                    int roiH = 0;
                    if (xMax > xMin && yMax > yMin) {
                        roiX = (int) Math.floor(xMin * frame.width);
                        roiY = (int) Math.floor(yMin * frame.height);
                        int roiRight = (int) Math.ceil(xMax * frame.width);
                        int roiBottom = (int) Math.ceil(yMax * frame.height);
                        roiW = Math.max(0, roiRight - roiX);
                        roiH = Math.max(0, roiBottom - roiY);
                    }

                    double score = NativeUtil.laplacianVarianceArgb(
                            frame.buffer.asBuffer(),
                            frame.width,
                            frame.height,
                            roiX,
                            roiY,
                            roiW,
                            roiH,
                            step
                    );
                    result.complete(score);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public Map<String, Double> computeIlluminationScore(
            String frameId,
            double xMin,
            double yMin,
            double xMax,
            double yMax,
            int sampleStep
    ) {
        final int step = sampleStep < 1 ? 1 : sampleStep;
        try {
            return asyncRun((result) -> {
                synchronized (lock) {
                    TfFrame frame = getFrameById(frameId);
                    if (frame == null) {
                        result.complete(null);
                        return;
                    }

                    int roiX = 0;
                    int roiY = 0;
                    int roiW = 0;
                    int roiH = 0;
                    if (xMax > xMin && yMax > yMin) {
                        roiX = (int) Math.floor(xMin * frame.width);
                        roiY = (int) Math.floor(yMin * frame.height);
                        int roiRight = (int) Math.ceil(xMax * frame.width);
                        int roiBottom = (int) Math.ceil(yMax * frame.height);
                        roiW = Math.max(0, roiRight - roiX);
                        roiH = Math.max(0, roiBottom - roiY);
                    }

                    Map<String, Double> stats = NativeUtil.illuminationStatsArgb(
                            frame.buffer.asBuffer(),
                            frame.width,
                            frame.height,
                            roiX,
                            roiY,
                            roiW,
                            roiH,
                            step
                    );
                    result.complete(stats);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public Map<String, Object> registerFrameFromJpeg(byte[] jpegBytes)
            throws ExecutionException, InterruptedException {
        return asyncRun((result) -> {
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            if (bitmap == null) {
                result.complete(null);
                return;
            }
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            ArgbBuffer argbBuffer = ArgbBuffer.Factory.allocate(w, h);
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            ByteBuffer buffer = argbBuffer.asBuffer();
            for (int pixel : pixels) {
                buffer.putInt(pixel);
            }
            bitmap.recycle();
            TfFrame frame = new TfFrame(w, h, argbBuffer);
            synchronized (lock) {
                frames.add(frame);
                result.complete(frame.toMap());
            }
        });
    }

    public Map<String, Object> registerFrameCopy(String frameId)
            throws ExecutionException, InterruptedException {
        return asyncRun((result) -> {
            TfFrame srcFrame = getFrameById(frameId);
            if (srcFrame == null) {
                result.complete(null);
                return;
            }
            ArgbBuffer copyBuffer = ArgbBuffer.Factory.allocate(srcFrame.width, srcFrame.height);
            srcFrame.buffer.convertTo(copyBuffer);
            TfFrame frame = new TfFrame(srcFrame.width, srcFrame.height, copyBuffer);
            synchronized (lock) {
                frames.add(frame);
                result.complete(frame.toMap());
            }
        });
    }

    public Map<String, Object> registerFrameCrop(
            String frameId,
            int x,
            int y,
            int width,
            int height,
            Integer resizeWidth,
            Integer resizeHeight
    ) throws ExecutionException, InterruptedException {
        return asyncRun((result) -> {
            TfFrame srcFrame = getFrameById(frameId);
            if (srcFrame == null) {
                result.complete(null);
                return;
            }

            int left = Math.max(0, x);
            int top = Math.max(0, y);
            int right = Math.min(srcFrame.width, x + width);
            int bottom = Math.min(srcFrame.height, y + height);
            if (right <= left || bottom <= top) {
                result.complete(null);
                return;
            }

            Rect crop = new Rect(left, top, right, bottom);
            ArgbBuffer copyBuffer = ArgbBuffer.Factory.allocate(srcFrame.width, srcFrame.height);
            srcFrame.buffer.convertTo(copyBuffer);
            copyBuffer.setCropRect(crop);

            ArgbBuffer cropBuffer = ArgbBuffer.Factory.allocate(crop.width(), crop.height());
            copyBuffer.convertTo(cropBuffer);
            copyBuffer.close();

            ArgbBuffer targetBuffer = cropBuffer;
            if (resizeWidth != null && resizeHeight != null
                    && (cropBuffer.getWidth() != resizeWidth || cropBuffer.getHeight() != resizeHeight)) {
                ArgbBuffer resizedBuffer = ArgbBuffer.Factory.allocate(resizeWidth, resizeHeight);
                cropBuffer.scale(resizedBuffer, FilterMode.BILINEAR);
                cropBuffer.close();
                targetBuffer = resizedBuffer;
            }

            TfFrame frame = new TfFrame(
                    targetBuffer.getWidth(),
                    targetBuffer.getHeight(),
                    srcFrame.id,
                    targetBuffer
            );
            synchronized (lock) {
                frames.add(frame);
                result.complete(frame.toMap());
            }
        });
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
                        ByteBuffer jpegBuffer = buffer.asBuffer();
                        bitmap.copyPixelsFromBuffer(jpegBuffer);

                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);

                        jpegBuffer.clear();
                        jpegBuffer = null;

                        buffer.close();
                        
                        result.complete(out.toByteArray());
                        bitmap.recycle();
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

        private boolean closeWhenChildClosed = true;;

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

            // Log.i("TfFrameClosed", "tryClose: " + id);
            // Log.i("TfFrameClosed", "closeCalled " + closeCalled + " !hasChildren() " + !hasChildren() + "!closed " + !closed);

            if (!canClose()) return;

            if (closed) return;
            closed = true;
            buffer.close();

            removeFrame(this);

           Log.i("TfFrameClosed", "closed: " + id + "frames " + frames.size());
            //Log.i("TfFrameClosed", "closed: " + id + "parent" + parentId);

            if (parentId != null) {
                TfFrame parent = getFrameById(parentId);
                if (parent != null) {
                   // Log.i("TfFrameClosed", "chamou parent" + parent.id);
                    if(parent.closeWhenChildClosed) {
                        parent.close();
                    } else {
                        parent.tryClose();
                    }
                }
            }

            Log.i("TfFrameClosed", "fim");

        }
    }

    private interface AsyncRun<T> {
        void run(CompletableFuture<T> result);
    }
}
