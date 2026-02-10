package br.dev.michaellopes.flutter_anycam.utils;

import io.github.crow_misia.libyuv.I420Buffer;
import io.github.crow_misia.libyuv.Nv12Buffer;

public class FastNv21ToNv12Converter {
    private byte[] nv12Buffer;
    public synchronized byte[] convert(byte[] nv21, int width, int height) {
        final int frameSize = width * height;
        final int totalSize = frameSize + (frameSize / 2);

        if (nv12Buffer == null || nv12Buffer.length != totalSize) {
            nv12Buffer = new byte[totalSize];
        }

        System.arraycopy(nv21, 0, nv12Buffer, 0, frameSize);

        for (int i = frameSize; i < totalSize; i += 2) {
            nv12Buffer[i] = nv21[i + 1];     // U
            nv12Buffer[i + 1] = nv21[i];     // V
        }
        return nv12Buffer;
    }

    public void dispose() {
        nv12Buffer = null;
    }
}
