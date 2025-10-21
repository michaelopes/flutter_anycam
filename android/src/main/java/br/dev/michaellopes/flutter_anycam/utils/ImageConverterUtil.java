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
import java.util.HashMap;
import java.util.Map;

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
            nv21Bytes[frameSize + i * 2] = yv12Bytes[vStart + i]; // V
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
            nv21[frameSize + i * 2] = i420Bytes[vStart + i]; // V
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

    public static N21Image jpegToNV21(byte[] jpegBytes) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int frameSize = width * height;
        byte[] nv21 = new byte[frameSize * 3 / 2];

        int[] argb = new int[frameSize];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);

        int yIndex = 0;
        int uvIndex = frameSize;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int color = argb[j * width + i];
                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

                y = clamp(y);
                u = clamp(u);
                v = clamp(v);

                nv21[yIndex++] = (byte) y;

                // Para NV21, intercalamos VU (não UV)
                if (j % 2 == 0 && i % 2 == 0 && uvIndex + 1 < nv21.length) {
                    nv21[uvIndex++] = (byte) v;
                    nv21[uvIndex++] = (byte) u;
                }
            }
        }

        return new N21Image(nv21, width, height);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    public static byte[] nv21ToJpeg(byte[] bytes, Integer width, Integer height, Integer quality, Float rotation) {
        YuvImage yuv = new YuvImage(bytes, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, width, height), quality, out);
        byte[] jpegBytes = out.toByteArray();

        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

        Matrix matrix = new Matrix();
        int centerX = width / 2;
        int centerY = height / 2;

        matrix.postRotate(rotation, centerX, centerY);

        if(rotation == 270) {
            matrix.postScale(-1, 1, centerX, centerY);
        }

        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);

        ByteArrayOutputStream finalOut = new ByteArrayOutputStream();
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, finalOut);
        byte[] rotatedBytes = finalOut.toByteArray();

        bitmap.recycle();
        rotatedBitmap.recycle();

        return rotatedBytes;
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


   public static class N21Image {
        private final byte[] bytes;
        private final int width;
        private final int height;

        N21Image(byte[] bytes, int width, int height) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public Map<String, Object> getMap() {
            Map<String, Object> result = new HashMap<>();
            result.put("bytes", bytes);
            result.put("width", width);
            result.put("height", height);
            return result;
        }
    }

}
