package br.dev.michaellopes.flutter_anycam.utils;

import java.nio.ByteBuffer;

import io.github.crow_misia.libyuv.Nv21Buffer;
import io.github.crow_misia.libyuv.RotateMode;

public class Nv21Rotator {

    public synchronized byte[] rotateNv21(byte[] input, int width, int height, int rotation) {
        byte[] output = new byte[input.length];
        if (rotation == 0) {
            System.arraycopy(input, 0, output, 0, input.length);
            return output;
        }
        boolean swap = (rotation == 90 || rotation == 270);
        boolean yflip = (rotation == 90 || rotation == 180);
        boolean xflip = (rotation == 270 || rotation == 180);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int xo = x, yo = y;
                int w = width, h = height;
                int xi = xo, yi = yo;
                if (swap) {
                    xi = w * yo / h;
                    yi = h * xo / w;
                }
                if (yflip) {
                    yi = h - yi - 1;
                }
                if (xflip) {
                    xi = w - xi - 1;
                }
                output[w * yo + xo] = input[w * yi + xi];
                int fs = w * h;
                int qs = (fs >> 2);
                xi = (xi >> 1);
                yi = (yi >> 1);
                xo = (xo >> 1);
                yo = (yo >> 1);
                w = (w >> 1);
                h = (h >> 1);
                // adjust for interleave here
                int ui = fs + (w * yi + xi) * 2;
                int uo = fs + (w * yo + xo) * 2;
                // and here
                int vi = ui + 1;
                int vo = uo + 1;
                output[uo] = input[ui];
                output[vo] = input[vi];
            }
        }
        return  output;
    }

}