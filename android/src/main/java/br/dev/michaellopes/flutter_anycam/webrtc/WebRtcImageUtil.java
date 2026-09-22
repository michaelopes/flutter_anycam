package br.dev.michaellopes.flutter_anycam.webrtc;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.media.Image;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.Optional;

import io.github.crow_misia.libyuv.FilterMode;
import io.github.crow_misia.libyuv.I420Buffer;
import io.github.crow_misia.libyuv.Nv21Buffer;
import io.github.crow_misia.libyuv.PlanePrimitive;

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

    public static Optional<I420Image> prepareWebRtcFrame(
            ImageProxy imageProxy,
            Integer customRotationDegrees,
            int streamWidth,
            int streamHeight
    ) {
        Optional<I420Image> optional = fromImageProxy(imageProxy, customRotationDegrees);
        if (optional.isEmpty()) {
            return optional;
        }

        I420Image image = optional.get();
        if (!shouldScale(streamWidth, streamHeight, image.width, image.height)) {
            return optional;
        }

        return scaleI420(image, streamWidth, streamHeight);
    }

    public static Optional<I420Image> fromImage(
            Image image,
            int rotationDegrees,
            Integer customRotationDegrees
    ) {
        if (image == null) {
            return Optional.empty();
        }

        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            return Optional.empty();
        }

        int rotation = customRotationDegrees != null ? customRotationDegrees : rotationDegrees;

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

    public static Optional<I420Image> prepareWebRtcFrame(
            Image image,
            int rotationDegrees,
            Integer customRotationDegrees,
            int streamWidth,
            int streamHeight
    ) {
        Optional<I420Image> optional = fromImage(image, rotationDegrees, customRotationDegrees);
        if (optional.isEmpty()) {
            return optional;
        }

        I420Image frame = optional.get();
        if (!shouldScale(streamWidth, streamHeight, frame.width, frame.height)) {
            return optional;
        }

        return scaleI420(frame, streamWidth, streamHeight);
    }

    /**
     * Tightly packed NV21 (Y then interleaved VU, stride = width) → planar I420 in freshly
     * allocated direct buffers. {@code nv21} is only read.
     */
    public static I420Image fromNv21(ByteBuffer nv21, int width, int height, int rotation) {
        I420Buffer dst = I420Buffer.Factory.allocate(width, height);
        try {
            ByteBuffer src = nv21.duplicate();
            src.rewind();
            Nv21Buffer srcBuf = Nv21Buffer.Factory.wrap(src, width, height);
            try {
                srcBuf.convertTo(dst);
            } finally {
                srcBuf.close();
            }
            return new I420Image(
                    width,
                    height,
                    rotation,
                    copyToDirect(dst.getPlaneY().getBuffer()),
                    dst.getPlaneY().getRowStride(),
                    copyToDirect(dst.getPlaneU().getBuffer()),
                    dst.getPlaneU().getRowStride(),
                    copyToDirect(dst.getPlaneV().getBuffer()),
                    dst.getPlaneV().getRowStride(),
                    1,
                    1
            );
        } finally {
            dst.close();
        }
    }

    public static I420Image ensureI420(I420Image src) {
        int expectedChromaStride = src.width / 2;

        boolean alreadyI420 =
                src.pixelStrideU == 1
                        && src.pixelStrideV == 1
                        && src.strideU == expectedChromaStride
                        && src.strideV == expectedChromaStride;

        if (alreadyI420) {
            return src;
        }

        return YuvUtil.normalizeToI420(src);
    }

    public static Optional<I420Image> scaleI420(I420Image src, int dstW, int dstH) {
        if (!shouldScale(dstW, dstH, src.width, src.height)) {
            return Optional.of(src);
        }

        I420Image normalized = ensureI420(src);
        I420Buffer dstBuf = null;
        try {
            I420Buffer srcBuf = toLibYuvBuffer(normalized);
            Rect crop = new Rect(0, 0, dstW, dstH);
            dstBuf = I420Buffer.Factory.allocate(dstW, dstH, crop);
            srcBuf.scale(dstBuf, FilterMode.BOX);

            I420Image scaled = new I420Image(
                    dstW,
                    dstH,
                    normalized.rotation,
                    copyToDirect(dstBuf.getPlaneY().getBuffer()),
                    dstBuf.getPlaneY().getRowStride(),
                    copyToDirect(dstBuf.getPlaneU().getBuffer()),
                    dstBuf.getPlaneU().getRowStride(),
                    copyToDirect(dstBuf.getPlaneV().getBuffer()),
                    dstBuf.getPlaneV().getRowStride(),
                    1,
                    1
            );
            return Optional.of(scaled);
        } finally {
            if (dstBuf != null) {
                dstBuf.close();
            }
            if (normalized != src) {
                normalized.close();
            }
            src.close();
        }
    }

    private static boolean shouldScale(int dstW, int dstH, int srcW, int srcH) {
        return dstW > 0 && dstH > 0 && (srcW != dstW || srcH != dstH);
    }

    private static I420Buffer toLibYuvBuffer(I420Image image) {
        Rect crop = new Rect(0, 0, image.width, image.height);
        return I420Buffer.Factory.wrap(
                PlanePrimitive.create(image.strideY, image.dataY),
                PlanePrimitive.create(image.strideU, image.dataU),
                PlanePrimitive.create(image.strideV, image.dataV),
                image.width,
                image.height,
                crop
        );
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
