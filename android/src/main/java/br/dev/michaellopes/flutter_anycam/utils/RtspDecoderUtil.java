package br.dev.michaellopes.flutter_anycam.utils;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Fallback analysis decoder used only when the GL pipeline is unavailable: decodes the
 * NAL units forwarded by RtspProcessor into {@link Image} frames (ByteBuffer mode).
 * Preview in that mode is rendered by the library's own decoder straight into Flutter's
 * surface, so this class never touches a Surface.
 */
public class RtspDecoderUtil {

    private static final String TAG = "RtspDecoderUtil";
    /** Format hint only; the real size comes from SPS / OUTPUT_FORMAT_CHANGED. */
    private static final int FORMAT_HINT_W = 1280;
    private static final int FORMAT_HINT_H = 720;
    private static final int MAX_PENDING_NALS = 30;

    private final String mimeType;
    private final RtspDecoderCallback callback;
    private final RtspDecoderFailure failure;

    private final Object lock = new Object();
    private final ArrayDeque<NalUnit> pendingNals = new ArrayDeque<>();
    private final ArrayDeque<Integer> freeInputs = new ArrayDeque<>();

    private final ByteArrayPool nalPool = new ByteArrayPool(
            new int[]{1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072},
            32
    );

    private HandlerThread callbackThread;
    private MediaCodec codec;
    private boolean running = false;

    public RtspDecoderUtil(String mimeType, RtspDecoderCallback callback, RtspDecoderFailure failure) {
        this.mimeType = mimeType;
        this.callback = callback;
        this.failure = failure;
    }

    public void start() {
        try {
            callbackThread = new HandlerThread("RtspAnalysisDecoder");
            callbackThread.start();

            MediaCodec mc = MediaCodec.createDecoderByType(mimeType);
            mc.setCallback(new CodecCallback(), new Handler(callbackThread.getLooper()));
            mc.configure(MediaFormat.createVideoFormat(mimeType, FORMAT_HINT_W, FORMAT_HINT_H), null, null, 0);
            synchronized (lock) {
                codec = mc;
                running = true;
            }
            mc.start();
        } catch (Exception e) {
            dispose();
            failure.onFailure(e);
        }
    }

    public void dispose() {
        MediaCodec mc;
        synchronized (lock) {
            running = false;
            mc = codec;
            codec = null;
            freeInputs.clear();
            NalUnit leftover;
            while ((leftover = pendingNals.poll()) != null) {
                leftover.release();
            }
        }
        if (mc != null) {
            try {
                mc.stop();
            } catch (Exception ignored) {
            }
            try {
                mc.release();
            } catch (Exception ignored) {
            }
        }
        if (callbackThread != null) {
            callbackThread.quitSafely();
            callbackThread = null;
        }
    }

    public void dispatchNal(byte[] data, int offset, int length, long ts) {
        if (length <= 0) return;
        synchronized (lock) {
            if (!running) return;

            byte[] buf = nalPool.acquire(length);
            System.arraycopy(data, offset, buf, 0, length);
            NalUnit nal = new NalUnit(buf, length, ts, nalPool);

            Integer input = freeInputs.poll();
            if (input != null) {
                queueInput(input, nal);
                return;
            }
            if (pendingNals.size() >= MAX_PENDING_NALS) {
                NalUnit dropped = pendingNals.poll();
                if (dropped != null) dropped.release();
            }
            pendingNals.offer(nal);
        }
    }

    /** Must hold {@link #lock}. Always releases {@code nal}. */
    private void queueInput(int index, NalUnit nal) {
        try {
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || buffer.capacity() < nal.length) {
                freeInputs.offer(index);
                return;
            }
            buffer.clear();
            buffer.put(nal.data, 0, nal.length);
            codec.queueInputBuffer(index, 0, nal.length, nal.timestamp, 0);
        } catch (Exception e) {
            Log.w(TAG, "Analysis input error", e);
        } finally {
            nal.release();
        }
    }

    private class CodecCallback extends MediaCodec.Callback {

        @Override
        public void onInputBufferAvailable(MediaCodec mc, int index) {
            synchronized (lock) {
                if (!running || mc != codec) return;
                NalUnit nal = pendingNals.poll();
                if (nal != null) {
                    queueInput(index, nal);
                } else {
                    freeInputs.offer(index);
                }
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec mc, int index, MediaCodec.BufferInfo info) {
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size <= 0) {
                releaseOutput(mc, index);
                return;
            }
            Image image = null;
            try {
                image = mc.getOutputImage(index);
                if (image != null) {
                    // Caller must finish before the image is closed (same contract as Camera2).
                    callback.onAnalysisImage(image);
                }
            } catch (Exception e) {
                Log.e(TAG, "Analysis output error", e);
            } finally {
                if (image != null) {
                    try {
                        image.close();
                    } catch (Exception ignored) {
                    }
                }
                releaseOutput(mc, index);
            }
        }

        @Override
        public void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
        }

        @Override
        public void onError(MediaCodec mc, MediaCodec.CodecException e) {
            Log.e(TAG, "Analysis codec error", e);
            failure.onFailure(e);
        }

        private void releaseOutput(MediaCodec mc, int index) {
            try {
                mc.releaseOutputBuffer(index, false);
            } catch (Exception ignored) {
            }
        }
    }

    // ========= NAL + POOL =========

    private static final class NalUnit {
        final byte[] data;
        final int length;
        final long timestamp;
        private final ByteArrayPool pool;

        NalUnit(byte[] data, int length, long timestamp, ByteArrayPool pool) {
            this.data = data;
            this.length = length;
            this.timestamp = timestamp;
            this.pool = pool;
        }

        void release() {
            pool.release(data);
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
            if (idx < 0 || arr.length != bucketSizes[idx]) return;
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
        /**
         * Analysis frame. Must copy any needed plane data before returning —
         * the {@link Image} is closed immediately after this call.
         */
        void onAnalysisImage(Image image);
    }

    public interface RtspDecoderFailure {
        void onFailure(Exception e);
    }
}
