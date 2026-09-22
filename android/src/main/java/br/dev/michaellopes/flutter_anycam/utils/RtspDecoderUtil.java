package br.dev.michaellopes.flutter_anycam.utils;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class RtspDecoderUtil {

    private static final String TAG = "RtspDecoderUtil";

    private final Surface previewSurface;
    private final RtspDecoderCallback callback;
    private final RtspDecoderFailure failure;

    private final int targetWidth;
    private final int targetHeight;

    private final long frameIntervalNs;

    private PreviewDecoder previewDecoder;
    private YuvDecoder yuvDecoder;

    private final AtomicBoolean running = new AtomicBoolean(false);

    // Pool p/ reduzir alocações de NAL (ajuste tamanhos conforme seu stream)
    private final ByteArrayPool nalPool = new ByteArrayPool(
            new int[]{1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072}, // buckets
            64 // max arrays por bucket
    );

    public RtspDecoderUtil(
            Surface surface,
            int targetWidth,
            int targetHeight,
            int targetFps,
            RtspDecoderCallback callback,
            RtspDecoderFailure failure
    ) {
        this.previewSurface = surface;
        this.callback = callback;
        this.failure = failure;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.frameIntervalNs = 1_000_000_000L / Math.max(1, targetFps);
    }

    // ========= PUBLIC API =========

    public void start() {
        try {
            running.set(true);
            previewDecoder = new PreviewDecoder();
            yuvDecoder = new YuvDecoder();
            previewDecoder.start();
            yuvDecoder.start();
        } catch (Exception e) {
            failure.onFailure(e);
        }
    }

    public void dispose() {
        running.set(false);
        if (previewDecoder != null) previewDecoder.stopDecoder();
        if (yuvDecoder != null) yuvDecoder.stopDecoder();
    }

    public void dispatchNal(byte[] data, int offset, int length, long ts) {
        if (!running.get() || length <= 0) return;

        // 1) pega buffer do pool
        byte[] buf = nalPool.acquire(length);
        System.arraycopy(data, offset, buf, 0, length);

        // 2) 1 NAL compartilhado (refcount = 2 decoders)
        NalUnit nal = new NalUnit(buf, 0, length, ts, nalPool, 2);

        previewDecoder.feedNal(nal);
        yuvDecoder.feedNal(nal);
    }

    // ========= PREVIEW (Surface) =========

    private class PreviewDecoder extends Thread {

        private final MediaCodec codec;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        // ArrayBlockingQueue é mais leve que LinkedBlockingQueue (menos nós/alloc)
        private final ArrayBlockingQueue<NalUnit> previewQueue = new ArrayBlockingQueue<>(15);

        PreviewDecoder() throws Exception {
            codec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
            codec.configure(format, previewSurface, null, 0);
            codec.start();
            setName("RtspPreviewDecoder");
        }

        @Override
        public void run() {
            while (running.get()) {
                NalUnit nal = null;
                try {
                    nal = previewQueue.take();
                    feed(nal, true);
                } catch (Exception e) {
                    Log.e(TAG, "Preview decode error", e);
                } finally {
                    if (nal != null) nal.release(); // libera quando já copiou para input buffer
                }
            }
        }

        private void feed(NalUnit nal, boolean render) throws Exception {
            int in = codec.dequeueInputBuffer(10_000);
            if (in >= 0) {
                ByteBuffer buffer = codec.getInputBuffer(in);
                if (buffer != null) {
                    buffer.clear();
                    buffer.put(nal.data, nal.offset, nal.length);
                    codec.queueInputBuffer(in, 0, nal.length, nal.timestamp, 0);
                } else {
                    codec.queueInputBuffer(in, 0, 0, 0, 0);
                }
            }

            int out = codec.dequeueOutputBuffer(info, 10_000);
            while (out >= 0) {
                codec.releaseOutputBuffer(out, render);
                out = codec.dequeueOutputBuffer(info, 0);
            }

            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = codec.getOutputFormat();
                try {
                    callback.onResolutionResult(
                            newFormat.getInteger(MediaFormat.KEY_WIDTH),
                            newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    );
                } catch (Exception ignored) {}
            }
        }

        void feedNal(NalUnit nal) {
            // drop-oldest para evitar travar e aumentar latência
            if (!previewQueue.offer(nal)) {
                NalUnit dropped = previewQueue.poll();
                if (dropped != null) dropped.release();
                previewQueue.offer(nal);
            }
        }

        void stopDecoder() {
            try {
                codec.stop();
            } catch (Exception ignored) {}
            try {
                codec.release();
            } catch (Exception ignored) {}
        }
    }

    // ========= YUV (Image) =========

    private class YuvDecoder {

        private final MediaCodec codec;
        private volatile long lastProcessTime = 0;

        private final ArrayBlockingQueue<NalUnit> yuvQueue = new ArrayBlockingQueue<>(15);

        // Reuso de buffers grandes
        private byte[] nv21Buffer;
        private byte[] resizedBuffer;

        // Resizer com mapas pré-calculados (evita divisões por pixel)
        //private Nv21Resizer resizer;
        YuvDecoder() throws Exception {
            codec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);

            codec.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec mc, int inputBufferId) {
                    NalUnit nal = null;
                    try {
                        nal = yuvQueue.poll(10, TimeUnit.MILLISECONDS);
                        if (nal != null) {
                            ByteBuffer buffer = mc.getInputBuffer(inputBufferId);
                            if (buffer != null) {
                                buffer.clear();
                                buffer.put(nal.data, nal.offset, nal.length);
                                mc.queueInputBuffer(inputBufferId, 0, nal.length, nal.timestamp, 0);
                            } else {
                                mc.queueInputBuffer(inputBufferId, 0, 0, 0, 0);
                            }
                        } else {
                            mc.queueInputBuffer(inputBufferId, 0, 0, 0, 0);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "onInputBufferAvailable error", e);
                    } finally {
                        if (nal != null) nal.release(); // libera após copiar pro input buffer
                    }
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec mc, int outputBufferId, MediaCodec.BufferInfo info) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size <= 0) {
                        mc.releaseOutputBuffer(outputBufferId, false);
                        return;
                    }

                    long now = System.nanoTime();
                    if (now - lastProcessTime < frameIntervalNs) {
                        mc.releaseOutputBuffer(outputBufferId, false);
                        return;
                    }
                    lastProcessTime = now;

                    Image image = null;
                    try {
                        image = mc.getOutputImage(outputBufferId);
                        if (image == null) {
                            mc.releaseOutputBuffer(outputBufferId, false);
                            return;
                        }

                        int srcW = image.getWidth();
                        int srcH = image.getHeight();

                        ensureYuvBuffers(srcW, srcH);
                        NativeUtil.yuv420ToNv21(image, nv21Buffer);

                        image.close();
                        image = null;

                        mc.releaseOutputBuffer(outputBufferId, false);

                        if (srcW == targetWidth && srcH == targetHeight) {
                            YuvFrame frame = new YuvFrame(nv21Buffer, srcW, srcH);
                            callback.onYuvFrame(frame, null);
                        } else {
                            int tgWidth;
                            int tgHeight;
                            if(targetWidth == -1 && targetHeight == -1) {
                                int minSize = Math.min(srcW, srcH);
                                tgWidth = minSize;
                                tgHeight = minSize;
                            } else {
                                tgWidth = targetWidth;
                                tgHeight = targetHeight;
                            }

                            YuvFrame rawFrame = new YuvFrame(nv21Buffer, srcW, srcH);
                            NativeUtil.resizeNv21(nv21Buffer, srcW, srcH, resizedBuffer, tgWidth, tgHeight);
                            YuvFrame targetFrame = new YuvFrame(resizedBuffer, tgWidth, tgHeight);
                            callback.onYuvFrame(targetFrame, rawFrame);
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "onOutputBufferAvailable error", e);
                        if (image != null) {
                            try { image.close(); } catch (Exception ignored) {}
                        }
                        try { mc.releaseOutputBuffer(outputBufferId, false); } catch (Exception ignored) {}
                    }
                }

                @Override
                public void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
                    Log.d(TAG, "YUV format changed: " + format);
                }

                @Override
                public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                    Log.e(TAG, "YUV codec error", e);
                    failure.onFailure(e);
                }
            });

            codec.configure(format, null, null, 0);
        }

        void start() {
            codec.start();
        }

        void feedNal(NalUnit nal) {
            if (!yuvQueue.offer(nal)) {
                NalUnit dropped = yuvQueue.poll();
                if (dropped != null) dropped.release();
                yuvQueue.offer(nal);
            }
        }

        void stopDecoder() {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
        }

        private void ensureYuvBuffers(int srcW, int srcH) {
            int srcSize = srcW * srcH * 3 / 2;
            if (nv21Buffer == null || nv21Buffer.length != srcSize) {
                nv21Buffer = new byte[srcSize];
            }

            int dstSize = targetWidth * targetHeight * 3 / 2;
            if (resizedBuffer == null || resizedBuffer.length != dstSize) {
                resizedBuffer = new byte[dstSize];
            }
        }
    }

    // ========= NV21 RESIZER (map precompute) =========
//    private static final class Nv21Resizer {
//        final int srcW, srcH, dstW, dstH;
//        final int[] xMapY;
//        final int[] yMapY;
//        final int[] xMapUV;
//        final int[] yMapUV;
//
//        Nv21Resizer(int srcW, int srcH, int dstW, int dstH) {
//            this.srcW = srcW;
//            this.srcH = srcH;
//            this.dstW = dstW;
//            this.dstH = dstH;
//
//            xMapY = new int[dstW];
//            yMapY = new int[dstH];
//
//            for (int i = 0; i < dstW; i++) xMapY[i] = (i * srcW) / dstW;
//            for (int j = 0; j < dstH; j++) yMapY[j] = (j * srcH) / dstH;
//
//            int dstW2 = dstW / 2;
//            int dstH2 = dstH / 2;
//            int srcW2 = srcW / 2;
//            int srcH2 = srcH / 2;
//
//            xMapUV = new int[dstW2];
//            yMapUV = new int[dstH2];
//
//            for (int i = 0; i < dstW2; i++) xMapUV[i] = (i * srcW2) / dstW2;
//            for (int j = 0; j < dstH2; j++) yMapUV[j] = (j * srcH2) / dstH2;
//        }
//
//        void resize(byte[] nv21, byte[] out) {
//            final int srcFrame = srcW * srcH;
//            final int dstFrame = dstW * dstH;
//
//            // Y
//            int outRow = 0;
//            for (int j = 0; j < dstH; j++) {
//                int srcY = yMapY[j] * srcW;
//                int outBase = outRow;
//                for (int i = 0; i < dstW; i++) {
//                    out[outBase + i] = nv21[srcY + xMapY[i]];
//                }
//                outRow += dstW;
//            }
//
//            // UV (NV21 = VU)
//            int srcUvStart = srcFrame;
//            int dstUvStart = dstFrame;
//
//            int dstH2 = dstH / 2;
//            int dstW2 = dstW / 2;
//
//            for (int j = 0; j < dstH2; j++) {
//                int srcUvRow = yMapUV[j] * srcW;
//                int dstUvRow = dstUvStart + j * dstW;
//
//                for (int i = 0; i < dstW2; i++) {
//                    int srcUvCol = xMapUV[i] * 2;
//                    int srcIndex = srcUvStart + srcUvRow + srcUvCol;
//                    int dstIndex = dstUvRow + i * 2;
//
//                    out[dstIndex]     = nv21[srcIndex];     // V
//                    out[dstIndex + 1] = nv21[srcIndex + 1]; // U
//                }
//            }
//        }
//    }

    // ========= NAL + POOL =========

    private static final class NalUnit {
        final byte[] data;
        final int offset;
        final int length;
        final long timestamp;

        private final ByteArrayPool pool;
        private final AtomicInteger refs;

        NalUnit(byte[] d, int o, int l, long t, ByteArrayPool pool, int refCount) {
            this.data = d;
            this.offset = o;
            this.length = l;
            this.timestamp = t;
            this.pool = pool;
            this.refs = new AtomicInteger(refCount);
        }

        void release() {
            if (refs.decrementAndGet() == 0) {
                pool.release(data);
            }
        }
    }

    private static final class ByteArrayPool {
        private final int[] bucketSizes;
        private final ArrayBlockingQueue<byte[]>[] buckets;

        @SuppressWarnings("unchecked")
        ByteArrayPool(int[] bucketSizes, int perBucket) {
            this.bucketSizes = bucketSizes.clone();
            this.buckets = new ArrayBlockingQueue[bucketSizes.length];
            for (int i = 0; i < bucketSizes.length; i++) {
                buckets[i] = new ArrayBlockingQueue<>(perBucket);
            }
        }

        byte[] acquire(int minSize) {
            int idx = bucketIndex(minSize);
            if (idx < 0) return new byte[minSize];

            byte[] arr = buckets[idx].poll();
            if (arr != null) return arr;
            return new byte[bucketSizes[idx]];
        }

        void release(byte[] arr) {
            int idx = bucketIndex(arr.length);
            if (idx < 0) return;
            // se não couber, descarta (evita crescer indefinidamente)
            buckets[idx].offer(arr);
        }

        private int bucketIndex(int size) {
            for (int i = 0; i < bucketSizes.length; i++) {
                if (size <= bucketSizes[i]) return i;
            }
            return -1;
        }
    }

    // ========= CALLBACKS =========

    public interface RtspDecoderCallback {
        void onResolutionResult(int width, int height);
        void onYuvFrame(YuvFrame targetFrame, YuvFrame rawFrame);
    }

    public interface RtspDecoderFailure {
        void onFailure(Exception e);
    }

    public static class YuvFrame {
      public  final byte[] nv21;
        public  final int width;
        public  final int height;

        public YuvFrame(byte[] nv21, int width, int height) {
            this.nv21 = nv21;
            this.width = width;
            this.height = height;
        }
    }
}

//package br.dev.michaellopes.flutter_anycam.utils;
//
//import android.media.Image;
//import android.media.MediaCodec;
//import android.media.MediaFormat;
//import android.util.Log;
//import android.view.Surface;
//
//import java.nio.ByteBuffer;
//import java.util.concurrent.LinkedBlockingQueue;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicBoolean;
//
//
//public class RtspDecoderUtil {
//
//    private static final String TAG = "RtspDecoderUtil";
//
//    private final Surface previewSurface;
//    private final RtspDecoderCallback callback;
//    private final RtspDecoderFailure failure;
//
//    private final int targetWidth;
//    private final int targetHeight;
//
//    private final long frameIntervalNs;
//
//    private PreviewDecoder previewDecoder;
//    private YuvDecoder yuvDecoder;
//
//    private final AtomicBoolean running = new AtomicBoolean(false);
//
//    public RtspDecoderUtil(
//            Surface surface,
//            int targetWidth,
//            int targetHeight,
//            int targetFps,
//            RtspDecoderCallback callback,
//            RtspDecoderFailure failure
//    ) {
//        this.previewSurface = surface;
//        this.callback = callback;
//        this.failure = failure;
//        this.targetWidth = targetWidth;
//        this.targetHeight = targetHeight;
//        this.frameIntervalNs = 1_000_000_000L / targetFps;
//    }
//
//    // ========= PUBLIC API =========
//    public void start() {
//        try {
//            running.set(true);
//            previewDecoder = new PreviewDecoder();
//            yuvDecoder = new YuvDecoder();
//
//            previewDecoder.start();
//            yuvDecoder.start();
//        } catch (Exception e) {
//            failure.onFailure(e);
//        }
//    }
//
//    public void dispose() {
//        running.set(false);
//        if (previewDecoder != null) previewDecoder.stopDecoder();
//        if (yuvDecoder != null) yuvDecoder.stopDecoder();
//    }
//
//    public void dispatchNal(byte[] data, int offset, int length, long ts) {
//        if (!running.get()) return;
//
//        byte[] copy = new byte[length];
//        System.arraycopy(data, offset, copy, 0, length);
//
//        NalUnit nal = new NalUnit(copy, 0, length, ts);
//
//        previewDecoder.feedNal(nal);
//        yuvDecoder.feedNal(nal);
//    }
//
//
//    private class PreviewDecoder extends Thread {
//
//        private MediaCodec codec;
//
//        private final LinkedBlockingQueue<NalUnit> previewQueue = new LinkedBlockingQueue<>(30);
//
//        PreviewDecoder() throws Exception {
//            codec = MediaCodec.createDecoderByType("video/avc");
//            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
//            codec.configure(format, previewSurface, null, 0);
//            codec.start();
//        }
//
//        @Override
//        public void run() {
//            while (running.get()) {
//                try {
//                    NalUnit nal = previewQueue.take();
//                    feed(nal, true);
//                } catch (Exception e) {
//                    Log.e(TAG, "Preview decode error", e);
//                }
//            }
//        }
//
//        private void feed(NalUnit nal, boolean render) throws Exception {
//            int in = codec.dequeueInputBuffer(10000);
//            if (in >= 0) {
//                ByteBuffer buffer = codec.getInputBuffer(in);
//                buffer.clear();
//                buffer.put(nal.data);
//                codec.queueInputBuffer(in, 0, nal.length, nal.timestamp, 0);
//            }
//
//            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
//            int out = codec.dequeueOutputBuffer(info, 10000);
//
//            while (out >= 0) {
//                codec.releaseOutputBuffer(out, render);
//                out = codec.dequeueOutputBuffer(info, 0);
//            }
//
//            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
//                MediaFormat newFormat = codec.getOutputFormat();
//                callback.onResolutionResult(
//                        newFormat.getInteger(MediaFormat.KEY_WIDTH),
//                        newFormat.getInteger(MediaFormat.KEY_HEIGHT)
//                );
//            }
//        }
//
//        void feedNal(NalUnit nal) {
//            previewQueue.offer(nal);
//        }
//
//        void stopDecoder() {
//            try {
//                codec.stop();
//                codec.release();
//            } catch (Exception ignored) {}
//        }
//    }
//
//    private class YuvDecoder {
//        private MediaCodec codec;
//        private volatile long lastProcessTime = 0;
//
//        private final LinkedBlockingQueue<NalUnit> yuvQueue = new LinkedBlockingQueue<>(30);
//
//        YuvDecoder() throws Exception {
//            codec = MediaCodec.createDecoderByType("video/avc");
//            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
//
//            codec.setCallback(new MediaCodec.Callback() {
//                @Override
//                public void onInputBufferAvailable(MediaCodec mc, int inputBufferId) {
//                    try {
//                        NalUnit nal = yuvQueue.poll(10, TimeUnit.MILLISECONDS);
//                        if (nal != null) {
//                            ByteBuffer buffer = mc.getInputBuffer(inputBufferId);
//                            buffer.clear();
//                            buffer.put(nal.data, nal.offset, nal.length);
//                            mc.queueInputBuffer(inputBufferId, 0, nal.length, nal.timestamp, 0);
//                        } else {
//                            mc.queueInputBuffer(inputBufferId, 0, 0, 0, 0);
//                        }
//                    } catch (Exception e) {
//                        Log.e(TAG, "onInputBufferAvailable error", e);
//                    }
//                }
//
//                @Override
//                public void onOutputBufferAvailable(MediaCodec mc, int outputBufferId, MediaCodec.BufferInfo info) {
//                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size <= 0) {
//                        mc.releaseOutputBuffer(outputBufferId, false);
//                        return;
//                    }
//                    long now = System.nanoTime();
//                    if (now - lastProcessTime < frameIntervalNs) {
//                        mc.releaseOutputBuffer(outputBufferId, false);
//                        return;
//                    }
//                    lastProcessTime = now;
//                    Image image = null;
//                    try {
//                        image = mc.getOutputImage(outputBufferId);
//
//                        if (image == null) {
//                            mc.releaseOutputBuffer(outputBufferId, false);
//                            return;
//                        }
//
//                        int imgW = image.getWidth();
//                        int imgH = image.getHeight();
//
//                        byte[] nv21 = YuvUtil.yuv420ToNv21(image).get();
//                        image.close();
//                        image = null;
//
//                        mc.releaseOutputBuffer(outputBufferId, false);
//
//                        byte[] resized = resizeNv21(nv21, imgW, imgH, targetWidth, targetHeight);
//                        callback.onYuvFrame(resized, targetWidth, targetHeight);
//
//                    } catch (Exception e) {
//                        Log.e(TAG, "onOutputBufferAvailable error", e);
//                        if (image != null) try { image.close(); } catch (Exception ignored) {}
//                        try { mc.releaseOutputBuffer(outputBufferId, false); } catch (Exception ignored) {}
//                    }
//                }
//
//                @Override
//                public void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
//                    Log.d(TAG, "YUV format changed: " + format);
//                }
//
//                @Override
//                public void onError(MediaCodec mc, MediaCodec.CodecException e) {
//                    Log.e(TAG, "YUV codec error", e);
//                    failure.onFailure(e);
//                }
//            });
//            codec.configure(format, null, null, 0);
//        }
//
//        void start() {
//            codec.start();
//        }
//
//        void feedNal(NalUnit nal) {
//            if (!yuvQueue.offer(nal)) {
//                yuvQueue.poll();
//                yuvQueue.offer(nal);
//            }
//        }
//
//        void stopDecoder() {
//            try {
//                codec.stop();
//                codec.release();
//            } catch (Exception ignored) {}
//        }
//    }
//
//    private byte[] resizeNv21(byte[] nv21, int srcW, int srcH, int dstW, int dstH) {
//        byte[] output = new byte[dstW * dstH * 3 / 2];
//        int srcFrameSize = srcW * srcH;
//        int dstFrameSize = dstW * dstH;
//
//        // ── Plano Y ──
//        for (int j = 0; j < dstH; j++) {
//            int srcY = j * srcH / dstH;
//            for (int i = 0; i < dstW; i++) {
//                int srcX = i * srcW / dstW;
//                output[j * dstW + i] = nv21[srcY * srcW + srcX];
//            }
//        }
//
//
//        int dstUvStart = dstFrameSize;
//        int srcUvStart = srcFrameSize;
//
//        for (int j = 0; j < dstH / 2; j++) {
//            int srcUvRow = j * (srcH / 2) / (dstH / 2);
//            for (int i = 0; i < dstW / 2; i++) {
//
//                int srcUvCol = i * (srcW / 2) / (dstW / 2);
//
//                int srcIndex = srcUvStart + srcUvRow * srcW + srcUvCol * 2;
//                int dstIndex = dstUvStart + j * dstW + i * 2;
//                output[dstIndex]     = nv21[srcIndex];     // V
//                output[dstIndex + 1] = nv21[srcIndex + 1]; // U
//            }
//        }
//        return output;
//    }
//
//
//    private static class NalUnit {
//        byte[] data;
//        int offset;
//        int length;
//        long timestamp;
//
//        NalUnit(byte[] d, int o, int l, long t) {
//            data = d;
//            offset = o;
//            length = l;
//            timestamp = t;
//        }
//    }
//
//    // ========= CALLBACKS =========
//
//    public interface RtspDecoderCallback {
//        void onResolutionResult(int width, int height);
//        void onYuvFrame(byte[] nv21, int width, int height);
//    }
//
//    public interface RtspDecoderFailure {
//        void onFailure(Exception e);
//    }
//}
//
//
