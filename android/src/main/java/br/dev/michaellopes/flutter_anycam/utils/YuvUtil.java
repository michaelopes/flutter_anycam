package br.dev.michaellopes.flutter_anycam.utils;

import android.media.Image;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

public class YuvUtil {

    static {
        System.loadLibrary("yuv_utils");
    }

    public static native byte[] rotateNV21JNI(byte[] input, int width, int height, int rotation);

    // Helper Java
    public static  byte[] rotateNV21(byte[] nv21, int width, int height, int rotation) {
      return rotateNV21JNI(nv21, width, height, rotation);
    }

    public static native void nv21ToNv12JNI(ByteBuffer nv21, ByteBuffer nv12, int width, int height);

    // Helper para ByteBuffer
    public static ByteBuffer nv21ToNv12(byte[] nv21, int width, int height) {
        try {
            ByteBuffer vNv21 = ByteBuffer.allocateDirect(nv21.length);
            vNv21.put(nv21);
            vNv21.rewind();
            ByteBuffer nv12 = ByteBuffer.allocateDirect(vNv21.capacity());
            nv21ToNv12JNI(vNv21, nv12, width, height);
            return nv12;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
    
    public static native byte[] yuv420ToNv21JNI(ByteBuffer yBuffer, ByteBuffer uBuffer, ByteBuffer vBuffer,
                                                int width, int height,
                                                int yRowStride, int uvRowStride, int uvPixelStride);
    public static CompletableFuture<byte[]> yuv420ToNv21(Image image) {
        return CompletableFuture.supplyAsync(() -> {
            ByteBuffer y = image.getPlanes()[0].getBuffer();
            ByteBuffer u = image.getPlanes()[1].getBuffer();
            ByteBuffer v = image.getPlanes()[2].getBuffer();

            int width = image.getWidth();
            int height = image.getHeight();

            int yRowStride = image.getPlanes()[0].getRowStride();
            int uvRowStride = image.getPlanes()[1].getRowStride();
            int uvPixelStride = image.getPlanes()[1].getPixelStride();

            return yuv420ToNv21JNI(y, u, v, width, height, yRowStride, uvRowStride, uvPixelStride);
        });
    }
}