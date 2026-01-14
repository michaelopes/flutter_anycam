package br.dev.michaellopes.flutter_anycam.result_process;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.dev.michaellopes.flutter_anycam.model.FrameImage;
import br.dev.michaellopes.flutter_anycam.utils.ImageConverterUtil;

public class UsbCameraProcessor extends BaseResultProcessor<ByteBuffer> {
    @Override
    public Map<String, Object> process(ByteBuffer input, int width, int height, Integer customRotationDegrees) {
        byte[] nv21Bytes = new byte[input.remaining()];
        input.get(nv21Bytes);

        FrameImage imageProxy = ImageConverterUtil.convertNV21ToFrameImageProxy(nv21Bytes, width, height);
        List<Map<String, Object>> planes = new ArrayList<>();
        for (FrameImage.FramePlane item :
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
}
