package br.dev.michaellopes.flutter_anycam.utils;

import android.annotation.SuppressLint;
import android.media.Image;

import androidx.camera.core.ImageProxy;
import androidx.camera.core.internal.utils.ImageUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.Log;

public class ImageAnalysisUtil {

    public Map<String, Object> imageProxyToNV21Map(ImageProxy imageProxy, Integer customRotationDegrees) {
        return imageProxyToNV21Map(imageProxy, customRotationDegrees, null);
    }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    public Map<String, Object> imageProxyToNV21Map(ImageProxy imageProxy, Integer customRotationDegrees, byte[] nv21) {
        try {

            Image image = imageProxy.getImage();
            if (image == null) return new HashMap<>();

            long start = System.nanoTime();

            byte[] bytes = nv21 != null ? nv21 : YuvUtil.yuv420ToNv21(image).get();

            long end = System.nanoTime();
            long durationNs = end - start;

            double durationMs = durationNs / 1_000_000.0;

            Log.d("PERF", "Tempo: " + durationMs + " ms");
            Map<String, Object> result = new HashMap<>();

            int width = image.getWidth();
            int height = image.getHeight();
            Image.Plane firstPlane = image.getPlanes()[0];

            result.put("height", height);
            result.put("width", width);
            result.put("format", "NV21");
            result.put("bytes", bytes);

            if (customRotationDegrees != null) {
                result.put("rotation", customRotationDegrees);
            } else {
                result.put("rotation", imageProxy.getImageInfo().getRotationDegrees());
            }

            result.put("rowStride", firstPlane.getRowStride());
            result.put("pixelStride", firstPlane.getPixelStride());
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

    public Map<String, Object> usbFrameToNV21Map(ByteBuffer buffer, int width, int height, Integer customRotationDegrees) {
        byte[] nv21Bytes = new byte[buffer.remaining()];
        buffer.get(nv21Bytes);

        Map<String, Object> image = new HashMap<>();
        image.put("width", width);
        image.put("height", height);
        if (customRotationDegrees != null) {
            image.put("rotation", customRotationDegrees);
        } else {
            image.put("rotation", 0);
        }
        image.put("bytes", nv21Bytes);
        image.put("format", "NV21");
        image.put("rowStride", width);
        image.put("pixelStride", 1);

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


    public Map<String, Object> rtspFrameToFlutterResult(byte[] yv12Bytes, int width, int height, Integer customRotationDegrees) {

        byte[] nv21Bytes = ImageConverterUtil.i420ToNv21(yv12Bytes, width, height);
        ImageConverterUtil.FrameImageProxy imageProxy = ImageConverterUtil.convertNV21ToFrameImageProxy(nv21Bytes, width, height);
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
        image.put("isPortrait", height > width);
        image.put("bytes", nv21Bytes);
        image.put("planes", planes);
        image.put("format", "YUV_420_888");

        return image;
    }

}
