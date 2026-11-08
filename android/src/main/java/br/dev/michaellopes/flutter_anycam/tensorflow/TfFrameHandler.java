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
import java.util.LinkedHashMap;
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
import io.github.crow_misia.libyuv.AbgrBuffer;
import io.github.crow_misia.libyuv.ArgbBuffer;
import io.github.crow_misia.libyuv.FilterMode;
import io.github.crow_misia.libyuv.Nv21Buffer;
import io.github.crow_misia.libyuv.RotateMode;

/**
 * Registry of native ARGB frames shared with Dart by id.
 *
 * <p>Ownership rules:
 * <ul>
 *   <li>Every frame has exactly one owner (Dart, or the internal creator) that calls
 *   {@link TfFrame#close()} once; extra calls are ignored.</li>
 *   <li>Background readers {@link TfFrame#pin() pin} a frame for the duration of the read, so
 *   a concurrent close never frees a buffer that is still being converted.</li>
 *   <li>Only the inference chain (camera frame → letterbox crop → model input) is linked by
 *   parent ids; a parent outlives its children so results can still reach the raw frame.</li>
 *   <li>Every frame owns its buffer: no two frames ever share one.</li>
 * </ul>
 */
public class TfFrameHandler {

    private static final String TAG = "TfFrameHandler";

    /**
     * Camera frames alive at once. A consumer that stops closing frames gets dropped frames
     * instead of an OOM (640x480 ARGB is ~1.2 MB per frame).
     */
    static final int MAX_SOURCE_FRAMES = 12;

    private TfFrameHandler() {
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AnycamTfFrames");
        t.setDaemon(true);
        return t;
    });
    private final ByteArrayPoolUtil bytePool = new ByteArrayPoolUtil(6, 10);
    private final ByteBufferPoolUtil byteBuffer = new ByteBufferPoolUtil(2);
    private static TfFrameHandler instance;

    private final Object lock = new Object();

    private final Map<String, TfFrame> frames = new LinkedHashMap<>();
    private int sourceFrames = 0;
    private long droppedFrames = 0;

    public static synchronized TfFrameHandler getInstance() {
        if (instance == null) instance = new TfFrameHandler();
        return instance;
    }

    // ---------------------------------------------------------------------------------------
    // Camera / stream frames
    // ---------------------------------------------------------------------------------------

    @OptIn(markerClass = ExperimentalGetImage.class)
    public Map<String, Object> addFrame(ImageProxy imageProxy, Size resizeFrame, int filter, Integer customRotationDegrees) {
        Image image = imageProxy.getImage();
        if (image == null) return null;
        int rotation = customRotationDegrees != null
                ? customRotationDegrees
                : imageProxy.getImageInfo().getRotationDegrees();
        return addYuvImage(image, filter, rotation);
    }

    /**
     * Camera2 / RTSP {@link Image} path. {@code resizeFrame} is accepted for API parity but
     * not applied; inference does its own sizing.
     */
    public Map<String, Object> addFrame(Image image, Size resizeFrame, int filter, Integer customRotationDegrees) {
        if (image == null) return null;
        return addYuvImage(image, filter, customRotationDegrees != null ? customRotationDegrees : 0);
    }

    private Map<String, Object> addYuvImage(Image image, int filter, int rotation) {
        if (!hasSourceCapacity()) return null;
        int width = image.getWidth();
        int height = image.getHeight();
        int size = width * height * 3 / 2;

        ByteArrayPoolUtil.PoolItem bytes = bytePool.acquire(size);
        ByteBufferPoolUtil.PoolItem nv21 = null;
        try {
            NativeUtil.yuv420ToNv21(image, bytes.data);
            nv21 = byteBuffer.acquire(size);
            nv21.buffer.clear();
            nv21.buffer.put(bytes.data, 0, size);
            nv21.buffer.flip();
        } catch (Exception e) {
            Log.e(TAG, "YUV to NV21 failed: " + e.getMessage());
            if (nv21 != null) nv21.release();
            return null;
        } finally {
            bytePool.release(bytes);
        }
        return addFrameNv21Buffer(nv21.buffer, width, height, filter, rotation, nv21);
    }

    public Map<String, Object> addFrame(byte[] nv21, int width, int height, int filter, Integer customRotationDegrees) {
        if (!hasSourceCapacity()) return null;
        ByteBufferPoolUtil.PoolItem item = byteBuffer.acquire(nv21.length);
        item.buffer.clear();
        item.buffer.put(nv21);
        item.buffer.flip();
        return addFrameNv21Buffer(item.buffer, width, height, filter,
                customRotationDegrees != null ? customRotationDegrees : 0, item);
    }

    /**
     * USB / pooled NV21 path — avoids a per-frame {@code byte[]} allocation.
     * A direct {@code nv21} is wrapped as-is: conversion completes before this returns, so
     * the caller may reuse it afterwards. Heap buffers are copied into an internal pool
     * buffer first. Rotation 180° keeps {@code width x height}.
     */
    public Map<String, Object> addFrame(ByteBuffer nv21, int width, int height, int filter, Integer customRotationDegrees) {
        if (!hasSourceCapacity()) return null;
        int rotation = customRotationDegrees != null ? customRotationDegrees : 0;
        ByteBuffer src = nv21.duplicate();
        src.rewind();
        if (src.isDirect()) {
            return addFrameNv21Buffer(src, width, height, filter, rotation, null);
        }
        ByteBufferPoolUtil.PoolItem item = byteBuffer.acquire(src.remaining());
        item.buffer.clear();
        item.buffer.put(src);
        item.buffer.flip();
        return addFrameNv21Buffer(item.buffer, width, height, filter, rotation, item);
    }

    private static RotateMode rotateModeOf(int rotation) {
        switch (rotation) {
            case 90:
                return RotateMode.ROTATE_90;
            case 180:
                return RotateMode.ROTATE_180;
            case 270:
                return RotateMode.ROTATE_270;
            default:
                return RotateMode.ROTATE_0;
        }
    }

    /** Releases {@code pooled} (when non-null) before returning. */
    private Map<String, Object> addFrameNv21Buffer(
            ByteBuffer nv21,
            int width,
            int height,
            int filter,
            int rotation,
            ByteBufferPoolUtil.PoolItem pooled
    ) {
        try {
            return asyncRun((result) -> {
                Nv21Buffer src = Nv21Buffer.Factory.wrap(nv21, width, height);
                ArgbBuffer argb = null;
                try {
                    int outW = (rotation == 90 || rotation == 270) ? height : width;
                    int outH = (rotation == 90 || rotation == 270) ? width : height;
                    argb = ArgbBuffer.Factory.allocate(outW, outH);
                    if (rotation == 90 || rotation == 180 || rotation == 270) {
                        Nv21Buffer rotated = Nv21Buffer.Factory.allocate(outW, outH);
                        try {
                            src.rotate(rotated, rotateModeOf(rotation));
                            if (rotation == 270) {
                                // Front sensors report 270; keep the selfie mirror the
                                // detectors were tuned with.
                                Nv21Buffer mirrored = Nv21Buffer.Factory.allocate(outW, outH);
                                try {
                                    rotated.mirrorTo(mirrored);
                                    mirrored.convertTo(argb);
                                } finally {
                                    mirrored.close();
                                }
                            } else {
                                rotated.convertTo(argb);
                            }
                        } finally {
                            rotated.close();
                        }
                    } else {
                        src.convertTo(argb);
                    }
                    if (filter > 0) {
                        argb.drawGray(0, 0, outW, outH);
                    }
                    TfFrame frame = register(new TfFrame(outW, outH, null, argb, true));
                    argb = null;
                    result.complete(frame.toMap());
                } finally {
                    src.close();
                    if (argb != null) argb.close();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "addFrame failed: " + e.getMessage());
            return null;
        } finally {
            if (pooled != null) pooled.release();
        }
    }

    /**
     * GPU readback path: {@code argb} holds libyuv ARGB (bytes B,G,R,A), tightly packed and
     * top-down. It is copied before returning, so the caller may reuse it afterwards.
     */
    public Map<String, Object> addFrameArgb(ByteBuffer argb, int width, int height, int filter, Integer customRotationDegrees) {
        if (!hasSourceCapacity()) return null;
        final int rotation = customRotationDegrees != null ? customRotationDegrees : 0;
        try {
            return asyncRun((result) -> {
                ByteBuffer src = argb.duplicate();
                src.rewind();
                ArgbBuffer srcBuffer = ArgbBuffer.Factory.wrap(src, width, height);
                ArgbBuffer frameBuffer = null;
                try {
                    switch (rotation) {
                        case 90:
                        case 270:
                            frameBuffer = ArgbBuffer.Factory.allocate(height, width);
                            srcBuffer.rotate(frameBuffer, rotateModeOf(rotation));
                            break;
                        case 180:
                            frameBuffer = ArgbBuffer.Factory.allocate(width, height);
                            srcBuffer.rotate(frameBuffer, RotateMode.ROTATE_180);
                            break;
                        default:
                            frameBuffer = ArgbBuffer.Factory.allocate(width, height);
                            srcBuffer.convertTo(frameBuffer);
                            break;
                    }
                    if (rotation == 270) {
                        ArgbBuffer mirrored = ArgbBuffer.Factory.allocate(frameBuffer.getWidth(), frameBuffer.getHeight());
                        frameBuffer.mirrorTo(mirrored);
                        frameBuffer.close();
                        frameBuffer = mirrored;
                    }
                    if (filter > 0) {
                        frameBuffer.drawGray(0, 0, frameBuffer.getWidth(), frameBuffer.getHeight());
                    }
                    TfFrame frame = register(new TfFrame(frameBuffer.getWidth(), frameBuffer.getHeight(), null, frameBuffer, true));
                    frameBuffer = null;
                    result.complete(frame.toMap());
                } finally {
                    srcBuffer.close();
                    if (frameBuffer != null) frameBuffer.close();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "addFrameArgb failed: " + e.getMessage());
            return null;
        }
    }

    boolean hasSourceCapacity() {
        synchronized (lock) {
            if (sourceFrames < MAX_SOURCE_FRAMES) return true;
            if (droppedFrames++ % 30 == 0) {
                Log.w(TAG, "Dropping camera frame: " + sourceFrames
                        + " frames still open. Close every FlutterAnycamTfFrame after use.");
            }
            return false;
        }
    }

    public int getFramesSize() {
        synchronized (lock) {
            return frames.size();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Inference input
    // ---------------------------------------------------------------------------------------

    /**
     * Builds the model input frame. Always a new buffer: the input frame is closed by its own
     * owner, independently of the model input derived from it.
     */
    public TfFrame getTfFrameToInference(TfInferenceInput input) throws ExecutionException, InterruptedException {
        final TfFrame inputFrame = input.inputFrame;
        if (inputFrame == null || !inputFrame.pin()) {
            throw new IllegalStateException("Inference input frame is closed or unknown");
        }
        try {
            return asyncRun((result) -> {
                ArgbBuffer source = inputFrame.buffer;
                TfFrame parent = inputFrame;
                TfFrame letterbox = null;
                ArgbBuffer target = null;
                try {
                    int srcW = source.getWidth();
                    int srcH = source.getHeight();
                    if (input.inputSize != null
                            && (srcW != input.inputSize.getWidth() || srcH != input.inputSize.getHeight())) {
                        int dstW = input.inputSize.getWidth();
                        int dstH = input.inputSize.getHeight();

                        // Center crop to the destination aspect ratio, then scale.
                        float srcAspect = (float) srcW / srcH;
                        float dstAspect = (float) dstW / dstH;
                        int cropW;
                        int cropH;
                        if (srcAspect > dstAspect) {
                            cropH = srcH;
                            cropW = Math.max(1, (int) (srcH * dstAspect));
                        } else {
                            cropW = srcW;
                            cropH = Math.max(1, (int) (srcW / dstAspect));
                        }
                        int offsetX = (srcW - cropW) / 2;
                        int offsetY = (srcH - cropH) / 2;

                        ArgbBuffer cropped = crop(source, new Rect(offsetX, offsetY, offsetX + cropW, offsetY + cropH));
                        letterbox = register(new TfFrame(cropW, cropH, inputFrame.id, cropped, false));
                        letterbox.closeWhenChildClosed = true;
                        parent = letterbox;

                        target = ArgbBuffer.Factory.allocate(dstW, dstH);
                        cropped.scale(target, FilterMode.BILINEAR);
                    } else {
                        target = ArgbBuffer.Factory.allocate(srcW, srcH);
                        source.convertTo(target);
                    }

                    if (input.filter > 0) {
                        target.drawGray(0, 0, target.getWidth(), target.getHeight());
                    }

                    TfFrame frame = register(new TfFrame(target.getWidth(), target.getHeight(), parent.id, target, false));
                    target = null;
                    letterbox = null;
                    result.complete(frame);
                } finally {
                    if (target != null) target.close();
                    if (letterbox != null) letterbox.close();
                }
            });
        } finally {
            inputFrame.unpin();
        }
    }

    /** Crop result frames are independent: closing them never touches the source frame. */
    public TfFrame newFrameCroppedById(String frameId, TfInferenceOutputBox box, boolean enableScale) throws ExecutionException, InterruptedException {
        TfFrame inference = acquire(frameId);
        if (inference == null) return null;
        TfFrame src = inference;
        try {
            if (box == null) {
                return null;
            }
            Rect crop;
            if (enableScale) {
                TfFrame parent = acquire(inference.parentId);
                if (parent != null) {
                    src = parent;
                    crop = box.getScaledRect(parent.getSize());
                } else {
                    crop = box.getRect();
                }
            } else {
                crop = box.getRect();
            }
            final TfFrame source = src;
            final Rect rect = crop;
            return asyncRun((result) -> {
                ArgbBuffer cropped = crop(source.buffer, rect);
                if (cropped == null) {
                    result.complete(null);
                    return;
                }
                result.complete(register(new TfFrame(cropped.getWidth(), cropped.getHeight(), null, cropped, false)));
            });
        } finally {
            if (src != inference) src.unpin();
            inference.unpin();
        }
    }

    /** Returns a new buffer with {@code rect} (clamped to the source), or null when empty. */
    static ArgbBuffer crop(ArgbBuffer source, Rect rect) {
        int left = Math.max(0, rect.left);
        int top = Math.max(0, rect.top);
        int right = Math.min(source.getWidth(), rect.right);
        int bottom = Math.min(source.getHeight(), rect.bottom);
        if (right <= left || bottom <= top) return null;
        Rect clamped = new Rect(left, top, right, bottom);
        // A cropped view over the same memory: never closed (it does not own the buffer)
        // and never mutating the source's own crop rect, which other readers rely on.
        ArgbBuffer view = ArgbBuffer.Factory.wrap(source.asBuffer(), source.getWidth(), source.getHeight(), clamped);
        ArgbBuffer out = ArgbBuffer.Factory.allocate(clamped.width(), clamped.height());
        try {
            view.convertTo(out);
        } catch (RuntimeException e) {
            out.close();
            throw e;
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // Registry
    // ---------------------------------------------------------------------------------------

    TfFrame register(TfFrame frame) {
        synchronized (lock) {
            frames.put(frame.id, frame);
            if (frame.source) sourceFrames++;
            if (frame.parentId != null) {
                TfFrame parent = frames.get(frame.parentId);
                if (parent != null) parent.children++;
            }
        }
        return frame;
    }

    public TfFrame getFrameById(String id) {
        if (id == null) return null;
        synchronized (lock) {
            return frames.get(id);
        }
    }

    /** Looks up and pins a frame; the caller must {@link TfFrame#unpin()} it. */
    TfFrame acquire(String id) {
        if (id == null) return null;
        synchronized (lock) {
            TfFrame frame = frames.get(id);
            if (frame == null || !frame.pinLocked()) return null;
            return frame;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Frame operations requested by Dart
    // ---------------------------------------------------------------------------------------

    private static int[] roiOf(TfFrame frame, double xMin, double yMin, double xMax, double yMax) {
        int[] roi = new int[4];
        if (xMax > xMin && yMax > yMin) {
            roi[0] = (int) Math.floor(xMin * frame.width);
            roi[1] = (int) Math.floor(yMin * frame.height);
            int roiRight = (int) Math.ceil(xMax * frame.width);
            int roiBottom = (int) Math.ceil(yMax * frame.height);
            roi[2] = Math.max(0, roiRight - roi[0]);
            roi[3] = Math.max(0, roiBottom - roi[1]);
        }
        return roi;
    }

    /** Read-only: runs on the calling (background) thread with the frame pinned. */
    public Double computeBlurScore(String frameId, double xMin, double yMin, double xMax, double yMax, int sampleStep) {
        TfFrame frame = acquire(frameId);
        if (frame == null) return null;
        try {
            int[] roi = roiOf(frame, xMin, yMin, xMax, yMax);
            return NativeUtil.laplacianVarianceArgb(
                    frame.buffer.asBuffer(), frame.width, frame.height,
                    roi[0], roi[1], roi[2], roi[3], Math.max(1, sampleStep)
            );
        } catch (Exception e) {
            Log.e(TAG, "computeBlurScore failed: " + e.getMessage());
            return null;
        } finally {
            frame.unpin();
        }
    }

    /** Read-only: runs on the calling (background) thread with the frame pinned. */
    public Map<String, Double> computeIlluminationScore(String frameId, double xMin, double yMin, double xMax, double yMax, int sampleStep) {
        TfFrame frame = acquire(frameId);
        if (frame == null) return null;
        try {
            int[] roi = roiOf(frame, xMin, yMin, xMax, yMax);
            return NativeUtil.illuminationStatsArgb(
                    frame.buffer.asBuffer(), frame.width, frame.height,
                    roi[0], roi[1], roi[2], roi[3], Math.max(1, sampleStep)
            );
        } catch (Exception e) {
            Log.e(TAG, "computeIlluminationScore failed: " + e.getMessage());
            return null;
        } finally {
            frame.unpin();
        }
    }

    public Map<String, Object> registerFrameFromJpeg(byte[] jpegBytes) throws ExecutionException, InterruptedException {
        if (jpegBytes == null || jpegBytes.length == 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, options);
        if (bitmap == null) return null;
        try {
            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                bitmap.recycle();
                bitmap = converted;
                if (bitmap == null) return null;
            }
            final Bitmap source = bitmap;
            return asyncRun((result) -> {
                int w = source.getWidth();
                int h = source.getHeight();
                // Bitmap memory is R,G,B,A per pixel, i.e. libyuv ABGR.
                AbgrBuffer abgr = AbgrBuffer.Factory.allocate(w, h);
                ArgbBuffer argb = null;
                try {
                    ByteBuffer pixels = abgr.asBuffer();
                    pixels.rewind();
                    source.copyPixelsToBuffer(pixels);
                    argb = ArgbBuffer.Factory.allocate(w, h);
                    abgr.convertTo(argb);
                    TfFrame frame = register(new TfFrame(w, h, null, argb, false));
                    argb = null;
                    result.complete(frame.toMap());
                } finally {
                    abgr.close();
                    if (argb != null) argb.close();
                }
            });
        } finally {
            bitmap.recycle();
        }
    }

    /** Independent copy: survives the source frame being closed. */
    public Map<String, Object> registerFrameCopy(String frameId) throws ExecutionException, InterruptedException {
        TfFrame src = acquire(frameId);
        if (src == null) return null;
        try {
            return asyncRun((result) -> {
                ArgbBuffer copy = ArgbBuffer.Factory.allocate(src.width, src.height);
                try {
                    src.buffer.convertTo(copy);
                } catch (RuntimeException e) {
                    copy.close();
                    throw e;
                }
                result.complete(register(new TfFrame(src.width, src.height, null, copy, false)).toMap());
            });
        } finally {
            src.unpin();
        }
    }

    /** Independent crop: survives the source frame being closed. */
    public Map<String, Object> registerFrameCrop(
            String frameId,
            int x,
            int y,
            int width,
            int height,
            Integer resizeWidth,
            Integer resizeHeight
    ) throws ExecutionException, InterruptedException {
        TfFrame src = acquire(frameId);
        if (src == null) return null;
        try {
            return asyncRun((result) -> {
                ArgbBuffer cropped = crop(src.buffer, new Rect(x, y, x + width, y + height));
                if (cropped == null) {
                    result.complete(null);
                    return;
                }
                ArgbBuffer target = cropped;
                try {
                    if (resizeWidth != null && resizeHeight != null && resizeWidth > 0 && resizeHeight > 0
                            && (cropped.getWidth() != resizeWidth || cropped.getHeight() != resizeHeight)) {
                        target = ArgbBuffer.Factory.allocate(resizeWidth, resizeHeight);
                        cropped.scale(target, FilterMode.BILINEAR);
                        cropped.close();
                    }
                } catch (RuntimeException e) {
                    if (target != cropped) target.close();
                    cropped.close();
                    throw e;
                }
                result.complete(register(new TfFrame(target.getWidth(), target.getHeight(), null, target, false)).toMap());
            });
        } finally {
            src.unpin();
        }
    }

    /** JPEG of the frame; runs on the calling (background) thread with the frame pinned. */
    public byte[] getFrameJpeg(String frameId) {
        TfFrame frame = acquire(frameId);
        if (frame == null) return null;
        AbgrBuffer abgr = null;
        Bitmap bitmap = null;
        try {
            abgr = AbgrBuffer.Factory.allocate(frame.width, frame.height);
            frame.buffer.convertTo(abgr);
            bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888);
            ByteBuffer pixels = abgr.asBuffer();
            pixels.rewind();
            bitmap.copyPixelsFromBuffer(pixels);
            ByteArrayOutputStream out = new ByteArrayOutputStream(frame.width * frame.height / 4);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "getFrameJpeg failed: " + e.getMessage());
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (abgr != null) abgr.close();
            frame.unpin();
        }
    }

    public void closeFrame(String frameId) {
        TfFrame frame = getFrameById(frameId);
        if (frame != null) {
            frame.close();
        }
    }

    /** Engine teardown: releases every frame as soon as no reader still pins it. */
    public void closeAll() {
        synchronized (lock) {
            for (TfFrame frame : new ArrayList<>(frames.values())) {
                frame.ownerReleased = true;
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (TfFrame frame : new ArrayList<>(frames.values())) {
                    if (frame.tryReleaseLocked()) changed = true;
                }
            }
        }
    }

    /**
     * Waits uninterruptibly: callers close the buffers the task reads as soon as this returns,
     * so returning early on interrupt would free native memory the TF thread is still
     * converting. Tasks are short; the interrupt flag is restored afterwards.
     */
    private <T> T asyncRun(AsyncRun<T> runner) throws ExecutionException, InterruptedException {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                runner.run(future);
                // A task that returns without completing would block the caller forever.
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public class TfFrame {

        final String id;
        final int width;
        final int height;
        final String parentId;
        final ArgbBuffer buffer;
        final boolean source;

        /** Internal frames released together with their last child. */
        boolean closeWhenChildClosed = false;

        // Guarded by `lock`.
        private int children = 0;
        private int pins = 0;
        private boolean ownerReleased = false;
        private boolean released = false;

        TfFrame(int width, int height, String parentId, ArgbBuffer buffer, boolean source) {
            this.width = width;
            this.height = height;
            this.buffer = buffer;
            this.parentId = parentId;
            this.source = source;
            this.id = UUID.randomUUID().toString();
        }

        public String getId() {
            return id;
        }

        public ArgbBuffer getBuffer() {
            return buffer;
        }

        Size getSize() {
            return new Size(width, height);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>(4);
            map.put("id", id);
            map.put("width", width);
            map.put("height", height);
            return map;
        }

        public TfFrame getParentFrame() {
            return getFrameById(parentId);
        }

        public boolean isReleased() {
            synchronized (lock) {
                return released;
            }
        }

        /** Owner release; idempotent. The buffer is freed once no child or reader needs it. */
        public void close() {
            synchronized (lock) {
                if (ownerReleased) return;
                ownerReleased = true;
                tryReleaseLocked();
            }
        }

        /** Keeps the buffer alive for a reader; false when already released. */
        public boolean pin() {
            synchronized (lock) {
                return pinLocked();
            }
        }

        private boolean pinLocked() {
            if (released) return false;
            pins++;
            return true;
        }

        public void unpin() {
            synchronized (lock) {
                if (pins > 0) pins--;
                tryReleaseLocked();
            }
        }

        private boolean tryReleaseLocked() {
            if (released || !ownerReleased || pins > 0 || children > 0) return false;
            released = true;
            frames.remove(id);
            if (source) sourceFrames--;
            try {
                if (buffer != null) buffer.close();
            } catch (Exception e) {
                Log.w(TAG, "buffer close failed: " + e.getMessage());
            }
            if (parentId != null) {
                TfFrame parent = frames.get(parentId);
                if (parent != null) {
                    if (parent.children > 0) parent.children--;
                    if (parent.closeWhenChildClosed && parent.children == 0) {
                        parent.ownerReleased = true;
                    }
                    parent.tryReleaseLocked();
                }
            }
            return true;
        }
    }

    private interface AsyncRun<T> {
        void run(CompletableFuture<T> result) throws Exception;
    }
}
