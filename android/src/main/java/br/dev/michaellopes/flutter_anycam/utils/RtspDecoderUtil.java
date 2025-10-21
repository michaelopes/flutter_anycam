package br.dev.michaellopes.flutter_anycam.utils;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;

public class RtspDecoderUtil {
    private static final String TAG = "RtspDecoder";
    private MediaCodec mediaCodec;
    private final RtspDecoderCallback callback;

    private boolean defaultCallbackCalled = false;
    private boolean ignoreFirstFrameCallback = true;
    private int keyColorFormat = 0;
    private  int width = 640;
    private int height = 480;


    public RtspDecoderUtil(Surface surface, RtspDecoderCallback callback, RtspDecoderFailure failure) {
        this.callback = callback;
        try {

            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 640, 480);
            ImageReader imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);

            mediaCodec = MediaCodec.createByCodecName("OMX.google.h264.decoder");
            mediaCodec.configure(format, surface, null, 0);

            mediaCodec.start();


           // mediaCodec.setOutputSurface(imageReader.getSurface());

            this.keyColorFormat = mediaCodec.getOutputFormat().getInteger(MediaFormat.KEY_COLOR_FORMAT);

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
               // decodeYuv(bufferInfo, outIndex);
            }

            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = mediaCodec.getOutputFormat();
                 width = newFormat.getInteger(MediaFormat.KEY_WIDTH);
                 height = newFormat.getInteger(MediaFormat.KEY_HEIGHT);
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

    private void decodeYuv(MediaCodec.BufferInfo info, int index) {
        byte[] yuvByteArray = null;
        ByteBuffer buffer = mediaCodec.getOutputBuffer(index);
        if (buffer != null) {
            buffer.position(info.offset);
            buffer.limit(info.offset + info.size);
            yuvByteArray = new byte[buffer.remaining()];
            buffer.get(yuvByteArray);
            callback.onFrameDecoded(yuvByteArray, width, height);
        }

    }


    public void release() {
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }
    }


    private static class FrameData {
        final byte[] data;
        final int offset;
        final int length;
        final long timestamp;
        FrameData(byte[] data, int offset, int length, long timestamp) {
            this.data = data;
            this.offset = offset;
            this.length = length;
            this.timestamp = timestamp;
        }
    }

    public interface RtspDecoderCallback {
        void onResolutionResult(int width, int height);
        void onFrameDecoded(byte[] yuv, int width, int height);


    }

    public interface RtspDecoderFailure {
        void onFailure(Exception e);
    }
}