package br.dev.michaellopes.flutter_anycam.utils;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

import io.flutter.Log;
import io.github.crow_misia.libyuv.AbgrBuffer;
import io.github.crow_misia.libyuv.ArgbBuffer;
import io.github.crow_misia.libyuv.FilterMode;
import io.github.crow_misia.libyuv.I420Buffer;
import io.github.crow_misia.libyuv.Nv12Buffer;
import io.github.crow_misia.libyuv.Nv21Buffer;
import io.github.crow_misia.libyuv.Plane;
import io.github.crow_misia.libyuv.PlaneProxy;
import io.github.crow_misia.libyuv.RotateMode;
import io.github.crow_misia.libyuv.ext.ImageExt;

public class FastYuvToNv21Converter {

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    public synchronized byte[] convert(@NonNull ImageProxy image) {


        Nv12Buffer nv21Buffer = ImageExt.toNv12Buffer(image);

   //     Nv21Buffer nv21Buffer = Nv21Buffer.Factory.allocate(image.getWidth(), image.getHeight());

     //   i420Buffer.convertTo(nv21Buffer);

        ByteBuffer buffer = nv21Buffer.asBuffer();
        buffer.rewind();

        byte[] nv21 = new byte[buffer.remaining()];
        buffer.get(nv21);



        return nv21;

       /* int width = image.getWidth();
        int height = image.getHeight();

        int ySize = width * height;
        int uvSize = width * height / 4;

        byte[] nv21 = new byte[ySize + uvSize * 2];

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        yBuffer.rewind();
        uBuffer.rewind();
        vBuffer.rewind();

        int rowStride = image.getPlanes()[0].getRowStride();

        if (image.getPlanes()[0].getPixelStride() != 1) {
            throw new IllegalArgumentException("Y plane pixelStride must be 1");
        }

        int pos = 0;

        if (rowStride == width) {
            yBuffer.get(nv21, 0, ySize);
            pos += ySize;
        } else {
            long yBufferPos = -rowStride; // not an actual position
            while (pos < ySize) {
                yBufferPos += rowStride;
                yBuffer.position((int) yBufferPos);
                yBuffer.get(nv21, pos, width);
                pos += width;
            }
        }

        rowStride = image.getPlanes()[2].getRowStride();
        int pixelStride = image.getPlanes()[2].getPixelStride();

        if (rowStride != image.getPlanes()[1].getRowStride()) {
            throw new IllegalArgumentException("U and V rowStride must match");
        }
        if (pixelStride != image.getPlanes()[1].getPixelStride()) {
            throw new IllegalArgumentException("U and V pixelStride must match");
        }

        if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
            byte savePixel = vBuffer.get(1);
            try {
                vBuffer.put(1, (byte) ~savePixel);

                if (uBuffer.get(0) == (byte) ~savePixel) {
                    vBuffer.put(1, savePixel);

                    vBuffer.position(0);
                    uBuffer.position(0);

                    vBuffer.get(nv21, ySize, 1);
                    uBuffer.get(nv21, ySize + 1, uBuffer.remaining());

                    return nv21; // shortcut
                }
            } catch (ReadOnlyBufferException ex) {
                // cannot check overlap
            }

            vBuffer.put(1, savePixel);
        }

        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int vuPos = col * pixelStride + row * rowStride;
                nv21[pos++] = vBuffer.get(vuPos);
                nv21[pos++] = uBuffer.get(vuPos);
            }
        }

        return nv21;*/
    }

    public static void nv12ToNv21(byte[] nv12, byte[] nv21, int width, int height) {
        int frameSize = width * height;

        System.arraycopy(nv12, 0, nv21, 0, frameSize);

        for (int i = 0; i < frameSize / 2; i += 2) {
            nv21[frameSize + i] = nv12[frameSize + i + 1];     // V
            nv21[frameSize + i + 1] = nv12[frameSize + i];     // U
        }
    }
    public synchronized void dispose() {
//        if (i420Buffer != null) {
//            i420Buffer.close();
//            i420Buffer = null;
//        }
//
//        if (nv21Buffer != null) {
//            nv21Buffer.close();
//            nv21Buffer = null;
//        }
    }

}
