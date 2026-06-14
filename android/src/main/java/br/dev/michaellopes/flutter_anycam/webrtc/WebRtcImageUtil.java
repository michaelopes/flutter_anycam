package br.dev.michaellopes.flutter_anycam.webrtc;

import android.annotation.SuppressLint;
import android.media.Image;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.Optional;

public final class WebRtcImageUtil {

    private WebRtcImageUtil() {
    }

    @SuppressLint("UnsafeOptInUsageError")
    public static Optional<I420Image> fromImageProxy(
            ImageProxy imageProxy,
            Integer customRotationDegrees
    ) {
        Image image = imageProxy.getImage();
        if (image == null) {
            return Optional.empty();
        }

        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            return Optional.empty();
        }

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        if (customRotationDegrees != null) {
            rotation = customRotationDegrees;
        }

        Image.Plane yPlane = planes[0];
        Image.Plane uPlane = planes[1];
        Image.Plane vPlane = planes[2];

        return Optional.of(new I420Image(
                image.getWidth(),
                image.getHeight(),
                rotation,
                copyToDirect(yPlane.getBuffer()),
                yPlane.getRowStride(),
                copyToDirect(uPlane.getBuffer()),
                uPlane.getRowStride(),
                copyToDirect(vPlane.getBuffer()),
                vPlane.getRowStride(),
                uPlane.getPixelStride(),
                vPlane.getPixelStride()
        ));
    }

    private static ByteBuffer copyToDirect(ByteBuffer src) {
        src.rewind();
        ByteBuffer direct = ByteBuffer.allocateDirect(src.remaining());
        direct.put(src);
        direct.flip();
        src.rewind();
        return direct;
    }
}
