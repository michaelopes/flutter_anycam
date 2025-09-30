package br.dev.michaellopes.flutter_anycam.utils;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

public class RtspDecoderUtil {
    private static final String TAG = "RtspDecoder";
    private MediaCodec mediaCodec;
    private final RtspDecoderCallback callback;

    private boolean defaultCallbackCalled = false;
    private boolean ignoreFirstFrameCallback = true;

    public RtspDecoderUtil(Surface surface, RtspDecoderCallback callback, RtspDecoderFailure failure) {
        this.callback = callback;
        try {
            mediaCodec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 640, 480);

            mediaCodec.configure(format, surface, null, 0);
            mediaCodec.start();
        } catch (Exception e) {
            failure.onFailure(e);
        }
    }

    public void decode(byte[] data, int offset, int length, long timestamp) {
        try {
            int inIndex = mediaCodec.dequeueInputBuffer(10000);
            if (inIndex >= 0) {
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data, offset, length);
                    mediaCodec.queueInputBuffer(inIndex, 0, length, timestamp, 0);
                }
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);

            while (outIndex >= 0) {
                mediaCodec.releaseOutputBuffer(outIndex, true);
                outIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
            }

            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = mediaCodec.getOutputFormat();
                int width = newFormat.getInteger(MediaFormat.KEY_WIDTH);
                int height = newFormat.getInteger(MediaFormat.KEY_HEIGHT);
                callback.onResolutionResult(width, height);
                defaultCallbackCalled = true;
                Log.d(TAG, "Stream resolution: " + width + "x" + height);
            } else if (!defaultCallbackCalled && !ignoreFirstFrameCallback) {
                defaultCallbackCalled = true;
                callback.onResolutionResult(640, 480);
            }
            ignoreFirstFrameCallback = false;
        } catch (Exception e) {
            Log.e(TAG, "Decode error", e);
        }
    }

    public void release() {
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }
    }

    public interface RtspDecoderCallback {
        void onResolutionResult(int width, int height);
    }

    public interface RtspDecoderFailure {
        void onFailure(Exception e);
    }
}