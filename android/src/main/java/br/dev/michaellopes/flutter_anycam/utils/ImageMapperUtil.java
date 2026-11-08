package br.dev.michaellopes.flutter_anycam.utils;

import android.annotation.SuppressLint;
import android.media.Image;
import android.util.Size;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ImageMapperUtil {
    /**
     * Ping-pong NV21 buffers so an in-flight EventChannel encode on the main thread
     * is not overwritten by the next analyze() on low-end devices (5fps still races
     * under main-thread jank). Avoids per-frame {@code new byte[]} allocation.
     */
    private final byte[][] nv21Buffers = new byte[2][];
    private final byte[][] nv21ResizedBuffers = new byte[2][];
    private int nv21Slot = 0;
    private int nv21ResizedSlot = 0;

    private byte[] obtainNv21Buffer(int size) {
        byte[] buf = nv21Buffers[nv21Slot];
        if (buf == null || buf.length != size) {
            buf = new byte[size];
            nv21Buffers[nv21Slot] = buf;
        }
        nv21Slot = 1 - nv21Slot;
        return buf;
    }

    private byte[] obtainNv21ResizedBuffer(int size) {
        byte[] buf = nv21ResizedBuffers[nv21ResizedSlot];
        if (buf == null || buf.length != size) {
            buf = new byte[size];
            nv21ResizedBuffers[nv21ResizedSlot] = buf;
        }
        nv21ResizedSlot = 1 - nv21ResizedSlot;
        return buf;
    }

    /**
     * Resolves the requested resize against the source size: (-1, -1) means the centered
     * square side, a single -1 keeps the aspect ratio. Dimensions are even (NV21 chroma is
     * 2x2 subsampled) and at least 2.
     */
    public static int[] targetSize(Size resizeFrame, int srcWidth, int srcHeight) {
        return targetSize(resizeFrame.getWidth(), resizeFrame.getHeight(), srcWidth, srcHeight);
    }

    static int[] targetSize(int w, int h, int srcWidth, int srcHeight) {
        if (w <= 0 && h <= 0) {
            w = Math.min(srcWidth, srcHeight);
            h = w;
        } else if (w <= 0) {
            w = Math.round(h * (float) srcWidth / srcHeight);
        } else if (h <= 0) {
            h = Math.round(w * (float) srcHeight / srcWidth);
        }
        return new int[]{Math.max(2, w & ~1), Math.max(2, h & ~1)};
    }

    public Map<String, Object> imageProxyToNV21Map(ImageProxy imageProxy, Size resizeFrame, int filter, Integer customRotationDegrees) {
        return imageProxyToNV21Map(imageProxy, resizeFrame, filter, customRotationDegrees, null);
    }

    public Map<String, Object> imageToNV21Map(
            Image image,
            int rotationDegrees,
            Size resizeFrame,
            int filter,
            Integer customRotationDegrees
    ) {
        try {
            if (image == null) return new HashMap<>();

            int srcWidth = image.getWidth();
            int srcHeight = image.getHeight();
            int width = srcWidth;
            int height = srcHeight;
            int rowStride;
            int pixelStride;

            int srcSize = srcWidth * srcHeight * 3 / 2;
            byte[] bytesBuffer = obtainNv21Buffer(srcSize);
            NativeUtil.yuv420ToNv21(image, bytesBuffer);

            int sensorOrientation = customRotationDegrees != null
                    ? customRotationDegrees
                    : rotationDegrees;

            Map<String, Object> rawFrame = null;
            if (resizeFrame != null) {
                // Same contract as imageProxyToNV21Map / usbFrameToNV21Map.
                rawFrame = new HashMap<>();
                rawFrame.put("width", srcWidth);
                rawFrame.put("height", srcHeight);
                rawFrame.put("format", "NV21");
                rawFrame.put("bytes", bytesBuffer);
                rawFrame.put("rotation", sensorOrientation);
                rawFrame.put("rowStride", srcWidth);
                rawFrame.put("pixelStride", 1);
            }

            byte[] finalBytes;
            if (resizeFrame != null) {
                int[] target = targetSize(resizeFrame, srcWidth, srcHeight);
                int targetWidth = target[0];
                int targetHeight = target[1];

                int dstSize = targetWidth * targetHeight * 3 / 2;
                byte[] bytesResizedBuffer = obtainNv21ResizedBuffer(dstSize);
                NativeUtil.resizeNv21(bytesBuffer, srcWidth, srcHeight, bytesResizedBuffer, targetWidth, targetHeight);
                finalBytes = bytesResizedBuffer;
                width = targetWidth;
                height = targetHeight;
            } else {
                finalBytes = bytesBuffer;
            }
            // NV21 output is always tightly packed, whatever the sensor plane stride is.
            rowStride = width;
            pixelStride = 1;

            Map<String, Object> result = new HashMap<>();
            result.put("height", height);
            result.put("width", width);
            result.put("format", "NV21");
            NativeUtil.applyFilter(finalBytes, width, height, filter);
            result.put("bytes", finalBytes);
            result.put("rotation", sensorOrientation);
            result.put("rowStride", rowStride);
            result.put("pixelStride", pixelStride);
            if (rawFrame != null) {
                result.put("rawFrame", rawFrame);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    public Map<String, Object> imageProxyToNV21Map(ImageProxy imageProxy, Size resizeFrame, int filter, Integer customRotationDegrees, byte[] nv21) {
        try {

            Image image = imageProxy.getImage();
            if (image == null) return new HashMap<>();
            Map<String, Object> rawFrame = null;

            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride;
            int pixelStride;
            final byte[] nv21Working;

            if (nv21 == null) {
                int srcSize = width * height * 3 / 2;
                byte[] bytesBuffer = obtainNv21Buffer(srcSize);
                NativeUtil.yuv420ToNv21(image, bytesBuffer);
                nv21Working = bytesBuffer;
            } else {
                nv21Working = nv21;
            }

            if(resizeFrame != null) {
                long start = System.currentTimeMillis();
                rawFrame = imageProxyToNV21Map(imageProxy, null, 0, customRotationDegrees, nv21Working);
                long inferenceMs = System.currentTimeMillis() - start;
               // Log.d("imageProxyToNV21Map_PERF", "inference=" + inferenceMs + "ms");
            }

            byte[] finalBytes;
            if (resizeFrame != null) {
                int[] target = targetSize(resizeFrame, width, height);
                int targetWidth = target[0];
                int targetHeight = target[1];

                int dstSize = targetWidth * targetHeight * 3 / 2;
                byte[] bytesResizedBuffer = obtainNv21ResizedBuffer(dstSize);
                NativeUtil.resizeNv21(nv21Working, width, height, bytesResizedBuffer, targetWidth, targetHeight);
                finalBytes = bytesResizedBuffer;
                width = targetWidth;
                height = targetHeight;
            } else {
                finalBytes = nv21Working;
            }
            rowStride = width;
            pixelStride = 1;


            Map<String, Object> result = new HashMap<>();

            int sensorOrientation;
            if (customRotationDegrees != null) {
                sensorOrientation = customRotationDegrees;
            } else {
                sensorOrientation = imageProxy.getImageInfo().getRotationDegrees();
            }

            result.put("height", height);
            result.put("width", width);
            result.put("format", "NV21");

            NativeUtil.applyFilter(finalBytes, width, height, filter);

            result.put("bytes", finalBytes);
            result.put("rotation", sensorOrientation);
            result.put("rowStride", rowStride);
            result.put("pixelStride", pixelStride);
            if(rawFrame != null) {
                result.put("rawFrame", rawFrame);
            }

            return result;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    @SuppressLint("RestrictedApi")
    public Map<String, Object> imageProxyToI420Map(ImageProxy image, Integer customRotationDegrees) {
        try {
            List<Map<String, Object>> planesAdapter = imagePlanesAdapter(image);
            Map<String, Object> adapter = imageProxyBaseAdapter(image);

            adapter.put("planes", planesAdapter);
            if (customRotationDegrees != null) {
                adapter.put("rotation", customRotationDegrees);
            }
            return adapter;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Camera2 {@link Image} → I420 plane map for raw stream. Copies plane bytes so the
     * Image can be closed immediately after return.
     */
    public Map<String, Object> imageToI420Map(
            Image image,
            int rotationDegrees,
            Integer customRotationDegrees
    ) {
        try {
            if (image == null) return new HashMap<>();

            Map<String, Object> adapter = new HashMap<>(6);
            adapter.put("height", image.getHeight());
            adapter.put("width", image.getWidth());
            adapter.put("format", "YUV_420_888");
            adapter.put(
                    "rotation",
                    customRotationDegrees != null ? customRotationDegrees : rotationDegrees
            );
            adapter.put("planes", imagePlanesAdapter(image));
            return adapter;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Map<String, Object>> imagePlanesAdapter(Image image) {
        Image.Plane[] planes = image.getPlanes();
        List<Map<String, Object>> planeData = new ArrayList<>(planes.length);

        for (Image.Plane plane : planes) {
            ByteBuffer buffer = plane.getBuffer();
            buffer.rewind();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            Map<String, Object> planeMap = new HashMap<>(3);
            planeMap.put("bytes", bytes);
            planeMap.put("rowStride", plane.getRowStride());
            planeMap.put("pixelStride", plane.getPixelStride());
            planeData.add(planeMap);
        }

        return planeData;
    }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private Map<String, Object> imageProxyBaseAdapter(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) return new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        result.put("height", image.getHeight());
        result.put("width", image.getWidth());
        result.put("format", "YUV_420_888");
        result.put("rotation", imageProxy.getImageInfo().getRotationDegrees());
        return result;
    }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private List<Map<String, Object>> imagePlanesAdapter(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) return new ArrayList<>();

        Image.Plane[] planes = image.getPlanes();
        List<Map<String, Object>> planeData = new ArrayList<>();

        for (Image.Plane plane : planes) {
            ByteBuffer buffer = plane.getBuffer();
            buffer.rewind();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            Map<String, Object> planeMap = new HashMap<>();
            planeMap.put("bytes", bytes);
            planeMap.put("rowStride", plane.getRowStride());
            planeMap.put("pixelStride", plane.getPixelStride());

            planeData.add(planeMap);
        }

        return planeData;
    }

    public Map<String, Object> usbFrameToNV21Map(ByteBuffer buffer, int width, int height, Size resizeFrame, int filter, Integer customRotationDegrees) {
        int srcSize = buffer.remaining();
        byte[] bytesBuffer = obtainNv21Buffer(srcSize);
        buffer.get(bytesBuffer);
        return usbFrameToNV21Map(bytesBuffer, width, height, resizeFrame, filter, customRotationDegrees);
    }

    public Map<String, Object> usbFrameToNV21Map(byte[] nv21, int width, int height, Size resizeFrame, int filter, Integer customRotationDegrees) {
        Map<String, Object> rawFrame = null;

        if(resizeFrame != null) {
            rawFrame = usbFrameToNV21Map(nv21, width, height, null, 0, customRotationDegrees);
        }

        byte[] finalBytes;
        if (resizeFrame != null) {
            int[] target = targetSize(resizeFrame, width, height);
            int targetWidth = target[0];
            int targetHeight = target[1];
            int dstSize = targetWidth * targetHeight * 3 / 2;
            byte[] bytesResizedBuffer = obtainNv21ResizedBuffer(dstSize);
            NativeUtil.resizeNv21(nv21, width, height, bytesResizedBuffer, targetWidth, targetHeight);
            finalBytes = bytesResizedBuffer;
            width = targetWidth;
            height = targetHeight;
        } else {
            finalBytes = nv21;
        }


        Map<String, Object> image = new HashMap<>();
        image.put("width", width);
        image.put("height", height);
        if (customRotationDegrees != null) {
            image.put("rotation", customRotationDegrees);
        } else {
            image.put("rotation", 0);
        }

        NativeUtil.applyFilter(finalBytes, width, height, filter);
        image.put("bytes", finalBytes);
        image.put("format", "NV21");
        image.put("rowStride", width);
        image.put("pixelStride", 1);
        if(rawFrame != null) {
            image.put("rawFrame", rawFrame);
        }
        return image;
    }

    public Map<String, Object> usbFrameToI420Map(ByteBuffer buffer, int width, int height, Integer customRotationDegrees) {
        byte[] nv21Bytes = new byte[buffer.remaining()];
        buffer.get(nv21Bytes);

        ImageConverterUtil.FrameImageProxy imageProxy = ImageConverterUtil.convertNV21ToFrameImageProxy(nv21Bytes, width, height);

        buffer.clear();
        nv21Bytes = null;

        List<Map<String, Object>> planes = new ArrayList<>();
        for (ImageConverterUtil.FramePlane item :
                imageProxy.getPlanes()) {
            Map<String, Object> plane = new HashMap<>();

            ByteBuffer pBuffer = item.getBuffer();
            byte[] bytes = new byte[pBuffer.remaining()];
            pBuffer.get(bytes);

            plane.put("bytes", bytes);
            plane.put("rowStride", item.getRowStride());
            plane.put("pixelStride", item.getPixelStride());
            planes.add(plane);
        }

        Map<String, Object> image = new HashMap<>();
        image.put("width", width);
        image.put("height", height);
        if (customRotationDegrees != null) {
            image.put("rotation", customRotationDegrees);
        } else {
            image.put("rotation", 0);
        }
        // image.put("bytes", nv21Bytes);
        image.put("planes", planes);
        image.put("format", "YUV_420_888");

        return image;
    }


}
