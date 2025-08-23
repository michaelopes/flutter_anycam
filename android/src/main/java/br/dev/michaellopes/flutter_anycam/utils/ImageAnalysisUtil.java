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

public class ImageAnalysisUtil {
    @SuppressLint("RestrictedApi")
    public Map<String, Object> imageProxyToFlutterResult(ImageProxy image, Integer customRotationDegrees) {

        List<Map<String, Object>> planesAdapter = imagePlanesAdapter(image);
        Map<String, Object> adapter = imageProxyBaseAdapter(image);
        byte[] bytes = ImageUtil.yuv_420_888toNv21(image);

        adapter.put("bytes", bytes);
        adapter.put("planes", planesAdapter);
        if(customRotationDegrees != null) {
            adapter.put("rotation", customRotationDegrees);
        }
        return adapter;
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

    public Map<String, Object> usbFrameToFlutterResult(ByteBuffer buffer, int width, int height, Integer customRotationDegrees) {
        byte[] nv21Bytes = new byte[buffer.remaining()];
        buffer.get(nv21Bytes);

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
        if(customRotationDegrees != null) {
            image.put("rotation", customRotationDegrees);
        } else {
            image.put("rotation", 0);
        }
        image.put("bytes", nv21Bytes);
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
        if(customRotationDegrees != null) {
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
