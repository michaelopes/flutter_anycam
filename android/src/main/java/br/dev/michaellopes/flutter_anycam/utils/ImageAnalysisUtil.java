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


}
