package br.dev.michaellopes.flutter_anycam.utils;

import android.graphics.Rect;
import android.media.Image;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import io.flutter.Log;

public class NativeUtil {

    static {
        System.loadLibrary("flutter_anycam_native_utils");
    }

    public static native void rotateNV21JNI(byte[] input, byte[] output, int width, int height, int rotation);

    public static void rotateNV21(byte[] nv21, byte[] output, int width, int height, int rotation) {
        rotateNV21JNI(nv21, output, width, height, rotation);
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
    private static native void cropNv21JNI(byte[] src,
                                   int srcW,
                                   int srcH,
                                   byte[] dst,
                                   int cropX,
                                   int cropY,
                                   int cropW,
                                   int cropH
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

    public static void cropNv21(
            byte[] src,
            int srcW,
            int srcH,
            byte[] dst,
            int cropX,
            int cropY,
            int cropW,
            int cropH
    ) {

        cropX &= ~1;
        cropY &= ~1;
        cropW &= ~1;
        cropH &= ~1;

        if (cropX + cropW > srcW) cropW = srcW - cropX;
        if (cropY + cropH > srcH) cropH = srcH - cropY;

        cropW &= ~1;
        cropH &= ~1;

        cropW = Math.max(2, cropW);
        cropH = Math.max(2, cropH);

        // Log.i("cropNv21", "srcW=" + srcW +"srcH=" + srcH);
        // Log.i("cropNv21", "cropX="  +cropX+"cropY=" +cropY+ "cropW=" +cropW+"cropH=" + cropH);

        cropNv21JNI(src, srcW, srcH, dst, cropX, cropY, cropW, cropH);
    }

    public static native void normalizeNative(
            ByteBuffer src,
            ByteBuffer output,
            int pixelCount,
            int dataType,
            int normalization,
            float invScale,
            int zeroPoint,
            float meanR, float meanG, float meanB,
            float stdR, float stdG, float stdB
    );

    public static byte[] cropNV21(byte[] img, int imgWidth, @NonNull Rect cropRect) {
        // 1.5 mean 1.0 for Y and 0.25 each for U and V
        int croppedImgSize = (int)Math.floor(cropRect.width() * cropRect.height() * 1.5);
        byte[] croppedImg = new byte[croppedImgSize];

        // Start points of UV plane
        int imgYPlaneSize = (int)Math.ceil(img.length / 1.5);
        int croppedImgYPlaneSize = cropRect.width() * cropRect.height();

        // Y plane copy
        for (int w = 0; w < cropRect.height(); w++) {
            int imgPos = (cropRect.top + w) * imgWidth + cropRect.left;
            int croppedImgPos = w * cropRect.width();
            System.arraycopy(img, imgPos, croppedImg, croppedImgPos, cropRect.width());
        }

        // UV plane copy
        // U and V are reduced by 2 * 2, so each row is the same size as Y
        // and is half U and half V data, and there are Y_rows/2 of UV_rows
        for (int w = 0; w < (int)Math.floor(cropRect.height() / 2.0); w++) {
            int imgPos = imgYPlaneSize + (cropRect.top / 2 + w) * imgWidth + cropRect.left;
            int croppedImgPos = croppedImgYPlaneSize + (w * cropRect.width());
            System.arraycopy(img, imgPos, croppedImg, croppedImgPos, cropRect.width());
        }

        return croppedImg;
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

    public static void applyFilter(
            byte[] nv21,
            int width,
            int height,
            int level) {

        if (level >= 2) {
            float contrast;
            switch (level) {
                case 2:
                    contrast = 1.2f;
                    break;
                case 3:
                    contrast = 1.35f;
                    break;
                default:
                    contrast = 1.60f;
                    break;
            }
            NativeUtil.increaseContrast(nv21, width, height, contrast);
        }
        if (level > 0) {
            NativeUtil.nv21ToGrayscale(nv21, width, height);
        }
    }
}