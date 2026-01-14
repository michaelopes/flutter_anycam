package br.dev.michaellopes.flutter_anycam.model;

import android.media.Image;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class FrameImage {

    private final int width;
    private final int height;
    private final FramePlane[] planes;

    public FrameImage(byte[] yuvData, int width, int height) {
        this.width = width;
        this.height = height;
        this.planes = buildPlanesByYUV(yuvData, width, height);
    }

    public FrameImage(Image image) {
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.planes = buildPlanesByImage(image);
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


    private FramePlane[] buildPlanesByImage(Image image) {

        FramePlane[] framePlanes = new FramePlane[]{};
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) {
            return framePlanes;
        }

        for (int i = 0; i < planes.length; i++) {
            Image.Plane plane = planes[i];
            ByteBuffer buffer = plane.getBuffer().duplicate();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            framePlanes[i] = new FramePlane(buffer, plane.getRowStride(), plane.getPixelStride());
        }

        return framePlanes;
    }

    private FramePlane[] buildPlanesByYUV(byte[] data, int width, int height) {
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
