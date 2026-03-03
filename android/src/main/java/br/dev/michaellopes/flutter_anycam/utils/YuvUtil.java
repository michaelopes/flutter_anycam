package br.dev.michaellopes.flutter_anycam.utils;

import android.media.Image;

import androidx.camera.core.internal.utils.ImageUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class YuvUtil {

    static {
        System.loadLibrary("flutter_anycam_yuv_utils");
    }

    public static native byte[] rotateNV21JNI(byte[] input, int width, int height, int rotation);

    public static byte[] rotateNV21(byte[] nv21, int width, int height, int rotation) {
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

    private static native void yuv420888ToNv21IntoJNI(
            ByteBuffer y,
            ByteBuffer u,
            ByteBuffer v,
            byte[] outNv21,
            int width,
            int height,
            int yRowStride,
            int uRowStride,
            int vRowStride,
            int uPixelStride,
            int vPixelStride
    );

    private static native void resizeNv21JNI(
            byte[] src,
            int srcW,
            int srcH,
            byte[] dst,
            int dstW,
            int dstH
    );


    public static void resizeNv21(
            byte[] src,
            int srcW,
            int srcH,
            byte[] dst,
            int dstW,
            int dstH
    ) {
        resizeNv21JNI(src, srcW, srcH, dst, dstW, dstH);
    }

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

    public static void yuv420ToNv21(Image image, byte[] out) {
        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];
        yuv420888ToNv21IntoJNI(
                yPlane.getBuffer(),
                uPlane.getBuffer(),
                vPlane.getBuffer(),
                out,
                image.getWidth(),
                image.getHeight(),
                yPlane.getRowStride(),
                uPlane.getRowStride(),
                vPlane.getRowStride(),
                uPlane.getPixelStride(),
                vPlane.getPixelStride()
        );
    }

    public static void nv21ToGrayscale(byte[] nv21, int width, int height) {
        int frameSize = width * height;
        for (int i = frameSize; i < nv21.length; i++) {
            nv21[i] = (byte) 128;
        }
    }

    public static void increaseContrast(byte[] nv21, int width, int height, float contrast) {
        int frameSize = width * height;
        for (int i = 0; i < frameSize; i++) {
            int y = nv21[i] & 0xFF;
            int newY = (int)((y - 128) * contrast + 128);
            if (newY < 0) newY = 0;
            if (newY > 255) newY = 255;
            nv21[i] = (byte) newY;
        }
    }
}