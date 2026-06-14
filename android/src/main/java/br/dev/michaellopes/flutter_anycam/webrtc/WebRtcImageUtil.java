package br.dev.michaellopes.flutter_anycam.webrtc;

import android.annotation.SuppressLint;
import android.media.Image;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

public final class WebRtcImageUtil {

    private WebRtcImageUtil() {
    }

    @SuppressLint("UnsafeOptInUsageError")
    public static I420Image fromImageProxy(ImageProxy imageProxy, Integer customRotationDegrees) {
        Image image = imageProxy.getImage();
        if (image == null) {
            return null;
        }

        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            return null;
        }

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        if (customRotationDegrees != null) {
            rotation = customRotationDegrees;
        }

        Image.Plane yPlane = planes[0];
        Image.Plane uPlane = planes[1];
        Image.Plane vPlane = planes[2];

        return new I420Image(
                image.getWidth(),
                image.getHeight(),
                rotation,
                bufferToBytes(yPlane.getBuffer()),
                yPlane.getRowStride(),
                bufferToBytes(uPlane.getBuffer()),
                uPlane.getRowStride(),
                bufferToBytes(vPlane.getBuffer()),
                vPlane.getRowStride(),
                uPlane.getPixelStride(),
                vPlane.getPixelStride()
        );
    }

    private static byte[] bufferToBytes(ByteBuffer buffer) {
        buffer.rewind();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
