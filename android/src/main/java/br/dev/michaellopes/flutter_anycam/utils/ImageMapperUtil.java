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
    private byte[] bytesResizedBuffer;
    private byte[] bytesBuffer;

    public Map<String, Object> imageProxyToNV21Map(ImageProxy imageProxy, Size resizeFrame, int filter, Integer customRotationDegrees) {
        return imageProxyToNV21Map(imageProxy, resizeFrame, filter, customRotationDegrees, null);
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

            if (nv21 == null) {
                int srcSize = width * height * 3 / 2;
                if (bytesBuffer == null || srcSize != bytesBuffer.length) {
                    bytesBuffer = new byte[srcSize];
                }
                YuvUtil.yuv420ToNv21(image, bytesBuffer);
            } else {
                bytesBuffer = nv21;
            }

            if(resizeFrame != null) {
                rawFrame = imageProxyToNV21Map(imageProxy, null, 0, customRotationDegrees, bytesBuffer);
            }

            byte[] finalBytes;
            if (resizeFrame != null) {

                int targetWidth;
                int targetHeight;
                if (resizeFrame.getHeight() == -1 && resizeFrame.getWidth() == -1) {
                    int minSize = Math.min(width, height);
                    targetWidth = minSize;
                    targetHeight = minSize;
                } else {
                    targetWidth = resizeFrame.getWidth();
                    targetHeight = resizeFrame.getHeight();
                }

                int dstSize = targetWidth * targetHeight * 3 / 2;
                if (bytesResizedBuffer == null || dstSize != bytesResizedBuffer.length) {
                    bytesResizedBuffer = new byte[dstSize];
                }
                YuvUtil.resizeNv21(bytesBuffer, width, height, bytesResizedBuffer, targetWidth, targetHeight);
                finalBytes = bytesResizedBuffer;
                width = targetWidth;
                height = targetHeight;
                rowStride = targetWidth;
                pixelStride = 1;
            } else {
                Image.Plane firstPlane = image.getPlanes()[0];
                rowStride = firstPlane.getRowStride();
                pixelStride = firstPlane.getPixelStride();
                finalBytes = bytesBuffer;
            }


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

            YuvUtil.applyFilter(finalBytes, width, height, filter);

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
        if (bytesBuffer == null || srcSize != bytesBuffer.length) {
            bytesBuffer = new byte[srcSize];
        }
        buffer.get(bytesBuffer);
        return  usbFrameToNV21Map(bytesBuffer, width, height, resizeFrame, filter, customRotationDegrees);
    }

    public Map<String, Object> usbFrameToNV21Map(byte[] nv21, int width, int height, Size resizeFrame, int filter, Integer customRotationDegrees) {
        Map<String, Object> rawFrame = null;

        if(resizeFrame != null) {
            rawFrame = usbFrameToNV21Map(nv21, width, height, null, 0, customRotationDegrees);
        }

        byte[] finalBytes;
        if (resizeFrame != null) {
            int targetWidth;
            int targetHeight;
            if (resizeFrame.getHeight() == -1 && resizeFrame.getWidth() == -1) {
                int minSize = Math.min(width, height);
                targetWidth = minSize;
                targetHeight = minSize;
            } else {
                targetWidth = resizeFrame.getWidth();
                targetHeight = resizeFrame.getHeight();
            }
            int dstSize = targetWidth * targetHeight * 3 / 2;
            if (bytesResizedBuffer == null || dstSize != bytesResizedBuffer.length) {
                bytesResizedBuffer = new byte[dstSize];
            }
            YuvUtil.resizeNv21(nv21, width, height, bytesResizedBuffer, targetWidth, targetHeight);
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

        YuvUtil.applyFilter(finalBytes, width, height, filter);
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


    public Map<String, Object> rtspFrameToNV21Map(byte[] nv21Bytes, int width, int height, int filter, Integer customRotationDegrees) {
        Map<String, Object> image = new HashMap<>();
        image.put("width", width);
        image.put("height", height);
        if (customRotationDegrees != null) {
            image.put("rotation", customRotationDegrees);
        } else {
            image.put("rotation", 0);
        }


        YuvUtil.applyFilter(nv21Bytes, width, height, filter);

        image.put("bytes", nv21Bytes);
        image.put("format", "NV21");
        image.put("rowStride", width);
        image.put("pixelStride", 1);
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
