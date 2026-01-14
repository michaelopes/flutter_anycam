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

import br.dev.michaellopes.flutter_anycam.model.FrameImage;

public class RTSPCameraProcessor extends BaseResultProcessor<FrameImage> {

    public int rotationDegrees = 0;

    public void setRotationDegrees(int rotationDegrees) {
        this.rotationDegrees = rotationDegrees;
    }

    @Override
    public Map<String, Object> process(FrameImage input, int width, int height, Integer customRotationDegrees) {
        Map<String, Object> adapter = new HashMap<>();
        adapter.put("height", width);
        adapter.put("width", height);
        adapter.put("format", "YUV_420_888");
        adapter.put("rotation", rotationDegrees);

        List<Map<String, Object>> planesAdapter = imagePlanesAdapter(input);

        byte[] bytes = imageToNV21(input);
        adapter.put("bytes", bytes);
        adapter.put("planes", planesAdapter);
        if (customRotationDegrees != null) {
            adapter.put("rotation", customRotationDegrees);
        }
        return adapter;
    }


    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private List<Map<String, Object>> imagePlanesAdapter(FrameImage image) {
        if (image == null) return new ArrayList<>();
        FrameImage.FramePlane[] planes = image.getPlanes();
        List<Map<String, Object>> planeData = new ArrayList<>();

        for (FrameImage.FramePlane plane : planes) {
            Map<String, Object> planeMap = new HashMap<>();
            planeMap.put("bytes", plane.getBuffer());
            planeMap.put("rowStride", plane.getRowStride());
            planeMap.put("pixelStride", plane.getPixelStride());

            planeData.add(planeMap);
        }

        return planeData;
    }

    private byte[] imageToNV21(FrameImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        FrameImage.FramePlane[] planes = image.getPlanes();

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

    static class Data {
       public final byte[] yData;
        public final byte[] uData;
        public final byte[] vData;

        public Data(byte[] yData, byte[] uData, byte[] vData) {
            this.yData = yData;
            this.uData = uData;
            this.vData = vData;
        }
    }


}

