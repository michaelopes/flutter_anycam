package br.dev.michaellopes.flutter_anycam.utils;

import android.media.Image;

import androidx.camera.core.internal.utils.ImageUtil;

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

    private static native byte[] yuv420888ToNv21JNI(
            ByteBuffer y,
            ByteBuffer u,
            ByteBuffer v,
            int width,
            int height,
            int yRowStride,
            int uRowStride,
            int vRowStride,
            int uPixelStride,
            int vPixelStride
    );
    public static CompletableFuture<byte[]> yuv420ToNv21(Image image) {
        return CompletableFuture.supplyAsync(() -> {
            Image.Plane yPlane = image.getPlanes()[0];
            Image.Plane uPlane = image.getPlanes()[1];
            Image.Plane vPlane = image.getPlanes()[2];

            return yuv420888ToNv21JNI(
                    yPlane.getBuffer(),
                    uPlane.getBuffer(),
                    vPlane.getBuffer(),
                    image.getWidth(),
                    image.getHeight(),
                    yPlane.getRowStride(),
                    uPlane.getRowStride(),
                    vPlane.getRowStride(),
                    uPlane.getPixelStride(),
                    vPlane.getPixelStride()
            );
        });
    }
}