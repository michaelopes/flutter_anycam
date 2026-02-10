package br.dev.michaellopes.flutter_anycam.stream;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;

import androidx.lifecycle.LifecycleOwner;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import br.dev.michaellopes.flutter_anycam.utils.FastNv21ToNv12Converter;

public class FrameEncoder {

    public interface OnH264FrameListener {
        void onSpsPps(byte[] spsPps);
        void onH264Frame(byte[] frame, MediaCodec.BufferInfo info);
        void onVideoInfo(ByteBuffer sps, ByteBuffer pps, ByteBuffer vps);
    }


    private static final String TAG = "CameraXStreamer";
    private final AtomicBoolean isStarted = new AtomicBoolean(false);
    private int width;
    private int height;
    private final int bitrate;
    private final int fps;
    private final FastNv21ToNv12Converter fastNv21ToNv12Converter;
    private OnH264FrameListener listener;
    private MediaCodec encoder;
    private HandlerThread encoderThread;
    private Handler encoderHandler;


    private byte[] spsPpsCache;

    public FrameEncoder(int bitrate, int fps) {
        fastNv21ToNv12Converter = new FastNv21ToNv12Converter();
        this.bitrate = bitrate;
        this.fps = fps;
    }

    public void setOnH264FrameListener(OnH264FrameListener listener) {
        this.listener = listener;
    }

    private void start() {
        if(!isStarted.getAndSet(true)) {
            encoderThread = new HandlerThread("H264EncoderThread");
            encoderThread.start();
            encoderHandler = new Handler(encoderThread.getLooper());
            encoderHandler.post(this::setupEncoder);
        }
    }

    public void dispose() {
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
                encoder = null;
            }

            if (encoderThread != null) {
                encoderThread.quitSafely();
                encoderThread = null;
            }

        } catch (Exception e) {
            Log.e(TAG, "stop error", e);
        }
    }

    private long frameIndex = 0;

    @SuppressLint("UnsafeOptInUsageError")
    public synchronized void sendFrame(@NonNull byte[] nv21, int width, int height) {
        this.width = width;
        this.height = height;

        start();

        long ptsUs = computePresentationTimeUs(frameIndex++);
        byte[] nv12 = fastNv21ToNv12Converter.convert(nv21, width, height);
        encoderHandler.post(() -> encodeFrame(nv12, ptsUs));
    }

    private void setupEncoder() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    width,
                    height
            );

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            Log.i(TAG, "H264 encoder started");

            encoderHandler.post(this::drainEncoder);

        } catch (Exception e) {
            Log.e(TAG, "setupEncoder error", e);
        }
    }

    private void encodeFrame(byte[] nv12, long ptsUs) {
        if (encoder == null) return;

        try {
            int inputIndex = encoder.dequeueInputBuffer(0);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(nv12);
                }

                encoder.queueInputBuffer(inputIndex, 0, nv12.length, ptsUs, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "encodeFrame error", e);
        }
    }

    private void drainEncoder() {
        if (encoder == null) return;

        try {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (true) {
                int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0);

                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                }

                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    ByteBuffer sps = newFormat.getByteBuffer("csd-0");
                    ByteBuffer pps = newFormat.getByteBuffer("csd-1");

//                    ByteBuffer vps = newFormat.getByteBuffer("csd-0");
//                    ByteBuffer sps = newFormat.getByteBuffer("csd-1");
//                    ByteBuffer pps = newFormat.getByteBuffer("csd-2");

                    if (sps != null && pps != null) {
                        if (listener != null) listener.onVideoInfo(sps, pps, null);
                    }
                    Log.i(TAG, "Output format changed: " + newFormat);
                    continue;
                }

                if (outputIndex >= 0) {
                    ByteBuffer outBuffer = encoder.getOutputBuffer(outputIndex);

                    if (outBuffer != null && bufferInfo.size > 0) {
                        byte[] outData = new byte[bufferInfo.size];
                        outBuffer.position(bufferInfo.offset);
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        outBuffer.get(outData);

                        boolean isKeyFrame =
                                (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                        boolean isConfig =
                                (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;

                        if (isConfig) {
                            spsPpsCache = outData;
                            if (listener != null) listener.onSpsPps(outData);
                        } else {
                            if (listener != null) {
                                byte[] annexBFrame = avccToAnnexB(outData);
                                listener.onH264Frame(outData, bufferInfo);
                            }
                        }
                    }

                    encoder.releaseOutputBuffer(outputIndex, false);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "drainEncoder error", e);
        }

        if (encoderHandler != null) {
            encoderHandler.postDelayed(this::drainEncoder, 5);
        }
    }

    private byte[] avccToAnnexB(byte[] avcc) {
        int offset = 0;
        byte[] annexB = new byte[avcc.length + 4 * 10]; // margem
        int outPos = 0;

        while (offset + 4 <= avcc.length) {
            int nalSize =
                    ((avcc[offset] & 0xFF) << 24) |
                            ((avcc[offset + 1] & 0xFF) << 16) |
                            ((avcc[offset + 2] & 0xFF) << 8) |
                            ((avcc[offset + 3] & 0xFF));

            offset += 4;

            if (nalSize <= 0 || offset + nalSize > avcc.length) break;

            annexB[outPos++] = 0x00;
            annexB[outPos++] = 0x00;
            annexB[outPos++] = 0x00;
            annexB[outPos++] = 0x01;

            System.arraycopy(avcc, offset, annexB, outPos, nalSize);
            outPos += nalSize;

            offset += nalSize;
        }

        byte[] finalOut = new byte[outPos];
        System.arraycopy(annexB, 0, finalOut, 0, outPos);
        return finalOut;
    }

    private long computePresentationTimeUs(long frameIndex) {
        return frameIndex * 1_000_000L / fps;
    }
}