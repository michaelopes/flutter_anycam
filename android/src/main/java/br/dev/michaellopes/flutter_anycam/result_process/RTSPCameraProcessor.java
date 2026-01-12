package br.dev.michaellopes.flutter_anycam.result_process;

import android.annotation.SuppressLint;
import android.media.Image;

import androidx.camera.core.ImageProxy;
import androidx.camera.core.internal.utils.ImageUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class RTSPCameraProcessor extends BaseResultProcessor<Image> {

    public int rotationDegrees = 0;


    public void setRotationDegrees(int rotationDegrees) {
        this.rotationDegrees = rotationDegrees;
    }

    @Override
    public Map<String, Object> process(Image input, int width, int height, Integer customRotationDegrees) {
        List<Map<String, Object>> planesAdapter = imagePlanesAdapter(input);
        /*Map<String, Object> adapter = imageProxyBaseAdapter(input);
      //  byte[] bytes = imageToNV21(input);
        byte[] bytes = null;
        adapter.put("bytes", bytes);
        adapter.put("planes", planesAdapter);
        if (customRotationDegrees != null) {
            adapter.put("rotation", customRotationDegrees);
        }
        return adapter;*/
        return Collections.emptyMap();
    }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private Map<String, Object> imageProxyBaseAdapter(Image image) {
        if (image == null) return new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        result.put("height", image.getHeight());
        result.put("width", image.getWidth());
        result.put("format", "YUV_420_888");
        result.put("rotation", rotationDegrees);
        return result;
    }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private List<Map<String, Object>> imagePlanesAdapter(Image image) {
        if (image == null) return new ArrayList<>();
        Image.Plane[] planes = image.getPlanes();
        List<Map<String, Object>> planeData = new ArrayList<>();

        for (Image.Plane plane : planes) {
            ByteBuffer buffer = plane.getBuffer().duplicate();
            buffer.rewind();
            byte[] bytes = new byte[buffer.remaining()];
            System.out.println("teste");
            try {
                buffer.get(bytes);
            } catch (Exception e) {
                e.printStackTrace();
            }
 /*
            Map<String, Object> planeMap = new HashMap<>();
            planeMap.put("bytes", bytes);
            planeMap.put("rowStride", plane.getRowStride());
            planeMap.put("pixelStride", plane.getPixelStride());

            planeData.add(planeMap);*/
        }

        return planeData;
    }

    private byte[] imageToNV21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();

        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuf = planes[0].getBuffer().duplicate();
        ByteBuffer uBuf = planes[1].getBuffer().duplicate();
        ByteBuffer vBuf = planes[2].getBuffer().duplicate();

        yBuf.rewind();
        uBuf.rewind();
        vBuf.rewind();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();

        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();

        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        byte[] nv21 = new byte[width * height * 3 / 2];
        int index = 0;

        // Y
        for (int row = 0; row < height; row++) {
            int yRowStart = row * yRowStride;
            for (int col = 0; col < width; col++) {
                nv21[index++] = yBuf.get(yRowStart + col * yPixelStride);
            }
        }

        // VU
        int uvHeight = height / 2;
        int uvWidth = width / 2;

        for (int row = 0; row < uvHeight; row++) {
            int uRowStart = row * uRowStride;
            int vRowStart = row * vRowStride;

            for (int col = 0; col < uvWidth; col++) {
                nv21[index++] = vBuf.get(vRowStart + col * vPixelStride);
                nv21[index++] = uBuf.get(uRowStart + col * uPixelStride);
            }
        }

        return nv21;
    }

}

