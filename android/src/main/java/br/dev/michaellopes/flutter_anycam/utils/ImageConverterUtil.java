package br.dev.michaellopes.flutter_anycam.utils;

import java.nio.ByteBuffer;

public class ImageConverterUtil {


    public static byte[] yv12ToNv21(byte[] yv12Bytes, int width, int height) {
        int frameSize = width * height;
        byte[] nv21Bytes = new byte[frameSize + frameSize / 2];

        if (yv12Bytes.length != frameSize + (frameSize / 4) * 2) {
            throw new IllegalArgumentException("Tamanho do array YV12 está incorreto");
        }

        System.arraycopy(yv12Bytes, 0, nv21Bytes, 0, frameSize);

        int vIndex = frameSize;                    // V começa logo após Y
        int uIndex = frameSize + frameSize / 4;   // U vem depois de V

        for (int i = 0; i < frameSize / 4; i++) {
            nv21Bytes[frameSize + i * 2] = yv12Bytes[vIndex + i];       // V
            nv21Bytes[frameSize + i * 2 + 1] = yv12Bytes[uIndex + i];   // U
        }

        return nv21Bytes;
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
}
