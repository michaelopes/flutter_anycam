package br.dev.michaellopes.flutter_anycam.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ImageConverterUtil {


    public static byte[] yv12ToNv21(byte[] yv12Bytes, int width, int height) {
        int frameSize = width * height;
        int qFrameSize = frameSize / 4; // 1/4 do tamanho para cada plano UV

        // Y + UV (intercalado) → tamanho final = frameSize + frameSize/2
        byte[] nv21Bytes = new byte[frameSize + frameSize / 2];

        // Copia o plano Y
        System.arraycopy(yv12Bytes, 0, nv21Bytes, 0, frameSize);

        // Índices no YV12
        int vStart = frameSize;           // Início do plano V
        int uStart = frameSize + qFrameSize; // Início do plano U

        // Intercala VU no NV21
        for (int i = 0; i < qFrameSize; i++) {
            nv21Bytes[frameSize + i * 2]     = yv12Bytes[vStart + i]; // V
            nv21Bytes[frameSize + i * 2 + 1] = yv12Bytes[uStart + i]; // U
        }

        return nv21Bytes;
    }

    public static byte[] i420ToNv21(byte[] i420Bytes, int width, int height) {
        int frameSize = width * height;
        int qFrameSize = frameSize / 4;
        byte[] nv21 = new byte[frameSize + frameSize / 2];

        // Copia Y diretamente
        System.arraycopy(i420Bytes, 0, nv21, 0, frameSize);

        // Em I420: Y | U | V
        int uStart = frameSize;
        int vStart = frameSize + qFrameSize;

        // Converte para NV21 (Y | VU intercalado)
        for (int i = 0; i < qFrameSize; i++) {
            nv21[frameSize + i * 2]     = i420Bytes[vStart + i]; // V
            nv21[frameSize + i * 2 + 1] = i420Bytes[uStart + i]; // U
        }

        return nv21;
    }

    public static FrameImageProxy convertNV21ToFrameImageProxy(byte[] nv21Data, int width, int height) {
        int frameSize = width * height;
        int qFrameSize = frameSize / 4;
        byte[] i420Data = new byte[frameSize + 2 * qFrameSize];

        System.arraycopy(nv21Data, 0, i420Data, 0, frameSize);

        int uIndex = frameSize;
        int vIndex = frameSize + qFrameSize;
        int uvStart = frameSize;

        for (int i = 0; i < frameSize / 2; i += 2) {
            i420Data[uIndex++] = nv21Data[uvStart + i + 1]; // U
            i420Data[vIndex++] = nv21Data[uvStart + i];     // V
        }

        return new FrameImageProxy(i420Data, width, height);
    }

    public static class FrameImageProxy {
        private final int width;
        private final int height;
        private final FramePlane[] planes;

        public FrameImageProxy(byte[] i420Data, int width, int height) {
            this.width = width;
            this.height = height;
            this.planes = buildPlanes(i420Data, width, height);
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public FramePlane[] getPlanes() {
            return planes;
        }

        private FramePlane[] buildPlanes(byte[] data, int width, int height) {
            int ySize = width * height;
            int uSize = ySize / 4;
            int vSize = ySize / 4;

            ByteBuffer yBuffer = ByteBuffer.wrap(data, 0, ySize);
            ByteBuffer uBuffer = ByteBuffer.wrap(data, ySize, uSize);
            ByteBuffer vBuffer = ByteBuffer.wrap(data, ySize + uSize, vSize);

            return new FramePlane[]{
                    new FramePlane(yBuffer, width, 1),           // Y plane
                    new FramePlane(uBuffer, width / 2, 2),       // U plane
                    new FramePlane(vBuffer, width / 2, 2)        // V plane
            };
        }
    }

    public static class FramePlane {
        private final ByteBuffer buffer;
        private final int rowStride;
        private final int pixelStride;

        public FramePlane(ByteBuffer buffer, int rowStride, int pixelStride) {
            this.buffer = buffer;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
        }

        public ByteBuffer getBuffer() {
            return buffer;
        }

        public int getRowStride() {
            return rowStride;
        }

        public int getPixelStride() {
            return pixelStride;
        }
    }

    public static byte[] nv21ToJpeg(byte[] bytes, Integer width, Integer height, Integer quality, Float rotation) {
        YuvImage yuv = new YuvImage(bytes, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, width, height), quality, out);
        byte[] jpegBytes = out.toByteArray();

        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

        float centerX = bitmap.getWidth() / 2f;
        float centerY = bitmap.getHeight() / 2f;

        Bitmap rotatedBitmap = BitmapPoolUtil.get(bitmap.getWidth(), bitmap.getHeight(), bitmap.getConfig());
        Canvas canvas = new Canvas(rotatedBitmap);

        Matrix matrix = new Matrix();
        matrix.postRotate(rotation, centerX, centerY);
        if (rotation == 270f) {
            matrix.postScale(-1f, 1f, centerX, centerY);
        }

        canvas.drawBitmap(bitmap, matrix, null);

        ByteArrayOutputStream finalOut = new ByteArrayOutputStream();
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, finalOut);
        byte[] rotatedBytes = finalOut.toByteArray();

        bitmap.recycle();
        BitmapPoolUtil.put(rotatedBitmap);

        return rotatedBytes;

    }
}
