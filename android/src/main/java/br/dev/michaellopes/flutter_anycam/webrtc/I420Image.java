package br.dev.michaellopes.flutter_anycam.webrtc;

import java.nio.ByteBuffer;

public class I420Image {
    public final int width;
    public final int height;
    public final int rotation;

    public final ByteBuffer dataY;
    public final int strideY;

    public final ByteBuffer dataU;
    public final int strideU;

    public final ByteBuffer dataV;
    public final int strideV;

    public final int pixelStrideU;
    public final int pixelStrideV;

    private OnClose onClose;
    private boolean isClose = false;

    public I420Image(
            int width,
            int height,
            int rotation,
            ByteBuffer dataY,
            int strideY,
            ByteBuffer dataU,
            int strideU,
            ByteBuffer dataV,
            int strideV,
            int pixelStrideU,
            int pixelStrideV
    ) {
        this.width = width;
        this.height = height;
        this.rotation = rotation;
        this.dataY = dataY;
        this.strideY = strideY;
        this.dataU = dataU;
        this.strideU = strideU;
        this.dataV = dataV;
        this.strideV = strideV;
        this.pixelStrideU = pixelStrideU;
        this.pixelStrideV = pixelStrideV;
    }

    public I420Image(
            int width,
            int height,
            int rotation,
            byte[] dataY,
            int strideY,
            byte[] dataU,
            int strideU,
            byte[] dataV,
            int strideV,
            int pixelStrideU,
            int pixelStrideV
    ) {
        ByteBuffer yBuffer = ByteBuffer.allocateDirect(dataY.length);
        yBuffer.put(dataY);
        yBuffer.flip();

        ByteBuffer uBuffer = ByteBuffer.allocateDirect(dataU.length);
        uBuffer.put(dataU);
        uBuffer.flip();

        ByteBuffer vBuffer = ByteBuffer.allocateDirect(dataV.length);
        vBuffer.put(dataV);
        vBuffer.flip();

        this.width = width;
        this.height = height;
        this.rotation = rotation;
        this.dataY = yBuffer;
        this.strideY = strideY;
        this.dataU = uBuffer;
        this.strideU = strideU;
        this.dataV = vBuffer;
        this.strideV = strideV;
        this.pixelStrideU = pixelStrideU;
        this.pixelStrideV = pixelStrideV;
    }

    void setOnClose(OnClose callback) {
        this.onClose = callback;
    }

    public void close() {
        if (!isClose) {
            if (onClose != null) {
                onClose.run(this);
            } else {
                dataY.clear();
                dataU.clear();
                dataV.clear();
            }
            isClose = true;
        }
    }

    public interface OnClose {
        void run(I420Image ref);
    }
}
