package br.dev.michaellopes.flutter_anycam.webrtc;

import java.nio.ByteBuffer;

public class YuvUtil {

    static {
        System.loadLibrary("flutter_anycam_webrtc_yuv_utils");
    }

    public static native I420Image normalizeToI420JNI(I420Image src);

    public static native void releaseNativeBuffer(ByteBuffer buffer);

    public static I420Image normalizeToI420(I420Image src) {
        I420Image result = normalizeToI420JNI(src);
        if (result == null) {
            throw new IllegalStateException("normalizeToI420 failed");
        }
        result.setOnClose((ref) -> {
            releaseNativeBuffer(ref.dataY);
            releaseNativeBuffer(ref.dataU);
            releaseNativeBuffer(ref.dataV);
            src.close();
        });
        return result;
    }
}
